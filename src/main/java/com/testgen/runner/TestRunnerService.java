package com.testgen.runner;

import com.testgen.model.ExecutionTrigger;
import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestRunStatus;
import com.testgen.report.ReportOrchestrator;
import com.testgen.repository.GeneratedTestCaseRepository;
import com.testgen.repository.TestGenerationRequestRepository;
import com.testgen.scheduler.FailureAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Üretilen test case'leri çalıştıran servis.
 *
 * İyileştirmeler:
 *  - runAllForRequest: paralel CompletableFuture koşumu (framework başına grup)
 *  - passedScenarios / failedScenarios: TestRunResult'tan gerçek sayılar
 *  - Maven path: PATH'de yoksa mvnw wrapper veya /usr/local/bin/mvn fallback
 *  - runOutput: TestRunResult içinde 10KB'a kısıtlı (truncate)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TestRunnerService {

    private final GeneratedTestCaseRepository     testCaseRepository;
    private final TestGenerationRequestRepository requestRepository;
    private final KarateRunner                    karateRunner;
    private final ReportOrchestrator              reportOrchestrator;
    private final GeneratedJavaTestProjectService javaTestProjectService;
    private final FailureAnalysisService          failureAnalysisService;
    private final com.testgen.service.AgentLearningService agentLearningService;
    private final com.testgen.service.TestSuiteService testSuiteService;
    private final com.testgen.service.TestExecutionService testExecutionService;
    private final com.testgen.service.TestPlanService testPlanService;
    private final com.testgen.metrics.TestGenMetrics metrics;

    @Value("${test-generator.runner.timeout-seconds}")
    private int timeoutSeconds;

    @Value("${test-generator.selenium.remote-url:}")
    private String seleniumRemoteUrl;

    @Value("${test-generator.selenium.headless:true}")
    private boolean seleniumHeadless;

    // ─────────────────────────────────────────────────────────
    // Suite koşumu: kullanıcı tanımlı paketteki tüm case'ler
    // ─────────────────────────────────────────────────────────
    @Async
    public CompletableFuture<Void> runSuite(String suiteId) {
        var suite = testSuiteService.get(suiteId);
        // Supersede edilmiş (self-healing ile yenisi üretilmiş) case'ler koşulmaz —
        // aksi halde eski bozuk versiyon yeni versiyonla aynı dosyaya yazıp sonucu bozar.
        List<GeneratedTestCase> cases = suite.getTestCases().stream()
                .filter(tc -> !tc.isSuperseded())
                .toList();

        var execution = testExecutionService.open(
                "Suite koşumu — " + suite.getName(), ExecutionTrigger.SUITE,
                null, null, suite.getId(), suite.getName(), null, cases.size());

        executeAndRecord(execution.getId(), cases, "suite");
        return CompletableFuture.completedFuture(null);
    }

    // ─────────────────────────────────────────────────────────
    // Test Plan koşumu: plandaki tüm suite'lerin case'leri
    // ─────────────────────────────────────────────────────────
    @Async
    public CompletableFuture<Void> runPlan(String planId) {
        var plan = testPlanService.get(planId);
        List<GeneratedTestCase> cases = testPlanService.resolveCases(planId);

        var execution = testExecutionService.open(
                "Plan koşumu — " + plan.getName(), ExecutionTrigger.PLAN,
                plan.getId(), plan.getName(), null, null, null, cases.size());

        executeAndRecord(execution.getId(), cases, "plan");
        return CompletableFuture.completedFuture(null);
    }

    // ─────────────────────────────────────────────────────────
    // Geçmiş bir koşumun aynı kapsamla tekrarı
    // ─────────────────────────────────────────────────────────
    @Async
    public CompletableFuture<Void> rerunExecution(String sourceExecutionId) {
        var source = testExecutionService.get(sourceExecutionId);
        List<GeneratedTestCase> cases = testExecutionService.resolveCasesForRerun(sourceExecutionId);

        var execution = testExecutionService.open(
                "Yeniden koşum — " + source.getName(), ExecutionTrigger.RERUN,
                source.getPlanId(), source.getPlanName(),
                source.getSuiteId(), source.getSuiteName(),
                sourceExecutionId, cases.size());

        executeAndRecord(execution.getId(), cases, "rerun");
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Koşum kaydını yürüten ortak akış: case'leri koşar, her sonucu koşum kaydına
     * anlık görüntü olarak yazar, özetleri günceller ve raporu üretip gönderir.
     */
    private void executeAndRecord(String executionId, List<GeneratedTestCase> cases, String label) {
        log.info("[{}] koşum başlıyor — {} case (executionId: {})", label, cases.size(), executionId);
        testExecutionService.markRunning(executionId);

        if (cases.isEmpty()) {
            testExecutionService.abort(executionId, "Kapsamda koşulabilir test case yok.");
            return;
        }

        // Java framework'lerinin proje dizinlerini temizle (bayat dosya koşulmasın)
        cases.stream().map(GeneratedTestCase::getFramework).distinct()
                .forEach(javaTestProjectService::cleanTestFiles);

        runCases(cases, label);

        List<GeneratedTestCase> executed = new ArrayList<>();
        for (GeneratedTestCase tc : cases) {
            GeneratedTestCase fresh = testCaseRepository.findById(tc.getId()).orElse(tc);
            executed.add(fresh);
            testExecutionService.recordResult(executionId, fresh);
        }

        var closed = testExecutionService.close(executionId);

        if (closed.getSuiteId() != null) {
            testSuiteService.recordRunSummary(closed.getSuiteId(),
                    closed.getPassedCases(), closed.getFailedCases());
        }
        if (closed.getPlanId() != null) {
            testPlanService.recordExecutionSummary(closed.getPlanId(), closed.getStatus(),
                    closed.getPassedCases(), closed.getFailedCases());
        }

        // Koşum bazlı rapor + e-posta (case'ler farklı üretim isteklerinden gelebilir)
        try {
            reportOrchestrator.generateAndSend(executionId, null, executed, null);
        } catch (Exception e) {
            log.error("Koşum raporu üretilemedi - executionId: {}", executionId, e);
        }
    }

    // ─────────────────────────────────────────────────────────
    // Tek test case çalıştır
    // ─────────────────────────────────────────────────────────
    @Async
    public CompletableFuture<GeneratedTestCase> runTest(String testCaseId) {
        var tc = testCaseRepository.findById(testCaseId)
                .orElseThrow(() -> new IllegalArgumentException("Test case bulunamadı: " + testCaseId));

        log.info("Test çalıştırılıyor: {} ({})", tc.getTestName(), tc.getFramework());

        tc.setRunStatus(TestRunStatus.RUNNING);
        testCaseRepository.save(tc);

        TestRunResult result = runSingle(tc);
        applyResult(tc, result);
        testCaseRepository.save(tc);
        log.info("Test tamamlandı: {} — {} ({}/{})",
                tc.getTestName(), tc.getRunStatus(), tc.getPassedScenarios(), tc.getTotalScenarios());

        // tc.getRequest() LAZY proxy döner; open-in-view kapalı olduğu için alanlarına
        // erişim LazyInitializationException fırlatır ve rapor/e-posta adımı SESSİZCE
        // ölürdü (@Async future içinde yutuluyordu). Satırı yeniden okuyup raporluyoruz.
        String requestId = tc.getRequest() != null ? tc.getRequest().getId() : null;
        if (requestId != null) {
            try {
                requestRepository.findById(requestId).ifPresent(request ->
                        reportOrchestrator.generateAndSend(request,
                                testCaseRepository.findByRequestIdAndSupersededFalse(requestId)));
            } catch (Exception e) {
                log.error("Rapor/e-posta adımı başarısız - requestId: {}", requestId, e);
            }
        }
        return CompletableFuture.completedFuture(tc);
    }

    // ─────────────────────────────────────────────────────────
    // TÜM case'leri paralel koştur + raporla
    // ─────────────────────────────────────────────────────────
    @Async
    public CompletableFuture<Void> runAllForRequest(String requestId, List<String> emailRecipients) {
        var request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request bulunamadı: " + requestId));

        List<GeneratedTestCase> cases = testCaseRepository.findByRequestIdAndSupersededFalse(requestId);
        log.info("{} test case koşturuluyor — requestId: {} (supersede edilenler hariç)",
                cases.size(), requestId);

        // Eski test dosyalarını temizle (mvn compilation hatalarını önlemek için)
        javaTestProjectService.cleanTestFiles(request.getFramework());

        runCases(cases, "run-all");

        List<GeneratedTestCase> updated = testCaseRepository.findByRequestIdAndSupersededFalse(requestId);

        // Self-healing OTOMATİK tetiklenmez (bkz. TestGenerationRequest.autoGenerateOnFailure).
        // Kullanıcı hatayı gördükten sonra POST /api/v1/tests/{id}/self-heal ile başlatır.
        if (request.isAutoGenerateOnFailure()) {
            long failedCount = updated.stream()
                    .filter(tc -> tc.getRunStatus() == TestRunStatus.FAILED).count();
            if (failedCount > 0) {
                log.info("Otomatik self-healing bu request için açık — {} başarısız test iyileştirilecek.", failedCount);
                if (healFailedCases(request) > 0) {
                    updated = testCaseRepository.findByRequestIdAndSupersededFalse(requestId);
                }
            }
        } else {
            long failedCount = updated.stream()
                    .filter(tc -> tc.getRunStatus() == TestRunStatus.FAILED).count();
            if (failedCount > 0) {
                log.info("{} test başarısız. Self-healing otomatik ÇALIŞMAZ — başlatmak için: "
                        + "POST /api/v1/tests/{}/self-heal", failedCount, requestId);
            }
        }

        reportOrchestrator.generateAndSend(request, updated, emailRecipients);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Kullanıcı isteğiyle self-healing: başarısız case'leri analiz edip iyileştirilmiş
     * sürümlerini üretir ve koşar. {@code autoGenerateOnFailure} bayrağına BAKMAZ —
     * bu çağrının kendisi zaten kullanıcının açık talebidir.
     *
     * @return üretilip koşulan iyileştirilmiş case sayısı
     */
    @Async
    public CompletableFuture<Integer> selfHealForRequest(String requestId) {
        TestGenerationRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request bulunamadı: " + requestId));
        int healed = healFailedCases(request);
        if (healed > 0) {
            List<GeneratedTestCase> updated = testCaseRepository.findByRequestIdAndSupersededFalse(requestId);
            reportOrchestrator.generateAndSend(request, updated, null);
        }
        return CompletableFuture.completedFuture(healed);
    }

    /**
     * Ortak iyileştirme adımı — hem otomatik hem elle tetiklenen yol bunu kullanır.
     *
     * <p>Onarım turundaki LLM çağrıları da isteğe bağlanır; aksi hâlde çağrı geçmişinde
     * FAILURE_ANALYSIS satırları sahipsiz görünüyordu.
     */
    private int healFailedCases(TestGenerationRequest request) {
        com.testgen.llm.LlmCallContext.set(request.getId(),
                com.testgen.llm.LlmCallContext.Phase.SELF_HEAL);
        try {
            return healFailedCasesInternal(request);
        } finally {
            com.testgen.llm.LlmCallContext.clear();
        }
    }

    private int healFailedCasesInternal(TestGenerationRequest request) {
        List<GeneratedTestCase> failedCases = testCaseRepository
                .findByRequestIdAndSupersededFalse(request.getId()).stream()
                .filter(tc -> tc.getRunStatus() == TestRunStatus.FAILED)
                .toList();

        if (failedCases.isEmpty()) {
            log.info("İyileştirilecek başarısız test yok — requestId: {}", request.getId());
            return 0;
        }

        List<GeneratedTestCase> newCases = failureAnalysisService.analyzeAndGenerateNew(failedCases, request);
        if (newCases.isEmpty()) {
            return 0;
        }

        newCases.forEach(tc -> tc.setRequest(request));
        testCaseRepository.saveAll(newCases);
        log.info("{} yeni self-heal test üretildi. Koşuluyor...", newCases.size());

        // Suite üyeliği: eski FAILED case yerine heal edilen versiyon geçsin
        newCases.forEach(tc -> testSuiteService.replaceCaseInSuites(tc.getParentCaseId(), tc));

        // Eski test dosyalarını temizle (iyileştirilmiş versiyonlar koşulmadan önce)
        javaTestProjectService.cleanTestFiles(request.getFramework());

        runCases(newCases, "SELF-HEAL");
        return newCases.size();
    }

    /**
     * Case listesini framework'e göre koşturur.
     *
     * Karate in-process çalıştığı için paralel koşulur. SELENIUM/REST_ASSURED ise
     * framework başına TEK bir Maven projesi paylaşır (aynı src/test/java ve target
     * dizini); paralel `mvn test` çağrıları birbirinin sınıflarını ve target'ını
     * ezdiği için bu case'ler sırayla koşulur.
     */
    private void runCases(List<GeneratedTestCase> cases, String label) {
        List<GeneratedTestCase> parallelCases = cases.stream()
                .filter(tc -> tc.getFramework() == TestFramework.KARATE)
                .toList();
        List<GeneratedTestCase> sequentialCases = cases.stream()
                .filter(tc -> tc.getFramework() != TestFramework.KARATE)
                .toList();

        List<CompletableFuture<Void>> futures = parallelCases.stream()
                .map(tc -> CompletableFuture.runAsync(() -> runAndRecord(tc, label)))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        for (GeneratedTestCase tc : sequentialCases) {
            runAndRecord(tc, label);
        }
    }

    private void runAndRecord(GeneratedTestCase tc, String label) {
        tc.setRunStatus(TestRunStatus.RUNNING);
        testCaseRepository.save(tc);

        TestRunResult result = runSingle(tc);
        applyResult(tc, result);
        testCaseRepository.save(tc);
        log.info("  [{}] {} — {} ({}/{})", label, tc.getTestName(), tc.getRunStatus(),
                tc.getPassedScenarios(), tc.getTotalScenarios());
    }

    // ─────────────────────────────────────────────────────────
    // Yardımcı — tek case koşumu
    // ─────────────────────────────────────────────────────────
    private TestRunResult runSingle(GeneratedTestCase tc) {
        try {
            return switch (tc.getFramework()) {
                case KARATE   -> karateRunner.run(tc);
                case SELENIUM, REST_ASSURED -> runMavenTest(tc);
            };
        } catch (Exception e) {
            log.error("Runner hatası: {}", tc.getTestName(), e);
            return TestRunResult.of(false, "Runner hatası: " + e.getMessage(), 0, 0, 0, 0);
        }
    }

    /** TestRunResult'tan gerçek passed/failed sayılarını entity'ye yaz. */
    private void applyResult(GeneratedTestCase tc, TestRunResult result) {
        tc.setRunStatus(result.passed() ? TestRunStatus.PASSED : TestRunStatus.FAILED);
        tc.setRunOutput(result.output());          // zaten 10KB ile kısıtlı
        tc.setTotalScenarios(result.total());
        tc.setPassedScenarios(result.passedCount());
        tc.setFailedScenarios(result.failedCount());
        tc.setExecutionTimeMs(result.durationMs());
        tc.setLastRunAt(LocalDateTime.now());
        // Kategori ve kaynak etiketiyle: hangi test sınıfının, hangi üretim yolundan
        // geldiğinde geçtiği ancak böyle ayrıştırılabiliyor.
        metrics.recordTestRun(tc.getFramework(), tc.getRunStatus(), result.durationMs(),
                result.passedCount(), result.failedCount(),
                tc.getTestCategory(), tc.isDeterministic());

        // Başarısızlıklar servis bazlı öğrenim deposuna düşer (halüsinasyon azaltma)
        if (!result.passed() && tc.getRequest() != null) {
            agentLearningService.recordRunFailure(tc.getRequest(), tc);
        }
    }

    // ─────────────────────────────────────────────────────────
    // Maven subprocess — PATH'de mvn yoksa wrapper veya full path
    // ─────────────────────────────────────────────────────────
    private TestRunResult runMavenTest(GeneratedTestCase tc) {
        long start = System.currentTimeMillis();
        Path projectDir = null;
        try {
            // Ortak projeye de yaz (gözden geçirilebilir "son üretim" kopyası)
            javaTestProjectService.writeTestSource(tc.getFramework(), tc.getFileName(), tc.getTestContent());
            // Koşum İZOLE projede yapılır: LLM'in ürettiği başka bir bozuk sınıf
            // bu case'in derlenmesini engellemesin.
            String runKey = tc.getId() != null ? tc.getId() : tc.getTestName();
            projectDir = javaTestProjectService.prepareIsolatedRun(
                    tc.getFramework(), runKey, tc.getFileName(), tc.getTestContent());

            String mvnCmd = javaTestProjectService.resolveMavenCommand(projectDir);
            ProcessBuilder pb = new ProcessBuilder(
                    mvnCmd, "-B", "-ntp", "test",
                    "-Dtest=" + tc.getTestName(),
                    "-DfailIfNoTests=false"
            );
            pb.directory(projectDir.toFile());
            pb.redirectErrorStream(true);

            // Selenium driver hedefini konfigürasyondan subprocess'e taşı:
            // Grid (compose: selenium-hub) tanımlıysa RemoteWebDriver, değilse lokal headless Chrome
            if (seleniumRemoteUrl != null && !seleniumRemoteUrl.isBlank()) {
                pb.environment().put("SELENIUM_REMOTE_URL", seleniumRemoteUrl);
            }
            pb.environment().put("SELENIUM_HEADLESS", String.valueOf(seleniumHeadless));

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                reader.lines().forEach(line -> output.append(line).append("\n"));
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            long durationMs = System.currentTimeMillis() - start;
            if (!finished) {
                process.destroyForcibly();
                return TestRunResult.ofMaven(false,
                        "Maven zaman aşımı (" + timeoutSeconds + "s)\n" + output, 0, durationMs);
            }

            boolean success = process.exitValue() == 0;
            return TestRunResult.fromSurefireOutput(success, output.toString(), durationMs);

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - start;
            return TestRunResult.ofMaven(false, "Maven çalıştırılamadı: " + e.getMessage(), 0, durationMs);
        } finally {
            javaTestProjectService.cleanupIsolatedRun(projectDir);
        }
    }
}
