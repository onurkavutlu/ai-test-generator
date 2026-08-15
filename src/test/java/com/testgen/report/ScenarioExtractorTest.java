package com.testgen.report;

import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ScenarioExtractorTest {

    @Test
    public void gherkinScenariosAndStepsAreExtracted() {
        String feature = """
                @testCaseLLM
                Feature: Sistem Sağlık Kontrolü

                  Background:
                    * def baseUrl = 'http://localhost:8080'

                  @smoke @P0
                  Scenario: Health endpoint UP dönmeli
                    Given url baseUrl + '/api/v1/tests/health'
                    When method get
                    Then status 200
                    And match response.status == 'UP'

                  @negative
                  Scenario: Olmayan endpoint 404 dönmeli
                    Given url baseUrl + '/api/v1/yok'
                    When method get
                    Then status 404
                """;

        List<ScenarioExtractor.Scenario> scenarios = ScenarioExtractor.extractGherkin(feature);

        assertEquals(2, scenarios.size());

        ScenarioExtractor.Scenario first = scenarios.get(0);
        assertEquals("Health endpoint UP dönmeli", first.name());
        assertTrue(first.tags().contains("@smoke"));
        assertTrue(first.tags().contains("@P0"));
        // Background adımı her senaryonun başına eklenir
        assertEquals("* def baseUrl = 'http://localhost:8080'", first.steps().get(0));
        assertTrue(first.steps().contains("When method get"));
        assertTrue(first.steps().contains("Then status 200"));
        assertTrue(first.steps().contains("And match response.status == 'UP'"));

        ScenarioExtractor.Scenario second = scenarios.get(1);
        assertEquals("Olmayan endpoint 404 dönmeli", second.name());
        assertTrue(second.steps().contains("Then status 404"));
    }

    @Test
    public void gherkinCommentsAndFeatureTagsAreIgnored() {
        String feature = """
                Feature: X
                  # bu bir yorum
                  Scenario: Tek senaryo
                    Then status 200
                """;

        List<ScenarioExtractor.Scenario> scenarios = ScenarioExtractor.extractGherkin(feature);

        assertEquals(1, scenarios.size());
        assertEquals("Tek senaryo", scenarios.get(0).name());
        assertEquals(List.of("Then status 200"), scenarios.get(0).steps());
    }

    @Test
    public void javaTestMethodsBecomeScenarios() {
        String java = """
                package com.testgen.generated;

                import org.junit.jupiter.api.Test;

                public class DashboardTest {

                    @Test
                    public void dashboardBasligiGorunmeli() {
                        // yorum satırı atlanır
                        driver.get("http://localhost:8080");
                        assertEquals("AI Test Generator - Dashboard", driver.getTitle());
                    }

                    @Test
                    public void menuGecisiCalismali() throws Exception {
                        driver.findElement(By.id("nav-create")).click();
                    }
                }
                """;

        List<ScenarioExtractor.Scenario> scenarios = ScenarioExtractor.extractJavaTests(java);

        assertEquals(2, scenarios.size());
        assertEquals("Dashboard basligi gorunmeli", scenarios.get(0).name());
        assertTrue(scenarios.get(0).steps().stream().anyMatch(s -> s.contains("driver.get")));
        assertTrue(scenarios.get(0).steps().stream().noneMatch(s -> s.startsWith("//")));
        assertEquals("Menu gecisi calismali", scenarios.get(1).name());
        assertEquals(1, scenarios.get(1).steps().size());
    }

    @Test
    public void extractDispatchesOnFrameworkAndHandlesEmptyContent() {
        GeneratedTestCase karate = GeneratedTestCase.builder()
                .framework(TestFramework.KARATE)
                .testContent("Feature: F\n  Scenario: S\n    Then status 200")
                .build();
        assertEquals(1, ScenarioExtractor.extract(karate).size());

        GeneratedTestCase empty = GeneratedTestCase.builder()
                .framework(TestFramework.SELENIUM)
                .testContent("   ")
                .build();
        assertTrue(ScenarioExtractor.extract(empty).isEmpty());
        assertTrue(ScenarioExtractor.extract(null).isEmpty());
    }

    @Test
    public void humanizeConvertsCamelCaseMethodNames() {
        assertEquals("Smoke happy path", ScenarioExtractor.humanize("smokeHappyPath"));
        assertEquals("Negative invalid input", ScenarioExtractor.humanize("negative_invalid_input"));
    }
}
