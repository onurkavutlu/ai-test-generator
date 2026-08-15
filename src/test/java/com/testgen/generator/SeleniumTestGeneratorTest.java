package com.testgen.generator;

import com.testgen.llm.LlmService;
import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.runner.GeneratedJavaTestProjectService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class SeleniumTestGeneratorTest {

    private SeleniumTestGenerator generator() {
        return new SeleniumTestGenerator(
                Mockito.mock(LlmService.class),
                Mockito.mock(GeneratedJavaTestProjectService.class),
                new GenerationLimit(0));
    }

    /** ObservationService.observePage'in ürettiği gerçek bölüm biçimi. */
    private static final String OBSERVED = """
            ## OBSERVED PAGE (canlı çekildi — selector'lar GERÇEK)
            URL: http://localhost:8081
            HTTP Status: 200
            Gerçek <title>: AI Test Generator - Dashboard
            Gerçek elementler (tag#id): select#test-type, select#framework, button#btn-submit-generate
            KURAL: Yalnızca yukarıda listelenen id'leri selector olarak kullan.
            """;

    @Test
    public void observedSmokeTestUsesOnlyObservedValues() {
        Optional<GeneratedTestCase> tc =
                generator().buildObservedSmokeTest("http://localhost:8081", OBSERVED);

        assertTrue(tc.isPresent());
        GeneratedTestCase c = tc.get();
        assertEquals("ObservedSmokeTest", c.getTestName());
        assertEquals("ObservedSmokeTest.java", c.getFileName());
        assertEquals(TestFramework.SELENIUM, c.getFramework());
        assertTrue(c.getTestSummary().contains("[OBSERVED][DETERMINISTIC]"));

        String code = c.getTestContent();
        // Gozlenen gercek baslik ve gercek element id'leri
        assertTrue(code.contains("assertEquals(\"AI Test Generator - Dashboard\", driver.getTitle()"));
        assertTrue(code.contains("By.id(\"test-type\")"));
        // Gozlem gorunurluk bilgisi VERMEZ; yalnizca DOM'da varlik dogrulanmali
        assertTrue(code.contains("assertFalse(driver.findElements(By.id("), code);
        assertTrue(!code.contains("isDisplayed()"), "gorunurluk varsayimi yapilmamali");
        assertTrue(code.contains("By.id(\"framework\")"));
        assertTrue(code.contains("By.id(\"btn-submit-generate\")"));
        assertTrue(code.contains("driver.get(\"http://localhost:8081\")"));
        // Derlenebilirlik iskeleti
        assertTrue(code.contains("DriverFactory.createDriver()"));
        assertTrue(code.contains("driver.quit()"));
        assertTrue(code.contains("import org.junit.jupiter.api.Test;"));
        assertTrue(code.contains("import org.openqa.selenium.By;"));
        assertTrue(code.startsWith("package com.testgen.generated;"));
    }

    @Test
    public void noObservationMeansNoDeterministicTest() {
        // Gözlem yoksa hiçbir şey uydurulmaz
        assertTrue(generator().buildObservedSmokeTest("http://x", null).isEmpty());
        assertTrue(generator().buildObservedSmokeTest("http://x", "## AI AGENT ANALYSIS\nfoo").isEmpty());
        assertTrue(generator().buildObservedSmokeTest("http://x",
                "## OBSERVED PAGE\nGerçek <title>: (title yok)\n").isEmpty());
    }

    @Test
    public void pageWithoutIdsStillProducesTitleAssertion() {
        String observed = """
                ## OBSERVED PAGE (canlı çekildi)
                URL: http://localhost:8081
                Gerçek <title>: Basit Sayfa
                id'li form elemanı bulunamadı — yalnızca sayfa yükleme/başlık senaryoları yaz.
                """;

        GeneratedTestCase c = generator().buildObservedSmokeTest("http://localhost:8081", observed).orElseThrow();

        assertTrue(c.getTestContent().contains("assertEquals(\"Basit Sayfa\", driver.getTitle()"));
        assertTrue(!c.getTestContent().contains("By.id("));
    }

    @Test
    public void titleWithQuotesIsEscaped() {
        String observed = "## OBSERVED PAGE\nGerçek <title>: A \"B\" C\n";

        GeneratedTestCase c = generator().buildObservedSmokeTest("http://x", observed).orElseThrow();

        assertTrue(c.getTestContent().contains("assertEquals(\"A \\\"B\\\" C\", driver.getTitle()"));
    }
}
