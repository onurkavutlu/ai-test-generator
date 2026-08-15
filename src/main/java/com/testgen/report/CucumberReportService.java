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
        // Test içeriğinden GERÇEK senaryo adları ve adımları çıkarılır; ayrıştırılamazsa
        // eski özet davranışına (senaryo sayısı kadar tek adımlı blok) düşülür.
        List<ScenarioExtractor.Scenario> parsed = ScenarioExtractor.extract(tc);
        List<Map<String, Object>> scenarios = new ArrayList<>();

        int passed = tc.getPassedScenarios() != null ? tc.getPassedScenarios() : 0;
        int declaredTotal = tc.getTotalScenarios() != null ? tc.getTotalScenarios() : 0;
        int total = parsed.isEmpty() ? Math.max(declaredTotal, 1) : parsed.size();

        for (int i = 0; i < total; i++) {
            String status = statusOf(tc, i, passed);
            ScenarioExtractor.Scenario source = i < parsed.size() ? parsed.get(i) : null;

            Map<String, Object> scenario = new LinkedHashMap<>();
            scenario.put("id", sanitizeId(tc.getTestName()) + ";scenario-" + (i + 1));
            scenario.put("name", source != null
                    ? source.name()
                    : tc.getTestName() + (total > 1 ? " [" + (i + 1) + "/" + total + "]" : ""));
            scenario.put("keyword", "Scenario");
            scenario.put("line", i + 2);
            scenario.put("description", "");
            scenario.put("type", "scenario");
            if (source != null && !source.tags().isEmpty()) {
                scenario.put("tags", source.tags().stream()
                        .map(t -> Map.<String, Object>of("name", t, "line", 0))
                        .toList());
            }
            scenario.put("steps", buildSteps(tc, status, source));
            scenarios.add(scenario);
        }

        return scenarios;
    }

    /**
     * Senaryo durumu. Runner senaryo bazında sonuç vermediği için kısmi koşumlarda
     * ilk {@code passed} senaryo geçmiş kabul edilir; tümü geçtiyse/kaldıysa kesin.
     */
    private String statusOf(GeneratedTestCase tc, int index, int passed) {
        if (tc.getRunStatus() == TestRunStatus.NOT_RUN || tc.getRunStatus() == TestRunStatus.SKIPPED) {
            return "skipped";
        }
        return index < passed ? "passed" : "failed";
    }

    private List<Map<String, Object>> buildSteps(GeneratedTestCase tc, String status,
                                                 ScenarioExtractor.Scenario source) {
        List<String> stepTexts = source != null && !source.steps().isEmpty()
                ? source.steps()
                : List.of("Given test senaryosu çalıştırıldı");

        // Süre senaryo adımlarına eşit dağıtılır — toplam koşum süresi korunur
        long totalNs = tc.getExecutionTimeMs() != null ? tc.getExecutionTimeMs() * 1_000_000L : 0L;
        long perStepNs = stepTexts.isEmpty() ? 0L : totalNs / stepTexts.size();

        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 0; i < stepTexts.size(); i++) {
            String text = stepTexts.get(i);
            String keyword = keywordOf(text);
            String name = text.substring(Math.min(keyword.length(), text.length())).trim();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", status);
            result.put("duration", perStepNs);
            // Hata mesajı yalnızca başarısız senaryonun SON adımına iliştirilir
            if ("failed".equals(status) && i == stepTexts.size() - 1 && tc.getRunOutput() != null) {
                result.put("error_message", truncate(tc.getRunOutput()));
            }

            Map<String, Object> step = new LinkedHashMap<>();
            step.put("keyword", keyword.isBlank() ? "* " : keyword + " ");
            step.put("name", name.isBlank() ? text : name);
            step.put("line", i + 3);
            step.put("result", result);
            steps.add(step);
        }
        return steps;
    }

    private static String keywordOf(String stepText) {
        for (String kw : List.of("Given", "When", "Then", "And", "But", "*")) {
            if (stepText.startsWith(kw + " ") || stepText.equals(kw)) {
                return kw;
            }
        }
        return "*";
    }

    private static String truncate(String text) {
        return text.length() > 500 ? text.substring(0, 500) + "..." : text;
    }

    private String sanitizeId(String name) {
        // Locale.ROOT şart: Türkçe locale'de 'I' → 'ı' olur, [^a-z0-9] filtresine takılır
        // ve id'ler bozulur (örn. "ApiTest" → "ap-test").
        return name == null ? "unknown"
                : name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
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
  .scenario-section { margin-top:40px; }
  .scenario-section h2 { font-size:16px; margin-bottom:16px; }
  .feature-block { background:var(--surface); border:1px solid var(--border); border-radius:10px; padding:18px 20px; margin-bottom:16px; }
  .feature-block h3 { font-size:14px; color:var(--blue); margin-bottom:12px; }
  .muted { color:var(--muted); font-weight:400; font-size:12px; }
  .scenario { border-left:3px solid var(--border); padding:6px 0 6px 14px; margin-bottom:14px; }
  .scenario-head { margin-bottom:8px; }
  .scenario-name { font-weight:600; font-size:13px; }
  ul.steps { list-style:none; margin:0; padding:0; }
  .step { font-family:'JetBrains Mono',monospace; font-size:12px; padding:3px 0 3px 12px; border-left:2px solid transparent; color:#adbac7; }
  .step .kw { color:var(--purple); font-weight:600; }
  .step-passed  { border-left-color:var(--green); }
  .step-failed  { border-left-color:var(--red); color:#ffb4ab; }
  .step-skipped { border-left-color:var(--muted); opacity:.7; }
  .step-error { margin-top:6px; padding:8px; background:rgba(248,81,73,.10); border-radius:6px; color:var(--red); white-space:pre-wrap; font-size:11px; }
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

<div class="scenario-section">
  <h2>Senaryolar &amp; Adımlar</h2>
  %s
</div>

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
                buildScenarioSection(cucumberJson),
                escHtml(jsonData),
                reportDate
        );
    }

    /** Feature → senaryo → adım kırılımını HTML olarak üretir. */
    @SuppressWarnings("unchecked")
    private String buildScenarioSection(List<Map<String, Object>> cucumberJson) {
        StringBuilder sb = new StringBuilder();

        for (Map<String, Object> feature : cucumberJson) {
            List<Map<String, Object>> elements =
                    (List<Map<String, Object>>) feature.getOrDefault("elements", List.of());
            sb.append("<div class=\"feature-block\"><h3>")
              .append(escHtml(String.valueOf(feature.get("name"))))
              .append(" <span class=\"muted\">(")
              .append(elements.size()).append(" senaryo)</span></h3>");

            for (Map<String, Object> element : elements) {
                List<Map<String, Object>> steps =
                        (List<Map<String, Object>>) element.getOrDefault("steps", List.of());
                String scenarioStatus = steps.isEmpty() ? "skipped"
                        : String.valueOf(((Map<String, Object>) steps.get(0).get("result")).get("status"));

                sb.append("<div class=\"scenario\"><div class=\"scenario-head\">")
                  .append("<span class=\"status-badge status-").append(scenarioStatus).append("\">")
                  .append(scenarioStatus.toUpperCase(java.util.Locale.ROOT)).append("</span> ")
                  .append("<span class=\"scenario-name\">")
                  .append(escHtml(String.valueOf(element.get("name")))).append("</span>");

                List<Map<String, Object>> tags =
                        (List<Map<String, Object>>) element.getOrDefault("tags", List.of());
                for (Map<String, Object> tag : tags) {
                    sb.append(" <span class=\"badge badge-cat\">")
                      .append(escHtml(String.valueOf(tag.get("name")))).append("</span>");
                }
                sb.append("</div><ul class=\"steps\">");

                for (Map<String, Object> step : steps) {
                    Map<String, Object> result = (Map<String, Object>) step.get("result");
                    String stepStatus = String.valueOf(result.get("status"));
                    sb.append("<li class=\"step step-").append(stepStatus).append("\">")
                      .append("<span class=\"kw\">").append(escHtml(String.valueOf(step.get("keyword")))).append("</span>")
                      .append(escHtml(String.valueOf(step.get("name"))));
                    Object err = result.get("error_message");
                    if (err != null) {
                        sb.append("<pre class=\"step-error\">").append(escHtml(String.valueOf(err))).append("</pre>");
                    }
                    sb.append("</li>");
                }
                sb.append("</ul></div>");
            }
            sb.append("</div>");
        }

        return sb.length() == 0
                ? "<p class=\"muted\">Senaryo ayrıntısı çıkarılamadı.</p>"
                : sb.toString();
    }

    private String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
