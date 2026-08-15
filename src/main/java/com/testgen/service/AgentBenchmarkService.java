package com.testgen.service;

import com.testgen.config.BadRequestException;
import com.testgen.model.*;
import com.testgen.repository.AgentBenchmarkRunRepository;
import com.testgen.repository.GeneratedTestCaseRepository;
import com.testgen.repository.LlmCallLogRepository;
import com.testgen.runner.TestRunnerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Ajan ölçüm koşumu — "çok-ajanlı analiz işe yarıyor mu?" sorusunu ÖLÇEREK yanıtlar.
 *
 * Aynı girdi iki kolda üretilir; tek değişken ajan adımıdır:
 *   WITH_AGENTS     → mevcut varsayılan davranış
 *   WITHOUT_AGENTS  → kontrol kolu, ajan analizi atlanır
 *
 * Ölçülen büyüklükler:
 *   - üretim kalitesi : makine doğrulaması (Karate parse / Java derleme) sonuçları
 *   - maliyet         : üretim penceresindeki LLM çağrı sayısı, süresi ve prompt boyutu
 *   - (opsiyonel)     : testler koşulursa geçen/toplam senaryo
 *
 * Kollar SIRAYLA koşulur: tek bir yerel LLM sunucusu paylaşıldığı için paralel koşum
 * hem süreleri hem de LLM atfını bozardı.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentBenchmarkService {

    private final AgentBenchmarkRunRepository runRepository;
    private final com.testgen.repository.AgentBenchmarkResultRepository resultRepository;
    private final GeneratedTestCaseRepository testCaseRepository;
    private final LlmCallLogRepository llmCallLogRepository;
    private final TestGenerationService testGenerationService;
    private final TestRunnerService testRunnerService;

    @Transactional
    public AgentBenchmarkRun create(AgentBenchmarkRun request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new BadRequestException("Ölçüm koşumu adı zorunludur.");
        }
        if (request.getTestType() == null || request.getFramework() == null) {
            throw new BadRequestException("testType ve framework zorunludur.");
        }
        if (request.getRepetitions() < 1 || request.getRepetitions() > 5) {
            throw new BadRequestException("repetitions 1 ile 5 arasında olmalıdır.");
        }
        if (request.getComparison() == null) {
            request.setComparison(BenchmarkComparison.AGENTS_ON_OFF);
        }
        request.setStatus(BenchmarkStatus.PENDING);
        AgentBenchmarkRun saved = runRepository.save(request);
        log.info("Ajan ölçüm koşumu oluşturuldu: '{}' ({}) — {} tekrar × 2 kol",
                saved.getName(), saved.getId(), saved.getRepetitions());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<AgentBenchmarkRun> list() {
        return runRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public AgentBenchmarkRun get(String runId) {
        AgentBenchmarkRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Ölçüm koşumu bulunamadı: " + runId));
        run.getResults().size(); // lazy koleksiyon tx içinde açılır
        return run;
    }

    // ─────────────────────────────────────────────────────────
    // Koşum
    // ─────────────────────────────────────────────────────────

    @Async
    public void execute(String runId) {
        // get() yerine doğrudan okuma: execute aynı bean içinden çağrıldığı için
        // @Transactional proxy'si devreye girmez, lazy koleksiyona dokunulmamalı.
        AgentBenchmarkRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Ölçüm koşumu bulunamadı: " + runId));
        markStatus(runId, BenchmarkStatus.RUNNING, null, LocalDateTime.now(), null);

        try {
            for (int i = 1; i <= run.getRepetitions(); i++) {
                for (BenchmarkArm arm : run.getComparison().arms()) {
                    AgentBenchmarkResult result = measureOnce(run, arm, i);
                    persist(runId, result);
                    log.info("[benchmark] {} #{} → case={} valid={} invalid={} llm={} çağrı / {} ms",
                            arm, i, result.getCaseCount(), result.getValidCases(),
                            result.getInvalidCases(), result.getLlmCalls(), result.getLlmDurationMs());
                }
            }
            markStatus(runId, BenchmarkStatus.COMPLETED, null, null, LocalDateTime.now());
            log.info("Ajan ölçüm koşumu tamamlandı: {}", runId);

        } catch (Exception e) {
            log.error("Ajan ölçüm koşumu başarısız: {}", runId, e);
            markStatus(runId, BenchmarkStatus.FAILED, e.getMessage(), null, LocalDateTime.now());
        }
    }

    /** Tek bir üretim: istek oluştur → üret → doğrulama ve maliyet ölç. */
    private AgentBenchmarkResult measureOnce(AgentBenchmarkRun run, BenchmarkArm arm, int iteration) {
        TestGenerationRequest request = TestGenerationRequest.builder()
                .testType(run.getTestType())
                .framework(run.getFramework())
                .userStory(run.getUserStory())
                .additionalContext(run.getAdditionalContext())
                .swaggerUrl(run.getSwaggerUrl())
                .applicationUrl(run.getApplicationUrl())
                // Kollar arasındaki TEK fark burada kurulur
                .agentsEnabled(arm != BenchmarkArm.WITHOUT_AGENTS)
                .agentMode(modeOf(arm))
                .build();

        TestGenerationRequest saved = testGenerationService.createRequest(request);

        AgentBenchmarkResult.AgentBenchmarkResultBuilder result = AgentBenchmarkResult.builder()
                .arm(arm)
                .iteration(iteration)
                .requestId(saved.getId());

        LocalDateTime windowStart = LocalDateTime.now();
        long started = System.currentTimeMillis();
        String error = null;
        try {
            testGenerationService.generateTests(saved.getId()).join();
        } catch (Exception e) {
            // Üretim başarısız olabilir — bu da ölçümün bir sonucudur, koşumu durdurmaz
            error = rootMessage(e);
            log.warn("[benchmark] {} #{} üretimi başarısız: {}", arm, iteration, error);
        }
        long generationMs = System.currentTimeMillis() - started;
        LocalDateTime windowEnd = LocalDateTime.now();

        List<GeneratedTestCase> cases = testCaseRepository.findByRequestId(saved.getId());
        result.caseCount(cases.size())
                .validCases(count(cases, ValidationStatus.VALID))
                .invalidCases(count(cases, ValidationStatus.INVALID))
                .skippedCases(count(cases, ValidationStatus.SKIPPED))
                .validationRetries(cases.stream().mapToInt(GeneratedTestCase::getValidationAttempts).sum())
                .generationDurationMs(generationMs)
                .errorMessage(error);

        applyLlmCost(result, windowStart, windowEnd);

        if (run.isRunTests() && !cases.isEmpty()) {
            applyRunMetrics(result, saved.getId());
        }
        return result.build();
    }

    /**
     * LLM maliyetini üretim penceresine göre atfeder.
     * Çağrı kayıtlarında requestId yok; kollar sıralı koştuğu için pencere kesin sonuç verir.
     */
    private void applyLlmCost(AgentBenchmarkResult.AgentBenchmarkResultBuilder result,
                              LocalDateTime start, LocalDateTime end) {
        List<LlmCallLog> calls = llmCallLogRepository.findByCalledAtBetween(start, end);
        result.llmCalls(calls.size())
                .llmDurationMs(calls.stream().mapToLong(LlmCallLog::getDurationMs).sum())
                .llmPromptChars(calls.stream().mapToLong(LlmCallLog::getPromptChars).sum());
    }

    /** Testleri koşar ve senaryo sayılarını ölçüme ekler (yavaş; yalnızca runTests=true). */
    private void applyRunMetrics(AgentBenchmarkResult.AgentBenchmarkResultBuilder result, String requestId) {
        try {
            testRunnerService.runAllForRequest(requestId, List.of()).join();
        } catch (Exception e) {
            log.warn("[benchmark] koşum başarısız - requestId: {}: {}", requestId, rootMessage(e));
        }
        List<GeneratedTestCase> executed = testCaseRepository.findByRequestIdAndSupersededFalse(requestId);
        result.totalScenarios(executed.stream().mapToInt(c -> nullSafe(c.getTotalScenarios())).sum())
                .passedScenarios(executed.stream().mapToInt(c -> nullSafe(c.getPassedScenarios())).sum());
    }

    /**
     * Sonucu doğrudan kendi tablosuna yazar.
     *
     * Bilerek koleksiyon üzerinden değil: bu metot aynı bean içinden çağrıldığı için
     * @Transactional proxy'si devreye girmez; detached bir entity'nin LAZY koleksiyonuna
     * dokunmak LazyInitializationException üretirdi. Repository çağrısının kendi
     * transaction'ı yeterlidir.
     */
    private void persist(String runId, AgentBenchmarkResult result) {
        runRepository.findById(runId).ifPresent(run -> {
            result.setRun(run);
            resultRepository.save(result);
        });
    }

    /** Koşum durumunu kısa, kendi transaction'ında günceller (aynı gerekçe). */
    private void markStatus(String runId, BenchmarkStatus status, String error,
                            LocalDateTime startedAt, LocalDateTime finishedAt) {
        runRepository.findById(runId).ifPresent(run -> {
            run.setStatus(status);
            if (error != null) run.setErrorMessage(error);
            if (startedAt != null) run.setStartedAt(startedAt);
            if (finishedAt != null) run.setFinishedAt(finishedAt);
            runRepository.save(run);
        });
    }

    /** Kolun gerektirdiği ajan modu; on/off ekseninde mod belirtilmez (konfigürasyon geçerli). */
    private static com.testgen.agent.AgentRouting.Mode modeOf(BenchmarkArm arm) {
        return switch (arm) {
            case LEAN_AGENTS -> com.testgen.agent.AgentRouting.Mode.LEAN;
            case FULL_AGENTS -> com.testgen.agent.AgentRouting.Mode.FULL;
            case WITH_AGENTS, WITHOUT_AGENTS -> null;
        };
    }

    private static int count(List<GeneratedTestCase> cases, ValidationStatus status) {
        return (int) cases.stream().filter(c -> c.getValidationStatus() == status).count();
    }

    private static int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
