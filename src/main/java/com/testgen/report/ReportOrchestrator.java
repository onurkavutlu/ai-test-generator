package com.testgen.report;

import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestGenerationRequest;
import com.testgen.notification.EmailNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.nio.file.Path;

/**
 * Raporlama orkestratörü.
 *
 * Test koşusu bittikten sonra sırasıyla:
 *  1. Allure result JSON'larını yazar
 *  2. allure CLI ile HTML rapor üretir
 *  3. Özet DTO hazırlar
 *  4. Email bildirimini async olarak gönderir
 *
 * TestRunnerService bu sınıfı çağırır.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportOrchestrator {

    private final AllureReportService   allureReportService;
    private final CucumberReportService cucumberReportService;

    // Email servisi isteğe bağlı (conditional bean)
    @Autowired(required = false)
    private EmailNotificationService emailNotificationService;

    /**
     * @param request   orijinal üretim isteği
     * @param testCases çalıştırılmış test case listesi
     * @param recipients email alıcıları (null → config'deki default'lar)
     */
    public ReportResult generateAndSend(TestGenerationRequest request,
                                         List<GeneratedTestCase> testCases,
                                         List<String> recipients) {

        String requestId = request.getId();
        log.info("Rapor üretimi başlıyor - requestId: {}", requestId);

        // 1. Allure JSON result'larını yaz
        allureReportService.writeAllResults(testCases, requestId);

        // 2. Allure HTML rapor üret (allure CLI)
        AllureReportResult allureResult = allureReportService.generateHtmlReport(requestId);
        String reportUrl = allureResult.isSuccess() ? allureResult.getReportUrl() : null;

        // 2.5. Cucumber HTML rapor üret
        Path cucumberReportPath = cucumberReportService.generateReport(requestId, testCases);
        if (cucumberReportPath != null) {
            log.info("Cucumber raporu hazır: {}", cucumberReportPath);
        }

        // 3. Özet DTO
        TestReportSummary summary = TestReportSummary.from(requestId, request.getAdditionalContext(), testCases, reportUrl);

        log.info("Test özeti - total:{} passed:{} failed:{} passRate:{}",
                summary.getTotalTests(), summary.getPassedTests(),
                summary.getFailedTests(), summary.getPassRateFormatted());

        // 3.5. Konsola detaylı ASCII özet raporu yazdır
        printConsoleSummaryReport(summary);

        // 4. Email gönder (async – sonucu beklemez)
        if (emailNotificationService != null) {
            emailNotificationService.sendTestReport(summary, recipients);
            log.info("Email bildirimi kuyruğa alındı");
        } else {
            log.debug("Email servisi devre dışı (notification.email.enabled=false)");
        }

        return new ReportResult(summary, allureResult);
    }

    /**
     * Convenience – alıcı belirtmeden çağır (config'deki default'lar kullanılır).
     */
    public ReportResult generateAndSend(TestGenerationRequest request,
                                         List<GeneratedTestCase> testCases) {
        return generateAndSend(request, testCases, null);
    }

    // ── Value object ──────────────────────────────────────────
    public record ReportResult(TestReportSummary summary, AllureReportResult allureResult) {
        public boolean isSuccess() { return allureResult.isSuccess(); }
        public String  reportUrl() { return allureResult.getReportUrl(); }
    }

    private void printConsoleSummaryReport(TestReportSummary summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n================================================================================\n");
        sb.append("                           AI TEST RUNNER SUMMARY REPORT                        \n");
        sb.append("================================================================================\n");
        sb.append(String.format(" Request ID        : %s\n", summary.getRequestId()));
        sb.append(String.format(" Reported At       : %s\n", summary.getFormattedDate()));
        sb.append(String.format(" Overall Status    : %s %s (Pass Rate: %s)\n", 
                summary.getStatusEmoji(), summary.getOverallStatus(), summary.getPassRateFormatted()));
        if (summary.getAllureReportUrl() != null && !summary.getAllureReportUrl().isBlank()) {
            sb.append(String.format(" Allure Report URL : %s\n", summary.getAllureReportUrl()));
        }
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append(" STATS:\n");
        sb.append(String.format("   Total Tests     : %d\n", summary.getTotalTests()));
        sb.append(String.format("   Passed Tests    : %d\n", summary.getPassedTests()));
        sb.append(String.format("   Failed Tests    : %d\n", summary.getFailedTests()));
        sb.append(String.format("   Skipped Tests   : %d\n", summary.getSkippedTests()));
        sb.append(String.format("   Broken Tests    : %d\n", summary.getBrokenTests()));
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append(" EXECUTED TEST CASES:\n");
        sb.append(String.format("   %-12s | %-8s | %-30s | %s\n", "Framework", "Status", "Test Name", "Duration"));
        sb.append("--------------------------------------------------------------------------------\n");
        
        if (summary.getTestCases() != null) {
            for (var tc : summary.getTestCases()) {
                String statusIndicator = tc.getRunStatus() == com.testgen.model.TestRunStatus.PASSED ? "PASSED" : "FAILED";
                if (tc.getRunStatus() == com.testgen.model.TestRunStatus.NOT_RUN) {
                    statusIndicator = "NOT RUN";
                } else if (tc.getRunStatus() == com.testgen.model.TestRunStatus.SKIPPED) {
                    statusIndicator = "SKIPPED";
                }
                
                String duration = tc.getExecutionTimeMs() != null 
                        ? String.format("%.2f s", tc.getExecutionTimeMs() / 1000.0)
                        : "N/A";
                
                sb.append(String.format("   %-12s | %-8s | %-30s | %s\n", 
                        tc.getFramework(), 
                        statusIndicator, 
                        tc.getTestName(), 
                        duration));
            }
        }
        
        boolean hasFailures = summary.getTestCases() != null && summary.getTestCases().stream()
                .anyMatch(tc -> tc.getRunStatus() == com.testgen.model.TestRunStatus.FAILED);
                
        if (hasFailures) {
            sb.append("--------------------------------------------------------------------------------\n");
            sb.append(" FAILED TEST DETAILS:\n");
            sb.append("--------------------------------------------------------------------------------\n");
            for (var tc : summary.getTestCases()) {
                if (tc.getRunStatus() == com.testgen.model.TestRunStatus.FAILED) {
                    sb.append(String.format(" 🔴 Test Name : %s (%s)\n", tc.getTestName(), tc.getFramework()));
                    String output = tc.getRunOutput();
                    if (output != null && !output.isBlank()) {
                        String[] lines = output.split("\n");
                        int printedLines = 0;
                        for (String line : lines) {
                            if (printedLines >= 10) {
                                sb.append("    ... [log output truncated for brevity] ...\n");
                                break;
                            }
                            sb.append("    ").append(line).append("\n");
                            printedLines++;
                        }
                    } else {
                        sb.append("    No logs available.\n");
                    }
                    sb.append("\n");
                }
            }
        }
        sb.append("================================================================================\n");
        log.info("{}", sb.toString());
    }
}
