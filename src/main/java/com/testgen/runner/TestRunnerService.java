package com.testgen.runner;

import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestRunStatus;
import com.testgen.report.ReportOrchestrator;
import com.testgen.repository.GeneratedTestCaseRepository;
import com.testgen.repository.TestGenerationRequestRepository;
import com.testgen.scheduler.FailureAnalysisService;
import com.testgen.service.MockDataGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
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
    private final MockDataGenerationService        mockDataGenerationService;
    private final FailureAnalysisService          failureAnalysisService;

    @Value("${test-generator.runner.timeout-seconds}")
    private int timeoutSeconds;

    // ─────────────────────────────────────────────────────────
    // Tek test case çalıştır
    // ─────────────────────────────────────────────────────────
    @Async
    public CompletableFuture<GeneratedTestCase> runTest(String testCaseId) {
        var tc = testCaseRepository.findById(testCaseId)
                .orElseThrow(() -> new IllegalArgumentException("Test case bulunamadı: " + testCaseId));

        log.info("Test çalıştırılıyor: {} ({})", tc.getTestName(), tc.getFramework());
        prepareKarateMock(tc);

        tc.setRunStatus(TestRunStatus.RUNNING);
        testCaseRepository.save(tc);

        TestRunResult result = runSingle(tc);
        applyResult(tc, result);
        testCaseRepository.save(tc);
        log.info("Test tamamlandı: {} — {} ({}/{})",
                tc.getTestName(), tc.getRunStatus(), tc.getPassedScenarios(), tc.getTotalScenarios());

        TestGenerationRequest request = tc.getRequest();
        if (request != null) {
            reportOrchestrator.generateAndSend(request, testCaseRepository.findByRequestId(request.getId()));
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

        List<GeneratedTestCase> cases = testCaseRepository.findByRequestId(requestId);
        log.info("{} test case paralel koşturuluyor — requestId: {}", cases.size(), requestId);

        // Her case için async iş başlat, hepsini bekle
        List<CompletableFuture<Void>> futures = cases.stream().map(tc ->
                CompletableFuture.runAsync(() -> {
                    prepareKarateMock(tc);
                    tc.setRunStatus(TestRunStatus.RUNNING);
                    testCaseRepository.save(tc);

                    TestRunResult result = runSingle(tc);
                    applyResult(tc, result);
                    testCaseRepository.save(tc);
                    log.info("  ✓ {} — {} ({}/{})",
                            tc.getTestName(), tc.getRunStatus(),
                            tc.getPassedScenarios(), tc.getTotalScenarios());
                })
        ).toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<GeneratedTestCase> updated = testCaseRepository.findByRequestId(requestId);

        if (request.isAutoGenerateOnFailure()) {
            List<GeneratedTestCase> failedCases = updated.stream()
                    .filter(tc -> tc.getRunStatus() == TestRunStatus.FAILED)
                    .toList();

            if (!failedCases.isEmpty()) {
                log.info("Manuel koşum sonrası {} başarısız test bulundu. Self-healing tetikleniyor...", failedCases.size());
                List<GeneratedTestCase> newCases = failureAnalysisService.analyzeAndGenerateNew(failedCases, request);

                if (!newCases.isEmpty()) {
                    newCases.forEach(tc -> tc.setRequest(request));
                    testCaseRepository.saveAll(newCases);
                    log.info("{} yeni self-heal test üretildi. Koşuluyor...", newCases.size());

                    List<CompletableFuture<Void>> healFutures = newCases.stream().map(tc ->
                            CompletableFuture.runAsync(() -> {
                                prepareKarateMock(tc);
                                tc.setRunStatus(TestRunStatus.RUNNING);
                                testCaseRepository.save(tc);

                                TestRunResult result = runSingle(tc);
                                applyResult(tc, result);
                                testCaseRepository.save(tc);
                                log.info("  🔧 SELF-HEAL {} — {} ({}/{})",
                                        tc.getTestName(), tc.getRunStatus(),
                                        tc.getPassedScenarios(), tc.getTotalScenarios());
                            })
                    ).toList();

                    CompletableFuture.allOf(healFutures.toArray(new CompletableFuture[0])).join();
                    updated = testCaseRepository.findByRequestId(requestId);
                }
            }
        }

        reportOrchestrator.generateAndSend(request, updated, emailRecipients);
        return CompletableFuture.completedFuture(null);
    }

    // ─────────────────────────────────────────────────────────
    // Yardımcı — tek case koşumu
    // ─────────────────────────────────────────────────────────
    private TestRunResult runSingle(GeneratedTestCase tc) {
        try {
            return switch (tc.getFramework()) {
                case KARATE   -> karateRunner.run(tc);
                case SELENIUM -> runMavenTest(tc);
                case APPIUM   -> runMavenTest(tc);
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
    }

    private void prepareKarateMock(GeneratedTestCase tc) {
        if (tc.getFramework() == TestFramework.KARATE) {
            mockDataGenerationService.generateMockDataForTestCase(tc);
            tc.setTestContent(mockDataGenerationService.redirectKarateUrl(tc.getTestContent()));
            testCaseRepository.save(tc);
        }
    }

    // ─────────────────────────────────────────────────────────
    // Maven subprocess — PATH'de mvn yoksa wrapper veya full path
    // ─────────────────────────────────────────────────────────
    private TestRunResult runMavenTest(GeneratedTestCase tc) {
        long start = System.currentTimeMillis();
        try {
            Path projectDir = javaTestProjectService.ensureProject(tc.getFramework());
            javaTestProjectService.writeTestSource(tc.getFramework(), tc.getFileName(), tc.getTestContent());

            String mvnCmd = resolveMaven(projectDir);
            ProcessBuilder pb = new ProcessBuilder(
                    mvnCmd, "-B", "-ntp", "test",
                    "-Dtest=" + tc.getTestName(),
                    "-DfailIfNoTests=false"
            );
            pb.directory(projectDir.toFile());
            pb.redirectErrorStream(true);
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
            String outStr = output.toString();
            int total = parseTestCount(outStr);
            return TestRunResult.ofMaven(success, outStr, total, durationMs);

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - start;
            return TestRunResult.ofMaven(false, "Maven çalıştırılamadı: " + e.getMessage(), 0, durationMs);
        }
    }

    /** mvn komutunu çözer: wrapper → PATH → bilinen konumlar sırasıyla. */
    private String resolveMaven(Path projectDir) {
        // 1. wrapper (mvnw)
        Path wrapper = projectDir.resolve("mvnw");
        if (Files.exists(wrapper)) {
            wrapper.toFile().setExecutable(true);
            return wrapper.toAbsolutePath().toString();
        }
        // 2. bilinen kurulum konumları
        for (String candidate : List.of(
                "/usr/local/bin/mvn",
                "/usr/bin/mvn",
                "/opt/homebrew/bin/mvn",
                System.getProperty("user.home") + "/.sdkman/candidates/maven/current/bin/mvn"
        )) {
            if (Files.exists(Path.of(candidate))) return candidate;
        }
        // 3. PATH'de varsay
        return "mvn";
    }

    private int parseTestCount(String output) {
        Matcher m = Pattern.compile("Tests run: (\\d+)").matcher(output);
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }
}
