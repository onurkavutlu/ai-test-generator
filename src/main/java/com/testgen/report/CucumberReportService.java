package com.testgen.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestRunStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Cucumber HTML raporu üretir.
 *
 * Karate zaten Gherkin/Cucumber formatında feature dosyaları kullanır.
 * Bu servis test sonuçlarından standart Cucumber JSON formatı üretip
 * bunu basit bir self-contained HTML rapora dönüştürür.
 * allure-cucumber7-jvm entegrasyonu için de Allure-uyumlu JSON yazılır.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CucumberReportService {

    private final ObjectMapper objectMapper;

    @Value("${test-generator.output.base-path}")
    private String basePath;

    // ─────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────

    /**
     * Test sonuçlarından Cucumber JSON + HTML rapor üretir.
     * @return Üretilen HTML rapor dosyasının path'i, hata durumunda null.
     */
    public Path generateReport(String requestId, List<GeneratedTestCase> testCases) {
        try {
            Path reportDir = Path.of(basePath, "cucumber-reports", requestId);
            Files.createDirectories(reportDir);

            // 1. Cucumber JSON dosyası yaz
            List<Map<String, Object>> cucumberJson = buildCucumberJson(testCases);
            Path jsonFile = reportDir.resolve("cucumber.json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonFile.toFile(), cucumberJson);
            log.info("Cucumber JSON yazıldı: {}", jsonFile);

            // 2. HTML rapor üret
            Path htmlFile = reportDir.resolve("cucumber-report.html");
            String html = buildHtmlReport(requestId, testCases, cucumberJson);
            Files.writeString(htmlFile, html, StandardCharsets.UTF_8);
            log.info("Cucumber HTML raporu üretildi: {}", htmlFile);

            return htmlFile;

        } catch (IOException e) {
            log.error("Cucumber raporu üretilemedi - requestId: {}", requestId, e);
            return null;
        }
    }

    /**
     * Verilen requestId için mevcut rapor dosyasının içeriğini döner.
     */
    public Optional<String> readReport(String requestId) {
        Path htmlFile = Path.of(basePath, "cucumber-reports", requestId, "cucumber-report.html");
        if (!Files.exists(htmlFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(htmlFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("Cucumber raporu okunamadı: {}", htmlFile, e);
            return Optional.empty();
        }
    }

    // ─────────────────────────────────────────────────────────
    // Cucumber JSON builder
    // ─────────────────────────────────────────────────────────

    private List<Map<String, Object>> buildCucumberJson(List<GeneratedTestCase> testCases) {
        List<Map<String, Object>> features = new ArrayList<>();

        for (GeneratedTestCase tc : testCases) {
            Map<String, Object> feature = new LinkedHashMap<>();
            feature.put("id",          sanitizeId(tc.getTestName()));
            feature.put("name",        tc.getTestName());
            feature.put("description", tc.getTestSummary() != null ? tc.getTestSummary() : "");
            feature.put("keyword",     "Feature");
            feature.put("uri",         tc.getFileName() != null ? tc.getFileName() : tc.getTestName() + ".feature");
            feature.put("line",        1);
            feature.put("tags",        buildTags(tc));
            feature.put("elements",    buildScenarios(tc));
            features.add(feature);
        }

        return features;
    }

    private List<Map<String, Object>> buildTags(GeneratedTestCase tc) {
        List<Map<String, Object>> tags = new ArrayList<>();
        if (tc.getTestCategory() != null) {
            tags.add(Map.of("name", "@" + tc.getTestCategory().name(), "line", 0));
        }
        if (tc.getTestPriority() != null) {
            tags.add(Map.of("name", "@" + tc.getTestPriority().name(), "line", 0));
        }
        if (tc.getFramework() != null) {
            tags.add(Map.of("name", "@" + tc.getFramework().name(), "line", 0));
        }
        return tags;
    }

    private List<Map<String, Object>> buildScenarios(GeneratedTestCase tc) {
        List<Map<String, Object>> scenarios = new ArrayList<>();

        int passed  = tc.getPassedScenarios() != null ? tc.getPassedScenarios() : 0;
        int failed  = tc.getFailedScenarios()  != null ? tc.getFailedScenarios()  : 0;
        int total   = tc.getTotalScenarios()   != null ? tc.getTotalScenarios()   : 1;

        // Her senaryo için tek bir step ekle (özet seviye)
        for (int i = 0; i < Math.max(total, 1); i++) {
            boolean scenarioPassed = i < passed;
            boolean scenarioFailed = i < failed;
            String status = scenarioPassed ? "passed"
                    : (tc.getRunStatus() == TestRunStatus.NOT_RUN ? "skipped"
                    : scenarioFailed ? "failed" : "failed");

            Map<String, Object> scenario = new LinkedHashMap<>();
            scenario.put("id",          sanitizeId(tc.getTestName()) + ";scenario-" + (i + 1));
            scenario.put("name",        tc.getTestName() + (total > 1 ? " [" + (i + 1) + "/" + total + "]" : ""));
            scenario.put("keyword",     "Scenario");
            scenario.put("line",        i + 2);
            scenario.put("description", "");
            scenario.put("type",        "scenario");
            scenario.put("steps",       buildSteps(tc, status));
            scenarios.add(scenario);
        }

        return scenarios;
    }

    private List<Map<String, Object>> buildSteps(GeneratedTestCase tc, String status) {
        long durationNs = tc.getExecutionTimeMs() != null ? tc.getExecutionTimeMs() * 1_000_000L : 0L;

        Map<String, Object> step = new LinkedHashMap<>();
        step.put("keyword", "Given ");
        step.put("name",    "test senaryosu çalıştırıldı");
        step.put("line",    3);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status",   status);
        result.put("duration", durationNs);
        if ("failed".equals(status) && tc.getRunOutput() != null) {
            String errMsg = tc.getRunOutput().length() > 500
                    ? tc.getRunOutput().substring(0, 500) + "..."
                    : tc.getRunOutput();
            result.put("error_message", errMsg);
        }
        step.put("result", result);

        return List.of(step);
    }

    private String sanitizeId(String name) {
        return name == null ? "unknown" : name.toLowerCase().replaceAll("[^a-z0-9]+", "-");
    }

    // ─────────────────────────────────────────────────────────
    // HTML Report builder (self-contained)
    // ─────────────────────────────────────────────────────────

    private String buildHtmlReport(String requestId,
                                   List<GeneratedTestCase> testCases,
                                   List<Map<String, Object>> cucumberJson) {
        long passedCount  = testCases.stream().filter(tc -> tc.getRunStatus() == TestRunStatus.PASSED).count();
        long failedCount  = testCases.stream().filter(tc -> tc.getRunStatus() == TestRunStatus.FAILED).count();
        long skippedCount = testCases.stream().filter(tc -> tc.getRunStatus() == TestRunStatus.NOT_RUN || tc.getRunStatus() == TestRunStatus.SKIPPED).count();
        long totalCount   = testCases.size();
        long totalMs      = testCases.stream().mapToLong(tc -> tc.getExecutionTimeMs() != null ? tc.getExecutionTimeMs() : 0).sum();
        int  passRate     = totalCount > 0 ? (int) Math.round(passedCount * 100.0 / totalCount) : 0;
        String reportDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss"));

        StringBuilder rows = new StringBuilder();
        for (GeneratedTestCase tc : testCases) {
            String statusClass = switch (tc.getRunStatus()) {
                case PASSED  -> "status-passed";
                case FAILED  -> "status-failed";
                case RUNNING -> "status-running";
                default      -> "status-skipped";
            };
            String statusText = tc.getRunStatus().name();
            int    scenPassed = tc.getPassedScenarios() != null ? tc.getPassedScenarios() : 0;
            int    scenFailed = tc.getFailedScenarios()  != null ? tc.getFailedScenarios()  : 0;
            int    scenTotal  = tc.getTotalScenarios()   != null ? tc.getTotalScenarios()   : 0;
            String duration   = tc.getExecutionTimeMs()  != null
                    ? String.format("%.2fs", tc.getExecutionTimeMs() / 1000.0) : "-";
            String framework  = tc.getFramework() != null ? tc.getFramework().name() : "-";
            String category   = tc.getTestCategory() != null ? tc.getTestCategory().name() : "-";

            rows.append("""
                    <tr>
                      <td><span class="badge badge-fw">%s</span></td>
                      <td class="feature-name">%s</td>
                      <td><span class="status-badge %s">%s</span></td>
                      <td>%d / %d</td>
                      <td class="%s">%d</td>
                      <td>%s</td>
                      <td><span class="badge badge-cat">%s</span></td>
                    </tr>
                    """.formatted(
                    escHtml(framework),
                    escHtml(tc.getTestName()),
                    statusClass, statusText,
                    scenPassed, scenTotal,
                    scenFailed > 0 ? "cell-failed" : "",
                    scenFailed,
                    duration,
                    escHtml(category)
            ));
        }

        String jsonData;
        try {
            jsonData = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(cucumberJson);
        } catch (Exception e) {
            jsonData = "[]";
        }

        return """
<!DOCTYPE html>
<html lang="tr">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Cucumber Report — %s</title>
<style>
  :root {
    --bg: #0d1117; --surface: #161b22; --border: #30363d;
    --green: #3fb950; --red: #f85149; --yellow: #d29922;
    --blue: #58a6ff; --purple: #bc8cff; --text: #e6edf3; --muted: #8b949e;
  }
  * { margin:0; padding:0; box-sizing:border-box; }
  body { background:var(--bg); color:var(--text); font-family:'Segoe UI',system-ui,sans-serif; padding:32px; }
  h1 { font-size:24px; font-weight:700; margin-bottom:4px; }
  .subtitle { color:var(--muted); font-size:13px; margin-bottom:28px; }
  .summary-grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(160px,1fr)); gap:16px; margin-bottom:32px; }
  .summary-card { background:var(--surface); border:1px solid var(--border); border-radius:10px; padding:20px; }
  .summary-card .label { font-size:12px; color:var(--muted); text-transform:uppercase; letter-spacing:.5px; margin-bottom:8px; }
  .summary-card .value { font-size:32px; font-weight:700; }
  .summary-card.green .value { color:var(--green); }
  .summary-card.red   .value { color:var(--red); }
  .summary-card.blue  .value { color:var(--blue); }
  .progress-bar { height:8px; background:var(--border); border-radius:4px; margin-bottom:32px; overflow:hidden; }
  .progress-fill { height:100%%; background:linear-gradient(90deg,var(--green),#56d364); border-radius:4px; transition:width .6s; }
  table { width:100%%; border-collapse:collapse; background:var(--surface); border-radius:10px; overflow:hidden; border:1px solid var(--border); }
  th { background:#1c2128; padding:12px 16px; text-align:left; font-size:12px; color:var(--muted); text-transform:uppercase; letter-spacing:.5px; border-bottom:1px solid var(--border); }
  td { padding:12px 16px; border-bottom:1px solid var(--border); font-size:14px; }
  tr:last-child td { border-bottom:none; }
  tr:hover td { background:rgba(255,255,255,.03); }
  .feature-name { font-weight:500; color:var(--blue); }
  .status-badge { display:inline-block; padding:3px 10px; border-radius:99px; font-size:12px; font-weight:600; }
  .status-passed  { background:rgba(63,185,80,.15);  color:var(--green); }
  .status-failed  { background:rgba(248,81,73,.15);  color:var(--red); }
  .status-skipped { background:rgba(139,148,158,.15);color:var(--muted); }
  .status-running { background:rgba(88,166,255,.15); color:var(--blue); }
  .badge { display:inline-block; padding:2px 8px; border-radius:6px; font-size:11px; font-weight:600; }
  .badge-fw  { background:rgba(188,140,255,.15); color:var(--purple); }
  .badge-cat { background:rgba(210,153,34,.15);  color:var(--yellow); }
  .cell-failed { color:var(--red); font-weight:600; }
  .json-section { margin-top:40px; }
  .json-section h2 { font-size:16px; margin-bottom:12px; }
  .json-box { background:var(--surface); border:1px solid var(--border); border-radius:10px; padding:20px; overflow:auto; max-height:400px; }
  pre { font-family:'JetBrains Mono',monospace; font-size:12px; color:#adbac7; }
  .copy-btn { float:right; margin-top:-4px; background:#238636; border:none; color:#fff; padding:6px 14px; border-radius:6px; cursor:pointer; font-size:12px; }
  .copy-btn:hover { background:#2ea043; }
  footer { margin-top:40px; text-align:center; font-size:12px; color:var(--muted); }
</style>
</head>
<body>
<h1>🥒 Cucumber Test Raporu</h1>
<p class="subtitle">Request ID: <strong>%s</strong> &nbsp;|&nbsp; %s</p>

<div class="summary-grid">
  <div class="summary-card blue"><div class="label">Toplam</div><div class="value">%d</div></div>
  <div class="summary-card green"><div class="label">Geçti</div><div class="value">%d</div></div>
  <div class="summary-card red"><div class="label">Kaldı</div><div class="value">%d</div></div>
  <div class="summary-card"><div class="label">Atlanan</div><div class="value" style="color:var(--muted)">%d</div></div>
  <div class="summary-card"><div class="label">Süre</div><div class="value" style="font-size:22px;padding-top:8px">%.1fs</div></div>
  <div class="summary-card green"><div class="label">Başarı Oranı</div><div class="value">%d%%</div></div>
</div>

<div class="progress-bar"><div class="progress-fill" style="width:%d%%"></div></div>

<table>
  <thead>
    <tr>
      <th>Framework</th><th>Feature</th><th>Durum</th>
      <th>Geçen/Toplam</th><th>Başarısız</th><th>Süre</th><th>Kategori</th>
    </tr>
  </thead>
  <tbody>%s</tbody>
</table>

<div class="json-section">
  <h2>Cucumber JSON Çıktısı <button class="copy-btn" onclick="copyJson()">Kopyala</button></h2>
  <div class="json-box"><pre id="jsonPre">%s</pre></div>
</div>

<footer>AI Test Generator &mdash; Cucumber Reporter &mdash; Üretildi: %s</footer>

<script>
function copyJson() {
  navigator.clipboard.writeText(document.getElementById('jsonPre').textContent);
  const btn = document.querySelector('.copy-btn');
  btn.textContent = 'Kopyalandı!';
  setTimeout(() => btn.textContent = 'Kopyala', 2000);
}
</script>
</body>
</html>
""".formatted(
                requestId,
                requestId, reportDate,
                totalCount, passedCount, failedCount, skippedCount,
                totalMs / 1000.0, passRate, passRate,
                rows.toString(),
                escHtml(jsonData),
                reportDate
        );
    }

    private String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
