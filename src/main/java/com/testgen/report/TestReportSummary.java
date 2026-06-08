package com.testgen.report;

import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.model.TestRunStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Email ve Allure raporunda kullanılan özet DTO.
 */
@Data
@Builder
public class TestReportSummary {

    private String  requestId;
    private String  projectName;
    private String  requestContext;
    private LocalDateTime generatedAt;
    private LocalDateTime reportedAt;

    // Sayısal özet
    private int totalTests;
    private int passedTests;
    private int failedTests;
    private int skippedTests;
    private int brokenTests;

    // Framework bazlı özet
    private Map<TestFramework, FrameworkSummary> frameworkSummaries;

    // Allure link
    private String allureReportUrl;

    // Ham test case listesi
    private List<GeneratedTestCase> testCases;

    // ─── Hesaplama ────────────────────────────────────────
    public double getPassRate() {
        if (totalTests == 0) return 0.0;
        return (passedTests * 100.0) / totalTests;
    }

    public String getPassRateFormatted() {
        return String.format("%.1f%%", getPassRate());
    }

    public String getOverallStatus() {
        if (failedTests == 0 && brokenTests == 0) return "PASSED";
        if (passedTests == 0) return "FAILED";
        return "PARTIAL";
    }

    public String getStatusEmoji() {
        return switch (getOverallStatus()) {
            case "PASSED"  -> "✅";
            case "FAILED"  -> "❌";
            default        -> "⚠️";
        };
    }

    public String getFormattedDate() {
        return reportedAt != null
                ? reportedAt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                : LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
    }

    public List<String> getGenerationHighlights() {
        if (testCases == null || testCases.isEmpty()) {
            return List.of("Bu calisma icin henuz test case uretilmedi.");
        }

        return testCases.stream()
                .filter(tc -> !hasTag(tc, "AUTO-FIX"))
                .map(tc -> "%s: %s".formatted(tc.getTestName(), cleanSummary(tc)))
                .filter(text -> !text.isBlank())
                .limit(6)
                .toList();
    }

    public List<String> getImprovementHighlights() {
        if (testCases == null || testCases.isEmpty()) {
            return List.of("Iyilestirme icin kullanilabilecek test sonucu henuz yok.");
        }

        List<String> highlights = testCases.stream()
                .filter(tc -> hasTag(tc, "AUTO-FIX"))
                .map(tc -> "%s: %s".formatted(tc.getTestName(), cleanSummary(tc)))
                .collect(Collectors.toList());

        testCases.stream()
                .filter(tc -> tc.getRunStatus() == TestRunStatus.FAILED)
                .map(tc -> "%s: Basarisiz sonuc LLM iyilestirme dongusu icin aday olarak isaretlendi.".formatted(tc.getTestName()))
                .forEach(highlights::add);

        if (highlights.isEmpty()) {
            highlights.add("Basarisiz veya otomatik duzeltilmis test bulunmuyor; iyilestirme aksiyonu gerekmiyor.");
        }

        return highlights.stream().limit(6).toList();
    }

    public List<String> getAgentHighlights() {
        if (requestContext == null || !requestContext.contains("## AI AGENT ANALYSIS")) {
            return List.of("AI agent analizi bu koşum için bulunamadı.");
        }

        String agentSection = requestContext.substring(requestContext.indexOf("## AI AGENT ANALYSIS"));
        return agentSection.lines()
                .filter(line -> line.startsWith("### "))
                .map(line -> line.replace("### ", "").trim() + " tamamlandı.")
                .limit(8)
                .toList();
    }

    private static boolean hasTag(GeneratedTestCase testCase, String tag) {
        return testCase.getTestSummary() != null && testCase.getTestSummary().contains("[" + tag + "]");
    }

    private static String cleanSummary(GeneratedTestCase testCase) {
        String summary = testCase.getTestSummary();
        if (summary == null || summary.isBlank()) {
            return "Ozet bilgisi yok.";
        }
        return summary.replaceAll("\\[[^]]+]", "").trim();
    }

    // ─── Factory ─────────────────────────────────────────
    public static TestReportSummary from(String requestId,
                                          List<GeneratedTestCase> cases,
                                          String allureUrl) {
        return from(requestId, null, cases, allureUrl);
    }

    public static TestReportSummary from(String requestId,
                                          String requestContext,
                                          List<GeneratedTestCase> cases,
                                          String allureUrl) {
        int total   = cases.size();
        int passed  = count(cases, TestRunStatus.PASSED);
        int failed  = count(cases, TestRunStatus.FAILED);
        int skipped = count(cases, TestRunStatus.SKIPPED);
        int broken  = total - passed - failed - skipped;

        Map<TestFramework, FrameworkSummary> frameworkMap = cases.stream()
                .collect(Collectors.groupingBy(
                        GeneratedTestCase::getFramework,
                        Collectors.collectingAndThen(Collectors.toList(), FrameworkSummary::from)
                ));

        return TestReportSummary.builder()
                .requestId(requestId)
                .projectName("AI Test Generator")
                .requestContext(requestContext)
                .generatedAt(LocalDateTime.now())
                .reportedAt(LocalDateTime.now())
                .totalTests(total)
                .passedTests(passed)
                .failedTests(failed)
                .skippedTests(skipped)
                .brokenTests(Math.max(0, broken))
                .frameworkSummaries(frameworkMap)
                .allureReportUrl(allureUrl)
                .testCases(cases)
                .build();
    }

    private static int count(List<GeneratedTestCase> cases, TestRunStatus status) {
        return (int) cases.stream()
                .filter(tc -> status == tc.getRunStatus())
                .count();
    }

    // ── İç sınıf ──────────────────────────────────────────
    @Data
    @Builder
    public static class FrameworkSummary {
        private TestFramework framework;
        private int total;
        private int passed;
        private int failed;

        public static FrameworkSummary from(List<GeneratedTestCase> cases) {
            return FrameworkSummary.builder()
                    .framework(cases.isEmpty() ? null : cases.get(0).getFramework())
                    .total(cases.size())
                    .passed(count(cases, TestRunStatus.PASSED))
                    .failed(count(cases, TestRunStatus.FAILED))
                    .build();
        }

        private static int count(List<GeneratedTestCase> list, TestRunStatus s) {
            return (int) list.stream().filter(tc -> s == tc.getRunStatus()).count();
        }
    }
}
