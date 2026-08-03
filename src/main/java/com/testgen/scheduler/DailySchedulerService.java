package com.testgen.scheduler;

import com.testgen.model.*;
import com.testgen.report.ReportOrchestrator;
import com.testgen.repository.GeneratedTestCaseRepository;
import com.testgen.repository.TestGenerationRequestRepository;
import com.testgen.runner.GeneratedJavaTestProjectService;
import com.testgen.runner.KarateRunner;
import com.testgen.runner.TestRunResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Günlük otomatik koşum + akıllı yeni test üretimi servisi.
 *
 * Akış (her gün 02:00'de):
 *
 *  Tüm scheduledRun=true request'ler için:
 *    1. Mevcut test case'leri koştur
 *    2. Başarısızları LLM'e gönder → analiz + yeni test üret
 *    3. Yeni üretilen testleri DB'ye kaydet ve koştur
 *    4. Allure raporu üret + email gönder
 *
 * Manuel tetikleme için POST /api/v1/scheduler/trigger-now endpoint'i var.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailySchedulerService {

    private final TestGenerationRequestRepository requestRepository;
    private final GeneratedTestCaseRepository     testCaseRepository;
    private final KarateRunner                    karateRunner;
    private final GeneratedJavaTestProjectService javaTestProjectService;
    private final FailureAnalysisService          failureAnalysisService;
    private final ReportOrchestrator              reportOrchestrator;

    // ─────────────────────────────────────────────────────────
    // Günlük koşum — her gün saat 02:00
    // cron = "saniye dakika saat gün ay haftanın-günü"
    // ─────────────────────────────────────────────────────────
    @Scheduled(cron = "${scheduler.daily-run.cron:0 0 2 * * *}")
    public void dailyRun() {
        log.info("===== GÜNLÜK SCHEDULER BAŞLADI: {} =====", LocalDateTime.now());

        List<TestGenerationRequest> scheduledRequests =
                requestRepository.findAllScheduled();

        if (scheduledRequests.isEmpty()) {
            log.info("Schedule'da aktif request yok, atlanıyor.");
            return;
        }

        log.info("{} adet request schedule'da bulundu.", scheduledRequests.size());

        for (TestGenerationRequest request : scheduledRequests) {
            try {
                runOneRequest(request);
            } catch (Exception e) {
                log.error("Request koşumu başarısız - requestId: {}", request.getId(), e);
            }
        }

        log.info("===== GÜNLÜK SCHEDULER TAMAMLANDI =====");
    }

    // ─────────────────────────────────────────────────────────
    // Manuel tetikleme (test/demo için)
    // ─────────────────────────────────────────────────────────
    public SchedulerRunSummary triggerManually(String requestId) {
        var request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Request bulunamadı: " + requestId));

        log.info("Manuel scheduler tetiklendi - requestId: {}", requestId);
        return runOneRequest(request);
    }

    // ─────────────────────────────────────────────────────────
    // Tek request için tam akış
    // ─────────────────────────────────────────────────────────
    @Transactional
    public SchedulerRunSummary runOneRequest(TestGenerationRequest request) {
        String requestId = request.getId();
        log.info("--- Koşum başlıyor: {} ---", requestId);

        // ── ADIM 1: Mevcut test case'leri koştur ──────────────
        List<GeneratedTestCase> existingCases =
                testCaseRepository.findByRequestId(requestId);

        log.info("{} mevcut test case koşturuluyor...", existingCases.size());
        int passed = 0, failed = 0;

        for (GeneratedTestCase tc : existingCases) {
            TestRunResult result = runSingleTestCase(tc);
            updateTestCase(tc, result);
            if (result.passed()) passed++; else failed++;
        }

        log.info("Koşum tamamlandı — geçti: {}, başarısız: {}", passed, failed);

        // ── ADIM 2: Başarısız testleri LLM ile analiz et, yeni üret ──
        List<GeneratedTestCase> newCases = List.of();

        if (failed > 0 && request.isAutoGenerateOnFailure()) {
            log.info("{} başarısız test için LLM analizi başlıyor...", failed);

            List<GeneratedTestCase> failedCases =
                    testCaseRepository.findFailedByRequestId(requestId);

            newCases = failureAnalysisService.analyzeAndGenerateNew(failedCases, request);

            if (!newCases.isEmpty()) {
                // Yeni case'leri request'e bağla ve kaydet
                final TestGenerationRequest finalRequest = request;
                newCases.forEach(tc -> tc.setRequest(finalRequest));
                testCaseRepository.saveAll(newCases);

                log.info("{} yeni test case üretildi ve kaydedildi.", newCases.size());

                // Yeni üretilen testleri de hemen koştur
                for (GeneratedTestCase tc : newCases) {
                    log.info("🔧 SELF-HEAL RUN: Düzeltilen test koşturuluyor: {}", tc.getTestName());
                    TestRunResult result = runSingleTestCase(tc);
                    updateTestCase(tc, result);
                    log.info("🔧 SELF-HEAL RUN SONUCU: {} - {}", tc.getTestName(), tc.getRunStatus());
                }
            }
        }

        // ── ADIM 3: Request istatistiklerini güncelle ─────────
        request.setLastScheduledRunAt(LocalDateTime.now());
        request.setScheduledRunCount(request.getScheduledRunCount() + 1);
        request.setTotalFailureCount(request.getTotalFailureCount() + failed);
        requestRepository.save(request);

        // ── ADIM 4: Allure raporu üret + email gönder ─────────
        List<GeneratedTestCase> allCases =
                testCaseRepository.findByRequestId(requestId);
        reportOrchestrator.generateAndSend(request, allCases);

        SchedulerRunSummary summary = new SchedulerRunSummary(
                requestId,
                existingCases.size(),
                passed,
                failed,
                newCases.size(),
                LocalDateTime.now()
        );

        log.info("--- Koşum özeti: {} ---", summary);
        return summary;
    }

    // ─────────────────────────────────────────────────────────
    // Tek test case'i çalıştır (framework'e göre doğru runner)
    // ─────────────────────────────────────────────────────────
    private TestRunResult runSingleTestCase(GeneratedTestCase tc) {
        tc.setRunStatus(TestRunStatus.RUNNING);
        testCaseRepository.save(tc);

        try {
            return switch (tc.getFramework()) {
                case KARATE   -> karateRunner.run(tc);
                case SELENIUM, REST_ASSURED -> runMavenTest(tc, tc.getFramework().name().toLowerCase());
            };
        } catch (Exception e) {
            log.error("Test koşumu hata verdi: {}", tc.getTestName(), e);
            return TestRunResult.ofMaven(false, "Runner hatası: " + e.getMessage(), 0, 0);
        }
    }

    private void updateTestCase(GeneratedTestCase tc, TestRunResult result) {
        tc.setRunStatus(result.passed() ? TestRunStatus.PASSED : TestRunStatus.FAILED);
        tc.setRunOutput(result.output());
        tc.setTotalScenarios(result.total());
        tc.setPassedScenarios(result.passed() ? result.total() : 0);
        tc.setFailedScenarios(result.passed() ? 0 : result.total());
        tc.setLastRunAt(LocalDateTime.now());
        testCaseRepository.save(tc);
    }

    private TestRunResult runMavenTest(GeneratedTestCase tc, String profile) {
        try {
            var projectDir = javaTestProjectService.ensureProject(tc.getFramework());
            javaTestProjectService.writeTestSource(tc.getFramework(), tc.getFileName(), tc.getTestContent());

            ProcessBuilder pb = new ProcessBuilder(
                    "mvn", "-B", "-ntp", "test",
                    "-Dtest=" + tc.getTestName(),
                    "-DfailIfNoTests=false"
            );
            pb.directory(projectDir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder out = new StringBuilder();
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                reader.lines().forEach(l -> out.append(l).append("\n"));
            }

            boolean finished = process.waitFor(300, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return TestRunResult.ofMaven(false, "Maven zaman asimina ugradi\n" + out, 0, 0);
            }

            boolean ok = process.exitValue() == 0;
            int count = parseTestCount(out.toString());
            return TestRunResult.ofMaven(ok, out.toString(), count, 0);

        } catch (Exception e) {
            return TestRunResult.ofMaven(false, "Maven hatası: " + e.getMessage(), 0, 0);
        }
    }

    private int parseTestCount(String output) {
        var m = java.util.regex.Pattern.compile("Tests run: (\\d+)").matcher(output);
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }
}
