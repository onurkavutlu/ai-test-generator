package com.testgen.scheduler;

import com.testgen.llm.LlmService;
import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestRunStatus;
import com.testgen.model.ValidationStatus;
import com.testgen.generator.CodeCleaner;
import com.testgen.repository.GeneratedTestCaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Başarısız test case'leri LLM ile analiz edip düzeltilmiş versiyonunu üretir.
 *
 * İyileştirmeler:
 *  - maxHealAttempts: sonsuz döngüyü önler (config: test-generator.runner.max-heal-attempts)
 *  - parentCaseId: yeni case'in hangi başarısız case'den türediğini kaydeder
 *  - superseded: eski başarısız case "supersede" edilmiş olarak işaretlenir
 *  - llmModel / llmDurationMs: LLM meta bilgisi entity'ye yazılır
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FailureAnalysisService {

    private final LlmService llmService;
    private final GeneratedTestCaseRepository testCaseRepository;
    private final com.testgen.service.AgentLearningService agentLearningService;
    private final com.testgen.generator.TestContentGate testContentGate;

    @Value("${test-generator.runner.max-heal-attempts:3}")
    private int maxHealAttempts;

    /**
     * Tek bir iyileştirme turunda en fazla kaç case onarılsın.
     *
     * NEDEN GEREKLİ: Her case 2 LLM çağrısı demek (hata analizi + yeniden üretim).
     * Ölçülen bir koşumda 45 başarısız case, LLM'i 10 dakikadan uzun süre tekeline
     * aldı ve o sırada başlatılan yeni üretimler hiç sıra bulamadı. Sınırın dışında
     * kalan case'ler ATLANMIŞ SAYILMAZ: healAttempts artmadığı için sonraki turda
     * yeniden ele alınırlar.
     */
    @Value("${test-generator.runner.max-heal-batch:10}")
    private int maxHealBatch;

    public List<GeneratedTestCase> analyzeAndGenerateNew(
            List<GeneratedTestCase> failedCases,
            TestGenerationRequest request) {

        if (failedCases.isEmpty()) {
            log.debug("Analiz edilecek başarısız test yok — requestId: {}", request.getId());
            return List.of();
        }

        // Retry limitini aşmış case'leri filtrele
        List<GeneratedTestCase> eligible = failedCases.stream()
                .filter(tc -> tc.getHealAttempts() < maxHealAttempts)
                .toList();

        List<GeneratedTestCase> skipped = failedCases.stream()
                .filter(tc -> tc.getHealAttempts() >= maxHealAttempts)
                .toList();

        if (!skipped.isEmpty()) {
            skipped.forEach(tc -> log.warn(
                    "⛔ Max heal attempts ({}) aşıldı, atlanıyor: {}", maxHealAttempts, tc.getTestName()));
        }

        if (eligible.isEmpty()) {
            log.info("Tüm başarısız testler max retry limitine ulaştı.");
            return List.of();
        }

        // LLM'i tek turda tüketmemek için parti sınırı; artan case'ler sonraki tura kalır
        List<GeneratedTestCase> batch = eligible;
        if (maxHealBatch > 0 && eligible.size() > maxHealBatch) {
            batch = eligible.subList(0, maxHealBatch);
            log.warn("İyileştirme parti sınırı ({}) uygulandı: {} case'ten {}'i bu turda onarılacak, "
                            + "kalan {} case sonraki turda ele alınacak (deneme hakları harcanmadı).",
                    maxHealBatch, eligible.size(), batch.size(), eligible.size() - batch.size());
        }

        log.info("{} başarısız test analiz ediliyor (max deneme: {}) — requestId: {}",
                batch.size(), maxHealAttempts, request.getId());

        return batch.stream()
                .map(failed -> analyzeOneAndGenerate(failed, request))
                .filter(tc -> tc != null)
                .collect(Collectors.toList());
    }

    private GeneratedTestCase analyzeOneAndGenerate(GeneratedTestCase failedCase,
                                                     TestGenerationRequest request) {
        try {
            int nextVersion = failedCase.getHealAttempts() + 1;
            log.info("🔧 SELF-HEALING v{}: {} — requestId: {}",
                    nextVersion, failedCase.getTestName(), request.getId());

            String prompt    = buildAnalysisPrompt(failedCase, request);
            long   start     = System.currentTimeMillis();
            String llmRaw    = llmService.generateTestCase(prompt, "FAILURE_ANALYSIS");
            long   durationMs = System.currentTimeMillis() - start;

            String cleanContent = failedCase.getFramework() == TestFramework.KARATE
                    ? CodeCleaner.cleanFeatureContent(llmRaw)
                    // Java: dosya adı korunuyor → class adı da dosya adıyla eşitlenmeli (javac kuralı)
                    : CodeCleaner.normalizeGeneratedJavaTest(
                            CodeCleaner.cleanJavaContent(llmRaw), failedCase.getTestName());

            if (cleanContent.isBlank()) {
                log.warn("LLM boş içerik döndürdü — failedCase: {}", failedCase.getTestName());
                return null;
            }

            // Orijinal dosyanın üzerine yazmak istiyoruz, aksi takdirde diskte hem bozuk eski dosya
            // hem de yeni dosya kalır ve TestRunner ikisini de çalıştırır. Ayrıca Java'da dosya adı
            // değişirse içindeki 'public class' adıyla uyuşmaz ve derleme hatası verir.
            String newName = failedCase.getTestName();

            GeneratedTestCase healed = GeneratedTestCase.builder()
                    .testName(newName)
                    .fileName(failedCase.getFileName())
                    .testContent(cleanContent)
                    .testSummary("[AUTO-FIX][v" + nextVersion + "] "
                            + failedCase.getTestName() + " başarısız olduğu için LLM tarafından yeniden üretildi.")
                    .framework(failedCase.getFramework())
                    .runStatus(TestRunStatus.NOT_RUN)
                    .parentCaseId(failedCase.getId())
                    // Parent'ın deneme sayısını devral — sıfırlanırsa heal zinciri maxHealAttempts limitini aşar
                    .healAttempts(nextVersion)
                    .llmDurationMs(durationMs)
                    .llmPromptChars(prompt.length())
                    .llmResponseChars(llmRaw.length())
                    .build();

            // ÜRETİM KAPISI self-healing için de geçerlidir: aksi hâlde "düzeltilmiş" içerik
            // hiç doğrulanmadan kaydediliyor ve koşumda ayrıştırılamıyordu (canlıda ölçüldü:
            // Karate case'i için Java kodu + açıklama metni döndü, "missing FEATURE at <EOF>").
            testContentGate.apply(healed);

            // ÇALIŞAN TESTİ BOZUK VERSİYONLA DEĞİŞTİRME.
            // Canlıda ölçüldü: 11 senaryodan 8'i geçen bir case, ayrıştırılamayan bir "düzeltme"
            // ile supersede edildi ve isteğin koşulabilir hâli 8/11'den 0/0'a düştü.
            // Supersede yalnızca yeni versiyon doğrulamayı geçtiyse yapılır.
            if (healed.getValidationStatus() == ValidationStatus.INVALID) {
                log.warn("⛔ SELF-HEALING v{} doğrulamayı geçemedi, orijinal korunuyor — {}: {}",
                        nextVersion, failedCase.getTestName(), firstLine(healed.getValidationError()));
                // Deneme sayısı yine de artar; aksi hâlde aynı başarısız onarım sonsuz tekrarlanır
                failedCase.setHealAttempts(nextVersion);
                testCaseRepository.save(failedCase);
                return null;
            }

            // Orijinal case'i supersede edilmiş olarak işaretle + heal count artır
            failedCase.setSuperseded(true);
            failedCase.setHealAttempts(nextVersion);
            testCaseRepository.save(failedCase);

            // Ders çıkar: aynı servise sonraki üretimlerde bu hata varsayımı tekrarlanmasın
            agentLearningService.recordSelfHeal(request, failedCase, nextVersion);

            log.info("✅ SELF-HEALING BAŞARILI v{}: {} (doğrulamadan geçti, orijinalin üzerine yazılacak)",
                    nextVersion, failedCase.getTestName());
            return healed;

        } catch (Exception e) {
            log.error("Self-healing başarısız — test: {}", failedCase.getTestName(), e);
            return null;
        }
    }

    private String buildAnalysisPrompt(GeneratedTestCase failedCase, TestGenerationRequest request) {
        String framework  = failedCase.getFramework().name();
        String errorOutput = failedCase.getRunOutput() != null
                ? failedCase.getRunOutput().length() > 800
                    ? failedCase.getRunOutput().substring(0, 800) + "…[kısaltıldı]"
                    : failedCase.getRunOutput()
                : "(hata çıktısı yok)";

        return """
                Aşağıdaki %s test case başarısız oldu. Analiz et ve düzeltilmiş, daha sağlam bir versiyon yaz.

                ## Başarısız Test: %s (Deneme: %d)

                ## Mevcut İçerik
                ```
                %s
                ```

                ## Hata Çıktısı
                ```
                %s
                ```

                ## Orijinal Bağlam
                - Test Tipi  : %s
                - Swagger URL: %s
                - User Story : %s

                ## Görev
                1. Hatanın kök nedenini belirle
                2. Assertion'ları API kontratına göre düzelt
                3. Edge case'leri genişlet (404, 400, idempotency vb.)
                4. Her senaryo bağımsız çalışabilir olsun
                5. SELENIUM ise: driver'ı YALNIZCA `DriverFactory.createDriver()` ile oluştur
                   (DriverFactory projede hazır — tanımlama; ChromeDriver/RemoteWebDriver'ı doğrudan kurma),
                   @AfterEach içinde driver.quit() çağır
                6. Yalnızca düzeltilmiş %s test kodunu döndür — açıklama ekleme

                Düzeltilmiş test:
                """.formatted(
                        framework,
                        failedCase.getTestName(), failedCase.getHealAttempts() + 1,
                        failedCase.getTestContent(),
                        errorOutput,
                        request.getTestType().name(),
                        request.getSwaggerUrl() != null ? request.getSwaggerUrl() : "(yok)",
                        request.getUserStory()  != null ? request.getUserStory()  : "(yok)",
                        framework
                );
    }

    private static String firstLine(String text) {
        if (text == null) return "(mesaj yok)";
        int nl = text.indexOf('\n');
        return nl > 0 ? text.substring(0, nl) : text;
    }
}
