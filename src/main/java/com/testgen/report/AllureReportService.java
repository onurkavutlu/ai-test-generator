package com.testgen.report;

import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.model.TestRunStatus;
import io.qameta.allure.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Allure raporlama servisi.
 *
 * Görevleri:
 *  1. Test çalışma sonuçlarını Allure JSON formatında allure-results klasörüne yazar
 *  2. allure CLI ile HTML raporu üretir
 *  3. Rapor path / URL bilgisini döner (email servisi bu bilgiyi kullanır)
 *
 * Allure result formatı: her test için bir UUID.json dosyası
 */
@Slf4j
@Service
public class AllureReportService {

    @Value("${test-generator.output.allure-results}")
    private String allureResultsPath;

    @Value("${test-generator.output.allure-report}")
    private String allureReportPath;

    @Value("${notification.email.allure-report-url}")
    private String allureReportUrl;

    // ─────────────────────────────────────────────────────────
    // Test sonuçlarını Allure result JSON'a yaz
    // ─────────────────────────────────────────────────────────

    /**
     * Tek bir test case'in sonucunu Allure result formatında diske yazar.
     * TestRunnerService her test bittikten sonra bu metodu çağırır.
     */
    public void writeTestResult(GeneratedTestCase tc, String requestId) {
        try {
            Path resultsDir = Path.of(allureResultsPath, requestId);
            Files.createDirectories(resultsDir);

            // Allure TestResult modeli oluştur
            TestResult result = buildAllureTestResult(tc, requestId);

            // JSON serialize
            String json = serializeToJson(result);
            Path resultFile = resultsDir.resolve(tc.getId() + "-result.json");
            Files.writeString(resultFile, json, StandardCharsets.UTF_8);

            // Eğer test başarısız olduysa run output'u attachment olarak ekle
            if (tc.getRunStatus() == TestRunStatus.FAILED && tc.getRunOutput() != null) {
                writeAttachment(resultsDir, tc.getId() + "-output.txt",
                        tc.getRunOutput(), "text/plain");
            }

            // Test content'ini (feature/java kodu) attachment olarak ekle
            if (tc.getTestContent() != null) {
                String ext = tc.getFramework() == TestFramework.KARATE ? ".feature" : ".java";
                writeAttachment(resultsDir, tc.getTestName() + ext,
                        tc.getTestContent(), "text/plain");
            }

            log.debug("Allure result yazıldı: {}", resultFile);

        } catch (IOException e) {
            log.error("Allure result yazılamadı: {}", tc.getId(), e);
        }
    }

    /**
     * Bir request'e ait tüm test case'lerin sonuçlarını yazar.
     */
    public void writeAllResults(List<GeneratedTestCase> testCases, String requestId) {
        testCases.forEach(tc -> writeTestResult(tc, requestId));
        writeEnvironmentProperties(requestId);
        writeCategoriesJson(requestId);
        log.info("{} adet Allure result yazıldı - requestId: {}", testCases.size(), requestId);
    }

    // ─────────────────────────────────────────────────────────
    // HTML Rapor Üretimi (allure CLI)
    // ─────────────────────────────────────────────────────────

    /**
     * allure generate komutu ile HTML raporu üretir.
     * Rapor dizinini döner.
     *
     * @return rapor dizin path'i veya null (allure CLI yoksa)
     */
    public AllureReportResult generateHtmlReport(String requestId) {
        Path resultsDir = Path.of(allureResultsPath, requestId);
        Path reportDir  = Path.of(allureReportPath, requestId);

        if (!Files.exists(resultsDir)) {
            log.warn("Allure results dizini bulunamadı: {}", resultsDir);
            return AllureReportResult.failed("Results dizini bulunamadı");
        }

        try {
            Files.createDirectories(reportDir);

            // allure CLI ile HTML rapor üret
            ProcessBuilder pb = new ProcessBuilder(
                    "allure", "generate",
                    resultsDir.toAbsolutePath().toString(),
                    "--output", reportDir.toAbsolutePath().toString(),
                    "--clean"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                reader.lines().forEach(line -> output.append(line).append("\n"));
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                String reportUrl = allureReportUrl + "/" + requestId + "/index.html";
                log.info("Allure HTML raporu üretildi: {}", reportDir);
                return AllureReportResult.success(reportDir.toString(), reportUrl);
            } else {
                log.error("allure generate başarısız (exit {}): {}", exitCode, output);
                // allure CLI yoksa in-house özet rapor üret
                return generateFallbackReport(requestId, reportDir);
            }

        } catch (Exception e) {
            log.warn("allure CLI bulunamadı veya hata: {} - fallback rapor üretiliyor", e.getMessage());
            return generateFallbackReport(requestId, reportDir);
        }
    }

    /**
     * allure CLI olmadığında basit HTML özet raporu üretir.
     */
    private AllureReportResult generateFallbackReport(String requestId, Path reportDir) {
        try {
            Files.createDirectories(reportDir);
            Path indexHtml = reportDir.resolve("index.html");
            // Minimal HTML template
            String html = buildFallbackHtml(requestId);
            Files.writeString(indexHtml, html, StandardCharsets.UTF_8);
            String reportUrl = allureReportUrl + "/" + requestId + "/index.html";
            return AllureReportResult.success(reportDir.toString(), reportUrl);
        } catch (IOException e) {
            return AllureReportResult.failed("Fallback rapor üretilemedi: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────
    // Allure JSON model
    // ─────────────────────────────────────────────────────────

    private TestResult buildAllureTestResult(GeneratedTestCase tc, String requestId) {
        Status allureStatus = switch (tc.getRunStatus()) {
            case PASSED  -> Status.PASSED;
            case FAILED  -> Status.FAILED;
            case SKIPPED -> Status.SKIPPED;
            default      -> Status.BROKEN;
        };

        long stopTime  = tc.getLastRunAt() != null
                ? tc.getLastRunAt().toInstant(ZoneOffset.UTC).toEpochMilli()
                : System.currentTimeMillis();
        long startTime = stopTime - (tc.getExecutionTimeMs() != null ? tc.getExecutionTimeMs() : 0L);

        return new TestResult()
                .setUuid(tc.getId() != null ? tc.getId() : UUID.randomUUID().toString())
                .setName(tc.getTestName())
                .setDescription(tc.getTestSummary())
                .setStatus(allureStatus)
                .setStart(startTime)
                .setStop(stopTime)
                .setLabels(List.of(
                        new Label().setName("suite").setValue(requestId),
                        new Label().setName("framework").setValue(tc.getFramework().name()),
                        new Label().setName("feature").setValue(tc.getFramework().name() + " Tests"),
                        new Label().setName("story").setValue(tc.getTestName()),
                        new Label().setName("tag").setValue("AI-Generated")
                ))
                .setLinks(List.of())
                .setStatusDetails(tc.getRunOutput() != null
                        ? new StatusDetails().setMessage(
                            tc.getRunOutput().length() > 500
                                ? tc.getRunOutput().substring(0, 500) + "..."
                                : tc.getRunOutput())
                        : null);
    }

    private String serializeToJson(TestResult result) {
        // Basit manuel JSON serialize (Jackson bağımlılığı zaten mevcut)
        return """
                {
                  "uuid": "%s",
                  "name": "%s",
                  "description": "%s",
                  "status": "%s",
                  "start": %d,
                  "stop": %d,
                  "labels": [
                    {"name": "suite",     "value": "%s"},
                    {"name": "framework", "value": "%s"},
                    {"name": "feature",   "value": "%s Tests"},
                    {"name": "tag",       "value": "AI-Generated"}
                  ],
                  "statusDetails": {
                    "message": "%s"
                  }
                }
                """.formatted(
                        safe(result.getUuid()),
                        safe(result.getName()),
                        safe(result.getDescription()),
                        result.getStatus().value(),
                        result.getStart() != null ? result.getStart() : 0,
                        result.getStop()  != null ? result.getStop()  : 0,
                        result.getLabels().stream().filter(l -> "suite".equals(l.getName()))
                              .findFirst().map(Label::getValue).orElse(""),
                        result.getLabels().stream().filter(l -> "framework".equals(l.getName()))
                              .findFirst().map(Label::getValue).orElse(""),
                        result.getLabels().stream().filter(l -> "framework".equals(l.getName()))
                              .findFirst().map(Label::getValue).orElse(""),
                        result.getStatusDetails() != null && result.getStatusDetails().getMessage() != null
                              ? safe(result.getStatusDetails().getMessage()) : ""
                );
    }

    private void writeAttachment(Path dir, String fileName, String content, String mimeType) throws IOException {
        Files.writeString(dir.resolve(fileName), content, StandardCharsets.UTF_8);
    }

    private void writeEnvironmentProperties(String requestId) {
        try {
            Path dir = Path.of(allureResultsPath, requestId);
            Files.createDirectories(dir);
            String props = """
                    Application=AI Test Generator
                    Version=1.0.0
                    RequestId=%s
                    GeneratedAt=%s
                    LLM.Provider=${llm.provider}
                    """.formatted(requestId, LocalDateTime.now());
            Files.writeString(dir.resolve("environment.properties"), props);
        } catch (IOException e) {
            log.warn("environment.properties yazılamadı", e);
        }
    }

    private void writeCategoriesJson(String requestId) {
        try {
            Path dir = Path.of(allureResultsPath, requestId);
            Files.createDirectories(dir);
            String categories = """
                    [
                      {
                        "name": "Başarılı Testler",
                        "matchedStatuses": ["passed"]
                      },
                      {
                        "name": "Başarısız Testler",
                        "matchedStatuses": ["failed"],
                        "messageRegex": ".*"
                      },
                      {
                        "name": "Kırık Testler (Broken)",
                        "matchedStatuses": ["broken"]
                      },
                      {
                        "name": "Atlanan Testler",
                        "matchedStatuses": ["skipped"]
                      }
                    ]
                    """;
            Files.writeString(dir.resolve("categories.json"), categories);
        } catch (IOException e) {
            log.warn("categories.json yazılamadı", e);
        }
    }

    private String buildFallbackHtml(String requestId) {
        return """
                <!DOCTYPE html>
                <html><head><title>Test Report – %s</title></head>
                <body style="font-family:sans-serif;padding:20px">
                <h2>AI Test Generator – Test Raporu</h2>
                <p><b>Request ID:</b> %s</p>
                <p><b>Üretilme Tarihi:</b> %s</p>
                <p style="color:#888">Allure CLI kurulu olmadığından detaylı rapor üretilemedi.
                   <a href="%s">Allure Server</a> üzerinde görüntüleyebilirsiniz.</p>
                </body></html>
                """.formatted(requestId, requestId, LocalDateTime.now(), allureReportUrl);
    }

    private String safe(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }
}
