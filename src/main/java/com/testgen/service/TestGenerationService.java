package com.testgen.service;

import com.testgen.generator.KarateTestGenerator;
import com.testgen.generator.RestAssuredTestGenerator;
import com.testgen.generator.SeleniumTestGenerator;
import com.testgen.model.*;
import com.testgen.repository.TestGenerationRequestRepository;
import com.testgen.repository.GeneratedTestCaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class TestGenerationService {

    private final KarateTestGenerator karateTestGenerator;
    private final SeleniumTestGenerator seleniumTestGenerator;
    private final RestAssuredTestGenerator restAssuredTestGenerator;
    private final TestGenerationRequestRepository requestRepository;
    private final GeneratedTestCaseRepository testCaseRepository;
    private final AiAgentOrchestratorService aiAgentOrchestratorService;
    private final AiTestDataGenerationService aiTestDataGenerationService;
    private final AgentLearningService agentLearningService;
    private final ObservationService observationService;
    private final com.testgen.generator.TestContentGate testContentGate;
    private final com.testgen.generator.TestCaseClassifier testCaseClassifier;
    private final com.testgen.metrics.TestGenMetrics metrics;

    @Transactional
    public TestGenerationRequest createRequest(TestGenerationRequest request) {
        request.setStatus(RequestStatus.PENDING);
        return requestRepository.save(request);
    }

    /**
     * Test üretimi — LLM/ajan adımları nedeniyle dakikalarca sürebilir.
     *
     * BİLEREK transaction'sız: tüm üretim tek bir uzun transaction içinde koşarsa
     *  (a) GENERATING durumu commit edilmediği için dashboard üretim boyunca isteği
     *      hâlâ PENDING gösterir,
     *  (b) üretim sürerken başka bir istek (örn. scheduler enable) aynı satırı
     *      güncellerse, uzun transaction commit ederken elindeki BAYAT kopyayı yazıp
     *      o güncellemeyi sessizce geri alır (lost update).
     * Bunun yerine her durum değişikliği kısa, kendi transaction'ında ve satır
     * yeniden okunarak yazılır.
     */
    @Async
    public CompletableFuture<List<GeneratedTestCase>> generateTests(String requestId) {
        var request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request bulunamadı: " + requestId));

        log.info("Test üretimi başlıyor - requestId: {}, type: {}, framework: {}",
                requestId, request.getTestType(), request.getFramework());

        long generationStart = System.currentTimeMillis();
        // Bu noktadan sonra yapılan TÜM LLM çağrıları bu isteğe ait sayılır. Korelasyon
        // olmadan "hangi üretim kaç çağrıya, kaç saniyeye ve kaç token'a mal oldu"
        // sorusu ölçülemiyordu; çağrı geçmişi ekranında istek bilgisi hiç yoktu.
        com.testgen.llm.LlmCallContext.set(requestId,
                com.testgen.llm.LlmCallContext.Phase.GENERATION);
        try {
            updateRequest(requestId, request, fresh -> fresh.setStatus(RequestStatus.GENERATING));

            // 1) ÖNCE GÖZLEMLE: hedeften gerçek veri topla — ajanlar tahmin etmesin
            request.setAdditionalContext(observationService.enrichWithObservations(request));
            // Gözlem kanıtını HEMEN kalıcı yaz: üretim reddedilse bile kullanıcı,
            // neyin gözlendiğini (ya da neden gözlenemediğini) ekranda görebilmeli.
            persistObservation(requestId, request);
            requireObservationWhenTargetGiven(request);
            // 2) Geçmiş koşum dersleri: aynı servisin bilinen tuzakları
            request.setAdditionalContext(agentLearningService.enrichWithLearnings(request));
            // 3) Ajan analizi: gözlem + dersler ışığında.
            //    Ölçüm koşumunun kontrol kolunda bilinçli olarak atlanır.
            if (request.isAgentsEnabled()) {
                request.setAdditionalContext(aiAgentOrchestratorService.enrichAdditionalContext(request));
            } else {
                log.info("Ajan analizi bu istek için devre dışı (ölçüm kontrol kolu) - requestId: {}", requestId);
            }
            request.setAdditionalContext(aiTestDataGenerationService.enrichAdditionalContext(request));

            String enrichedContext = request.getAdditionalContext();
            updateRequest(requestId, request, fresh -> fresh.setAdditionalContext(enrichedContext));

            List<GeneratedTestCase> cases = switch (request.getFramework()) {
                case KARATE  -> karateTestGenerator.generate(request);
                case SELENIUM -> seleniumTestGenerator.generate(request);
                case REST_ASSURED -> restAssuredTestGenerator.generate(request);
            };

            if (cases.isEmpty()) {
                throw new TestGenerationException("Calistirilabilir test case uretilemedi.");
            }

            // ÜRETİM KAPISI: her case DB'ye yazılmadan önce makine ile doğrulanır
            // (Karate parse / Java derleme). Geçemezse hata LLM'e geri verilip düzeltilmeye
            // çalışılır; yine geçemezse case INVALID olarak işaretlenir — koşumda
            // "0/0 FAILED" olarak keşfedilmesi beklenmez.
            cases.forEach(testContentGate::apply);

            // ISTQB sınıflandırması: kategori / seviye / tasarım tekniği. Bu alanlar veri
            // modelinde tanımlıydı ama hiç doldurulmuyordu; sınıflandırma olmadan
            // "hangi test sınıfı düşüyor" sorusu ölçülemiyor.
            cases.forEach(tc -> testCaseClassifier.classify(tc, request.getTestType()));
            logValidationSummary(requestId, cases);

            TestGenerationRequest owner = requestRepository.findById(requestId).orElse(request);
            cases.forEach(tc -> tc.setRequest(owner));
            testCaseRepository.saveAll(cases);

            updateRequest(requestId, request, fresh -> fresh.setStatus(RequestStatus.GENERATED));

            metrics.recordGeneration(request.getFramework(),
                    System.currentTimeMillis() - generationStart, cases.size());
            log.info("{} adet test case üretildi - requestId: {}", cases.size(), requestId);
            return CompletableFuture.completedFuture(cases);

        } catch (Exception e) {
            log.error("Test üretimi başarısız - requestId: {}", requestId, e);
            updateRequest(requestId, request, fresh -> fresh.setStatus(RequestStatus.FAILED));
            throw new TestGenerationException("Test üretimi başarısız: " + e.getMessage(), e);
        } finally {
            // Havuz iş parçacığı yeniden kullanılır; bağlam bırakılırsa sonraki isteğin
            // çağrıları yanlış requestId ile etiketlenir.
            com.testgen.llm.LlmCallContext.clear();
        }
    }

    /** Gözlem kanıtını (ya da atlama nedenini) isteğe yazar. */
    private void persistObservation(String requestId, TestGenerationRequest observed) {
        updateRequest(requestId, observed, fresh -> {
            fresh.setObservedRequestLine(observed.getObservedRequestLine());
            fresh.setObservedStatus(observed.getObservedStatus());
            fresh.setObservedDurationMs(observed.getObservedDurationMs());
            fresh.setObservedBody(observed.getObservedBody());
            fresh.setObservationSkipReason(observed.getObservationSkipReason());
            fresh.setObservedAt(observed.getObservedAt());
        });
    }

    /**
     * Somut bir hedef verildiği hâlde gözlem yapılamadıysa üretimi <b>reddeder</b>.
     *
     * <p><b>Neden:</b> ölçülen bir koşumda gözlem başarısız oldu ve akış sessizce devam
     * etti. Bağlama giren tek şey "gözlemlenemedi" notuydu; ajanlar bunu <i>"endpoint
     * erişilemez"</i> diye okudu ve analizin tamamını bu yanlış öncüle dayandırdı —
     * dört ajan aynı hatalı cümleyi sayfalarca tekrarladı. Üretilen hiçbir şey
     * ölçüme dayanmıyordu ama sistem başarılı göründü.
     *
     * <p>Kural: hedef verildiyse üretim ölçüme dayanır; ölçüm yoksa üretim yapılmaz.
     * Yalnızca kullanıcı hikâyesi verilen (hedefsiz) istekler bu kuralın dışındadır —
     * orada gözlemlenecek bir şey yoktur.
     */
    private void requireObservationWhenTargetGiven(TestGenerationRequest request) {
        boolean hasTarget =
                (request.getRawPayload() != null && !request.getRawPayload().isBlank())
                || (request.getSwaggerUrl() != null && !request.getSwaggerUrl().isBlank())
                || (request.getApplicationUrl() != null && !request.getApplicationUrl().isBlank());
        if (!hasTarget) {
            return;
        }
        String ctx = request.getAdditionalContext();
        if (ctx != null && com.testgen.service.ObservationService.isObserved(
                extractObservedSection(ctx))) {
            return;
        }
        throw new TestGenerationException(
                "Hedef verildi ancak gözlem yapılamadı — üretim ölçülmemiş veriye dayanamaz. "
                + "Yan etkili istekler için observeMutating=true ile onay verin ya da hedefe "
                + "erişimi doğrulayın. Gözlem notu: "
                + firstLineOf(extractObservedSection(ctx)));
    }

    /** Bağlamdaki OBSERVED bölümünü ayırır; yoksa boş döner. */
    private static String extractObservedSection(String context) {
        if (context == null) return "";
        int i = context.indexOf(com.testgen.service.ObservationService.SECTION_TITLE);
        return i < 0 ? "" : context.substring(i);
    }

    private static String firstLineOf(String s) {
        if (s == null || s.isBlank()) return "(gözlem bölümü hiç oluşmadı)";
        String[] lines = s.split("\n", 3);
        return lines.length > 1 ? lines[1] : lines[0];
    }

    /** Üretim kapısının sonucunu tek satırda özetler — kalite regresyonu logdan izlenebilsin. */
    private void logValidationSummary(String requestId, List<GeneratedTestCase> cases) {
        log.info("Üretim doğrulaması — requestId: {} | toplam: {} | VALID: {} | INVALID: {} | SKIPPED: {}",
                requestId, cases.size(),
                countByStatus(cases, ValidationStatus.VALID),
                countByStatus(cases, ValidationStatus.INVALID),
                countByStatus(cases, ValidationStatus.SKIPPED));
    }

    private long countByStatus(List<GeneratedTestCase> cases, ValidationStatus status) {
        return cases.stream().filter(c -> c.getValidationStatus() == status).count();
    }

    /**
     * Satırı yeniden okuyup yalnızca ilgili alanı güncelleyerek kısa bir transaction'da yazar.
     * Böylece uzun süren üretim, aynı satırda paralel yapılan değişiklikleri ezmez.
     * Satır bulunamazsa (silinmişse) eldeki kopya üzerinde çalışılır.
     */
    private void updateRequest(String requestId, TestGenerationRequest fallback,
                               java.util.function.Consumer<TestGenerationRequest> mutation) {
        TestGenerationRequest fresh = requestRepository.findById(requestId).orElse(fallback);
        mutation.accept(fresh);
        requestRepository.save(fresh);
    }

    public List<GeneratedTestCase> getTestCasesByRequestId(String requestId) {
        return testCaseRepository.findByRequestId(requestId);
    }

    @Transactional
    public GeneratedTestCase addManualTestCase(String requestId, GeneratedTestCase testCase) {
        TestGenerationRequest request = getRequest(requestId);
        TestFramework framework = testCase.getFramework() != null ? testCase.getFramework() : request.getFramework();
        if (framework != request.getFramework()) {
            throw new TestGenerationException("Manuel test framework'u request framework'u ile uyumlu olmalı: " + request.getFramework());
        }

        String fileName = testCase.getFileName();
        if (fileName == null || fileName.isBlank()) {
            fileName = defaultFileName(testCase.getTestName(), framework);
        }

        testCase.setRequest(request);
        testCase.setFramework(framework);
        testCase.setFileName(fileName);
        testCase.setTestSummary(testCase.getTestSummary() == null || testCase.getTestSummary().isBlank()
                ? "[MANUAL] Kullanıcı tarafından manuel eklenen test case."
                : "[MANUAL] " + testCase.getTestSummary());
        testCase.setRunStatus(testCase.getRunStatus() == null ? TestRunStatus.NOT_RUN : testCase.getRunStatus());

        GeneratedTestCase saved = testCaseRepository.save(testCase);
        if (request.getStatus() == RequestStatus.FAILED || request.getStatus() == RequestStatus.PENDING) {
            request.setStatus(RequestStatus.GENERATED);
            requestRepository.save(request);
        }
        return saved;
    }

    public TestGenerationRequest getRequest(String requestId) {
        return requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request bulunamadı: " + requestId));
    }

    public List<TestGenerationRequest> getAllRequests() {
        return requestRepository.findAll();
    }

    private String defaultFileName(String testName, TestFramework framework) {
        String extension = framework == TestFramework.KARATE ? ".feature" : ".java";
        return testName.endsWith(extension) ? testName : testName + extension;
    }
}
