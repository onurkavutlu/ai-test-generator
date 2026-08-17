package com.testgen.generator;

import com.testgen.llm.LlmService;
import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.runner.GeneratedJavaTestProjectService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

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

    @Test
    public void observedUiContractPrefersStableTestIdOverFallbackId() {
        String observed = """
                ## OBSERVED PAGE
                Gerçek <title>: Giriş
                Gerçek elementler (tag#id): input#username
                ## OBSERVED UI CONTRACT (kaynak HTML'den çıkarıldı — kararlı locatorlar)
                - input | selector: data-testid=login-username | label: Kullanıcı adı | required
                - input | selector: name=password | type: password
                """;

        GeneratedTestCase c = generator().buildObservedSmokeTest("http://localhost:8081", observed).orElseThrow();

        assertTrue(c.getTestContent().contains("By.cssSelector(\"[data-testid='login-username']\")"));
        assertTrue(c.getTestContent().contains("By.name(\"password\")"));
        assertTrue(c.getTestContent().contains("By.id(\"username\")"));
    }

    @Test
    public void renderedVisibleLocatorUsesExplicitWaitAndVisibilityAssertion() {
        String observed = """
                ## OBSERVED PAGE
                Gerçek <title>: Gerçek Sayfa
                ## OBSERVED RENDERED UI CONTRACT (izole tarayıcıda görünür DOM)
                - input | selector: id=search-keyword-header | label: Arama yap | type: text | required | state: visible
                ## OBSERVED UI CONTRACT (kaynak HTML'den çıkarıldı)
                - input | selector: id=hidden-template
                """;

        GeneratedTestCase c = generator().buildObservedSmokeTest("https://www.vodafone.com.tr/", observed).orElseThrow();

        String code = c.getTestContent();
        assertTrue(code.contains("ExpectedConditions.visibilityOfElementLocated(By.id(\"search-keyword-header\"))"), code);
        assertTrue(code.contains("new WebDriverWait(driver, Duration.ofSeconds(10))"), code);
        assertTrue(code.contains("assertTrue("), code);
        assertTrue(code.contains("assertFalse(driver.findElements(By.id(\"hidden-template\"))"), code);
    }

    @Test
    public void observedUserFlowProducesOnlyMeasuredClicksAndOutcomeAssertions() {
        String observed = """
                ## OBSERVED PAGE
                Gerçek <title>: Vodafone
                ## OBSERVED USER FLOW (kullanıcının istediği güvenli akış yürütüldü)
                1. tıkla: Ev İnterneti | locator: visible link text 'Ev İnterneti' | sonuç: URL=https://www.vodafone.com.tr/; menü açıldı
                2. tıkla: 5G RedBox | locator: visible link text '5G RedBox' | sonuç: URL=https://www.vodafone.com.tr/net/redbox; 5G RedBox Tarifeleri
                3. tıkla: Detayları göster | locator: visible .tarife-card containing '5G RedBox 500GB Paketi' -> link text 'Detayları göster' | sonuç: URL=https://www.vodafone.com.tr/net/redbox; detay görünür
                Akış sonu URL: https://www.vodafone.com.tr/net/redbox
                Akış sonu başlık: 5G Redbox | Vodafone
                """;

        GeneratedTestCase testCase = generator().buildObservedUserFlowTest(
                "https://www.vodafone.com.tr/", observed).orElseThrow();

        assertEquals("ObservedUserFlowTest", testCase.getTestName());
        assertTrue(testCase.isDeterministic());
        String code = testCase.getTestContent();
        assertTrue(code.contains("By.linkText(\"Ev İnterneti\")"), code);
        assertTrue(code.contains("By.linkText(\"5G RedBox\")"), code);
        assertTrue(code.contains("tarife-card"), code);
        assertTrue(code.contains("5G RedBox 500GB Paketi"), code);
        assertTrue(code.contains("assertEquals(\"https://www.vodafone.com.tr/net/redbox\", driver.getCurrentUrl()"), code);
        assertTrue(code.contains("assertEquals(\"5G Redbox | Vodafone\", driver.getTitle()"), code);
        var result = new GeneratedTestValidator().validate(TestFramework.SELENIUM,
                testCase.getFileName(), code);
        assertFalse(result.isInvalid(), result.error());
    }

    @Test
    public void unverifiableFlowLocatorDoesNotProducePartialTest() {
        String observed = """
                ## OBSERVED USER FLOW
                1. tıkla: Bilinmeyen | locator: css: .tahmin | sonuç: URL=http://x; değişti
                """;

        assertTrue(generator().buildObservedUserFlowTest("http://x", observed).isEmpty());
    }

    @Test
    public void verifiedFlowNeverAcceptsFreeformLlmTestCode() {
        LlmService llm = Mockito.mock(LlmService.class);
        GeneratedJavaTestProjectService files = Mockito.mock(GeneratedJavaTestProjectService.class);
        SeleniumTestGenerator flowGenerator = new SeleniumTestGenerator(llm, files, new GenerationLimit(0));
        TestGenerationRequest request = TestGenerationRequest.builder().testType(com.testgen.model.TestType.FRONTEND_WEB)
                .framework(TestFramework.SELENIUM).applicationUrl("https://example.test/")
                .additionalContext("""
                        ## OBSERVED USER FLOW
                        1. tıkla: Ürünler | locator: visible link text 'Ürünler' | sonuç: URL=https://example.test/products; ürünler
                        Akış sonu URL: https://example.test/products
                        """).build();

        List<GeneratedTestCase> produced = flowGenerator.generate(request);

        assertEquals(1, produced.size());
        assertEquals("ObservedUserFlowTest", produced.get(0).getTestName());
        verifyNoInteractions(llm);
    }
}
