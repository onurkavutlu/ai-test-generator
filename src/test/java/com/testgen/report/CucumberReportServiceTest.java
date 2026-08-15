package com.testgen.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.model.TestRunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CucumberReportServiceTest {

    private static final String FEATURE = """
            Feature: Sağlık Kontrolü

              @smoke
              Scenario: Health endpoint UP dönmeli
                Given url 'http://localhost:8080/api/v1/tests/health'
                When method get
                Then status 200
            """;

    private CucumberReportService service(Path basePath) {
        CucumberReportService svc = new CucumberReportService(new ObjectMapper());
        ReflectionTestUtils.setField(svc, "basePath", basePath.toString());
        return svc;
    }

    private GeneratedTestCase karateCase(TestRunStatus status, int passed, int total) {
        return GeneratedTestCase.builder()
                .testName("HealthTest")
                .fileName("HealthTest.feature")
                .framework(TestFramework.KARATE)
                .testContent(FEATURE)
                .runStatus(status)
                .passedScenarios(passed)
                .totalScenarios(total)
                .executionTimeMs(1200L)
                .build();
    }

    @Test
    public void reportContainsRealScenarioNamesAndSteps(@TempDir Path tmp) throws Exception {
        Path html = service(tmp).generateReport("req-1", List.of(karateCase(TestRunStatus.PASSED, 1, 1)));

        assertNotNull(html);
        String content = Files.readString(html);

        // Gerçek Gherkin adı ve adımları rapora yansımalı — eski hâlde "HealthTest [1/1]" ve
        // tek bir "test senaryosu çalıştırıldı" adımı yazılıyordu.
        assertTrue(content.contains("Health endpoint UP dönmeli"), "gerçek senaryo adı olmalı");
        assertTrue(content.contains("method get"), "gerçek adım olmalı");
        assertTrue(content.contains("status 200"), "gerçek adım olmalı");
        assertTrue(content.contains("@smoke"), "senaryo tag&#39;i olmalı");
        assertTrue(!content.contains("test senaryosu çalıştırıldı"),
                "uydurma placeholder adım kalmamalı");

        String json = Files.readString(html.getParent().resolve("cucumber.json"));
        assertTrue(json.contains("\"name\" : \"Health endpoint UP dönmeli\""));
        assertTrue(json.contains("\"keyword\" : \"When \""));
    }

    @Test
    public void failedRunAttachesErrorToLastStep(@TempDir Path tmp) throws Exception {
        GeneratedTestCase failed = karateCase(TestRunStatus.FAILED, 0, 1);
        failed.setRunOutput("no step-definition method match found");

        Path html = service(tmp).generateReport("req-2", List.of(failed));
        String json = Files.readString(html.getParent().resolve("cucumber.json"));

        assertTrue(json.contains("\"status\" : \"failed\""));
        assertTrue(json.contains("no step-definition method match found"));
    }

    @Test
    public void unparsableContentFallsBackToSummaryScenarios(@TempDir Path tmp) throws Exception {
        GeneratedTestCase odd = GeneratedTestCase.builder()
                .testName("OpaqueTest")
                .fileName("OpaqueTest.feature")
                .framework(TestFramework.KARATE)
                .testContent("bu bir gherkin degil")
                .runStatus(TestRunStatus.PASSED)
                .passedScenarios(2)
                .totalScenarios(2)
                .build();

        Path html = service(tmp).generateReport("req-3", List.of(odd));
        String json = Files.readString(html.getParent().resolve("cucumber.json"));

        // Ayrıştırılamayınca eski özet davranışı korunur, rapor üretimi kırılmaz
        assertTrue(json.contains("OpaqueTest [1/2]"));
        assertTrue(json.contains("test senaryosu çalıştırıldı"));
    }
}
