package com.testgen.generator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CodeCleanerTest {

    @Test
    public void testCleanFeatureContentInjectsTag() {
        String raw = "```gherkin\n" +
                "Feature: Get pet by id\n" +
                "  Scenario: Get pet\n" +
                "    Given url 'https://fakerestapi.azurewebsites.net/api/v1'\n" +
                "```";

        String cleaned = CodeCleaner.cleanFeatureContent(raw);

        assertTrue(cleaned.startsWith("@testCaseLLM\nFeature:"));
        assertTrue(cleaned.contains("@testCaseLLM\n  Scenario:"));
    }

    @Test
    public void testInjectTestCaseLlmTagAddsIfMissing() {
        String content = "Feature: Test Feature\n" +
                "  Scenario: Test Scenario\n" +
                "    Then status 200\n" +
                "  Scenario Outline: Test Outline\n" +
                "    Then status <status>";

        String injected = CodeCleaner.injectTestCaseLlmTag(content);

        assertTrue(injected.contains("@testCaseLLM\nFeature: Test Feature"));
        assertTrue(injected.contains("@testCaseLLM\n  Scenario: Test Scenario"));
        assertTrue(injected.contains("@testCaseLLM\n  Scenario Outline: Test Outline"));
    }

    @Test
    public void testInjectTestCaseLlmTagDoesNotDuplicate() {
        String content = "@testCaseLLM\n" +
                "Feature: Test Feature\n" +
                "  @testCaseLLM\n" +
                "  Scenario: Test Scenario\n" +
                "    Then status 200";

        String injected = CodeCleaner.injectTestCaseLlmTag(content);

        // Hem Feature hem de Scenario için sadece 1 kez bulunmalı, çoğaltılmamalı
        int featureTagCount = countOccurrences(injected, "@testCaseLLM\nFeature:");
        int scenarioTagCount = countOccurrences(injected, "@testCaseLLM\n  Scenario:");

        assertEquals(1, featureTagCount);
        assertEquals(1, scenarioTagCount);
    }

    @Test
    public void testInjectTestCaseLlmTagPreservesOtherTags() {
        String content = "Feature: Test Feature\n" +
                "  @smoke @regression\n" +
                "  Scenario: Test Scenario\n" +
                "    Then status 200";

        String injected = CodeCleaner.injectTestCaseLlmTag(content);

        assertTrue(injected.contains("@testCaseLLM\n  @smoke @regression\n  Scenario: Test Scenario"));
    }

    @Test
    public void testCleanFeatureContentStripsLeadingProse() {
        // Self-healing yanıtlarında LLM fence kullanmadan açıklama metniyle başlayabiliyor
        String raw = "Hatanın kök nedeni tag kullanımıdır.\n" +
                "Aşağıdaki düzeltilmiş kodu görüntüleyelim:\n\n" +
                "Feature: Health Endpoint Test\n" +
                "  Scenario: Kontrol\n" +
                "    Then status 200";

        String cleaned = CodeCleaner.cleanFeatureContent(raw);

        assertTrue(cleaned.startsWith("@testCaseLLM\nFeature:"));
        assertTrue(!cleaned.contains("kök nedeni"));
    }

    @Test
    public void testInjectTestCaseLlmTagRemovesDanglingTags() {
        // LLM tag'i senaryonun SONUNA koyarsa Karate parse hatası verir — temizlenmeli
        String content = "Feature: Test\n" +
                "  @testCaseLLM\n" +
                "  Scenario: Birinci\n" +
                "    Then status 200\n" +
                "    @testCaseLLM\n" +
                "\n" +
                "  Scenario: İkinci\n" +
                "    Then status 404";

        String injected = CodeCleaner.injectTestCaseLlmTag(content);

        // Dangling tag (boş satırdan önceki) silinmeli, İkinci senaryoya düzgün tag eklenmeli
        assertTrue(!injected.contains("Then status 200\n    @testCaseLLM"));
        assertTrue(injected.contains("@testCaseLLM\n  Scenario: İkinci"));
        assertTrue(injected.contains("@testCaseLLM\nFeature:"));
    }

    @Test
    public void testCleanJavaContentWithMarkdownAndComments() {
        String raw = "Sure, here is the fixed code:\n" +
                "```java\n" +
                "import org.junit.jupiter.api.Test;\n" +
                "public class SmokeTest {}\n" +
                "```\n" +
                "Hope this helps!";
        String cleaned = CodeCleaner.cleanJavaContent(raw);
        assertEquals("import org.junit.jupiter.api.Test;\npublic class SmokeTest {}", cleaned);
    }

    @Test
    public void testCleanJavaContentNoMarkdownButComments() {
        String raw = "Here is the code without backticks:\n" +
                "import org.junit.jupiter.api.Test;\n" +
                "public class SmokeTest {}";
        String cleaned = CodeCleaner.cleanJavaContent(raw);
        assertEquals("import org.junit.jupiter.api.Test;\npublic class SmokeTest {}", cleaned);
    }

    // ── Karate sözdizimi onarımı ─────────────────────────────

    @Test
    public void repairAddsMissingDefToAssignmentSteps() {
        // llama'nın canlıda ürettiği hata: def unutulunca Karate adımı tanımaz
        String raw = "Feature: X\n" +
                "  Background:\n" +
                "    * baseUrl = 'http://localhost:8080'\n" +
                "  Scenario: s\n" +
                "    Given url baseUrl\n" +
                "    Then status 200";

        String repaired = CodeCleaner.repairFeatureSyntax(raw);

        assertTrue(repaired.contains("* def baseUrl = 'http://localhost:8080'"));
        assertTrue(CodeCleaner.looksRunnableFeature(repaired));
    }

    @Test
    public void repairLeavesKarateKeywordStepsUntouched() {
        String raw = "Feature: X\n" +
                "  Scenario: s\n" +
                "    * def token = 'abc'\n" +
                "    * header Content-Type = 'application/json'\n" +
                "    * karate.configure('connectTimeout', 5000)\n" +
                "    Then status 200";

        String repaired = CodeCleaner.repairFeatureSyntax(raw);

        assertTrue(repaired.contains("* def token = 'abc'"));
        assertTrue(!repaired.contains("* def header"));
        assertTrue(!repaired.contains("* def karate"));
    }

    @Test
    public void repairCollapsesMultiLineJsBlockThatBreaksParser() {
        // Kapanış parantezi adımla aynı girintideyse Gherkin parser'ı kırılıyordu
        String raw = "Feature: X\n" +
                "  Scenario: s\n" +
                "    * def generatePayload = function() {\n" +
                "      return { \"key\": \"v\" }\n" +
                "    }\n" +
                "    Then status 200";

        String repaired = CodeCleaner.repairFeatureSyntax(raw);

        assertTrue(repaired.contains("* def generatePayload = function() { return { \"key\": \"v\" } }"));
        assertTrue(!repaired.contains("\n      return"));
    }

    @Test
    public void repairRemovesEqualsFromValueOnlyKarateSteps() {
        // "* url = 'x'" geçersiz; doğrusu "* url 'x'"
        String raw = "Feature: X\n" +
                "  Background:\n" +
                "    * url = 'http://localhost:8081'\n" +
                "  Scenario: s\n" +
                "    Then status 200";

        String repaired = CodeCleaner.repairFeatureSyntax(raw);

        assertTrue(repaired.contains("* url 'http://localhost:8081'"));
        assertTrue(!repaired.contains("url ="));
        assertTrue(CodeCleaner.looksRunnableFeature(repaired));
    }

    @Test
    public void repairKeepsOnlyTheFirstFeatureBlock() {
        // Karate dosya başına tek Feature kabul eder; LLM iki blok döndürebiliyor
        String raw = "Feature: Birinci\n" +
                "  Scenario: s1\n    Then status 200\n\n" +
                "#### 2. Ikinci senaryo\n" +
                "Feature: Ikinci\n" +
                "  Scenario: s2\n    Then status 404";

        String repaired = CodeCleaner.repairFeatureSyntax(raw);

        assertEquals(1, countOccurrences(repaired, "Feature:"));
        assertTrue(repaired.contains("Feature: Birinci"));
        assertTrue(!repaired.contains("Feature: Ikinci"));
    }


    @Test
    public void repairStripsTrailingProseAfterFeature() {
        // Self-healing yanitinda LLM feature'in SONUNA aciklama paragrafi ekliyordu →
        // Karate "mismatched input 'B' expecting <EOF>" ile tum dosyayi koslamiyordu
        String raw = "Feature: Health\n" +
                "  Scenario: s\n" +
                "    Given url 'http://x'\n" +
                "    Then status 200\n" +
                "\n" +
                "Bu duzeltilmis kodda, response.length ifadesinin yerine #arrayLength kullaniyoruz.\n" +
                "Ayrica idempotency testi ekledik.";

        String repaired = CodeCleaner.repairFeatureSyntax(raw);

        assertTrue(!repaired.contains("Bu duzeltilmis kodda"));
        assertTrue(!repaired.contains("Ayrica idempotency"));
        assertTrue(repaired.contains("Then status 200"));
        assertTrue(CodeCleaner.looksRunnableFeature(repaired));
    }

    @Test
    public void repairKeepsTablesDocstringsAndComments() {
        String raw = "Feature: X\n" +
                "  # yorum\n" +
                "  Scenario Outline: s\n" +
                "    Given url '<url>'\n" +
                "    Then status <code>\n" +
                "    Examples:\n" +
                "      | url | code |\n" +
                "      | /a  | 200  |\n";

        String repaired = CodeCleaner.repairFeatureSyntax(raw);

        assertTrue(repaired.contains("# yorum"));
        assertTrue(repaired.contains("Examples:"));
        assertTrue(repaired.contains("| url | code |"));
        assertTrue(repaired.contains("| /a  | 200  |"));
    }

    @Test
    public void runnableCheckRejectsMultipleFeatureBlocks() {
        String twoFeatures = "Feature: A\n  Scenario: s\n    Then status 200\n"
                + "Feature: B\n  Scenario: s2\n    Then status 200";
        assertTrue(!CodeCleaner.looksRunnableFeature(twoFeatures));
    }

    @Test
    public void runnableCheckRejectsMissingDefAndMissingScenario() {
        assertTrue(!CodeCleaner.looksRunnableFeature(
                "Feature: X\n  Scenario: s\n    * baseUrl = 'http://x'\n    Then status 200"));
        assertTrue(!CodeCleaner.looksRunnableFeature("Feature: X\n  (senaryo yok)"));
        assertTrue(CodeCleaner.looksRunnableFeature(
                "Feature: X\n  Scenario: s\n    * def baseUrl = 'http://x'\n    Then status 200"));
    }

    // ── Java testi normalizasyonu ────────────────────────────

    @Test
    public void normalizeAddsMissingJUnitImports() {
        String raw = "package com.testgen.generated;\n\n" +
                "import org.openqa.selenium.WebDriver;\n\n" +
                "public class DashboardPageTest {\n" +
                "    @BeforeEach\n    public void setup() {}\n" +
                "    @AfterEach\n    public void tearDown() {}\n" +
                "    @Test\n    public void smoke() { assertEquals(1, 1); }\n" +
                "}";

        String normalized = CodeCleaner.normalizeGeneratedJavaTest(raw, "DashboardPageTest");

        assertTrue(normalized.contains("import org.junit.jupiter.api.Test;"));
        assertTrue(normalized.contains("import org.junit.jupiter.api.BeforeEach;"));
        assertTrue(normalized.contains("import org.junit.jupiter.api.AfterEach;"));
        assertTrue(normalized.contains("import static org.junit.jupiter.api.Assertions.*;"));
        assertTrue(normalized.startsWith("package com.testgen.generated;"));
    }

    @Test
    public void normalizeDoesNotDuplicateExistingImports() {
        String raw = "package com.testgen.generated;\n\n" +
                "import org.junit.jupiter.api.Test;\n\n" +
                "public class SmokeTest {\n    @Test\n    public void t() {}\n}";

        String normalized = CodeCleaner.normalizeGeneratedJavaTest(raw, "SmokeTest");

        assertEquals(1, countOccurrences(normalized, "import org.junit.jupiter.api.Test;"));
    }

    @Test
    public void normalizeRewritesJUnit4AssertCalls() {
        String raw = "package com.testgen.generated;\n\n" +
                "public class SmokeTest {\n    @Test\n    public void t() {\n" +
                "        Assert.assertEquals(\"a\", \"a\");\n        Assert.assertTrue(true);\n    }\n}";

        String normalized = CodeCleaner.normalizeGeneratedJavaTest(raw, "SmokeTest");

        assertTrue(!normalized.contains("Assert."), "JUnit4 Assert.* çağrısı kalmamalı");
        assertTrue(normalized.contains("assertEquals(\"a\", \"a\");"));
        assertTrue(normalized.contains("import static org.junit.jupiter.api.Assertions.*;"));
    }

    // ── Selenium normalizasyonu ──────────────────────────────

    @Test
    public void seleniumNormalizeAddsDriverScaffoldingAndImports() {
        // LLM'in canlıda ürettiği hâl: driver hiç tanımlanmamış, By/WebDriver import yok
        String raw = "package com.testgen.generated;\n\n" +
                "public class DashboardTest {\n" +
                "    @Test\n    public void loads() {\n" +
                "        driver.get(\"http://localhost:8080\");\n" +
                "        assertTrue(driver.findElement(By.id(\"nav\")).isDisplayed());\n" +
                "    }\n}";

        String normalized = CodeCleaner.normalizeSeleniumTest(raw, "DashboardTest");

        assertTrue(normalized.contains("private WebDriver driver;"), "driver alanı eklenmeli");
        assertTrue(normalized.contains("DriverFactory.createDriver()"), "driver kurulumu eklenmeli");
        assertTrue(normalized.contains("driver.quit()"), "driver kapatılmalı");
        assertTrue(normalized.contains("import org.openqa.selenium.WebDriver;"));
        assertTrue(normalized.contains("import org.openqa.selenium.By;"));
        assertTrue(normalized.contains("import org.junit.jupiter.api.BeforeEach;"));
        assertTrue(normalized.contains("import org.junit.jupiter.api.AfterEach;"));
    }

    @Test
    public void seleniumNormalizeKeepsExistingDriverSetup() {
        String raw = "package com.testgen.generated;\n\n" +
                "import org.openqa.selenium.WebDriver;\n\n" +
                "public class DashboardTest {\n" +
                "    private WebDriver driver;\n" +
                "    @BeforeEach\n    public void setup() { driver = DriverFactory.createDriver(); }\n" +
                "    @Test\n    public void loads() { driver.get(\"http://x\"); }\n}";

        String normalized = CodeCleaner.normalizeSeleniumTest(raw, "DashboardTest");

        assertEquals(1, countOccurrences(normalized, "private WebDriver driver;"));
        assertEquals(1, countOccurrences(normalized, "import org.openqa.selenium.WebDriver;"));
    }

    @Test
    public void normalizeAddsHamcrestImportsWhenMatchersUsed() {
        String raw = "package com.testgen.generated;\n\n" +
                "public class SmokeTest {\n    @Test\n    public void t() {\n" +
                "        assertThat(driver.getTitle(), containsString(\"Dashboard\"));\n" +
                "        assertThat(driver.getTitle(), notNullValue());\n    }\n}";

        String normalized = CodeCleaner.normalizeGeneratedJavaTest(raw, "SmokeTest");

        assertTrue(normalized.contains("import static org.hamcrest.MatcherAssert.assertThat;"));
        assertTrue(normalized.contains("import static org.hamcrest.Matchers.*;"));
    }


    @Test
    public void inlineAnnotationsAlsoGetImports() {
        // LLM annotation'i ayni satira yazabiliyor: "@AfterEach public void ..."
        String raw = "package com.testgen.generated;\n\n" +
                "public class SmokeTest {\n" +
                "    @BeforeAll static void init() {}\n" +
                "    @AfterEach public void tearDown() {}\n" +
                "    @Test public void t() {}\n}";

        String normalized = CodeCleaner.normalizeGeneratedJavaTest(raw, "SmokeTest");

        assertTrue(normalized.contains("import org.junit.jupiter.api.BeforeAll;"));
        assertTrue(normalized.contains("import org.junit.jupiter.api.AfterEach;"));
        assertTrue(normalized.contains("import org.junit.jupiter.api.Test;"));
    }

    @Test
    public void restAssuredNormalizeAddsDslImports() {
        String raw = "package com.testgen.generated;\n\n" +
                "public class HealthTest {\n    @Test\n    public void t() {\n" +
                "        given().when().get(\"/health\").then().statusCode(200);\n" +
                "        RestAssured.baseURI = \"http://localhost:8080\";\n    }\n}";

        String normalized = CodeCleaner.normalizeRestAssuredTest(raw, "HealthTest");

        assertTrue(normalized.contains("import static io.restassured.RestAssured.*;"));
        assertTrue(normalized.contains("import io.restassured.RestAssured;"));
    }

    @Test
    public void seleniumNormalizeAddsFindByImportAndPageFactoryInit() {
        String raw = "package com.testgen.generated;\n\n" +
                "public class DashboardTest {\n" +
                "    @FindBy(id = \"nav\") private WebElement nav;\n" +
                "    @Test\n    public void t() { driver.get(\"http://x\"); }\n}";

        String normalized = CodeCleaner.normalizeSeleniumTest(raw, "DashboardTest");

        assertTrue(normalized.contains("import org.openqa.selenium.support.FindBy;"));
        assertTrue(normalized.contains("import org.openqa.selenium.support.PageFactory;"));
        assertTrue(normalized.contains("PageFactory.initElements(driver, this);"));
    }

    // ── Endpoint → test adı ──────────────────────────────────

    @Test
    public void buildTestNameStripsSchemeAndHostFromUrls() {
        // HAR/Collection akışı buraya tam URL geçiriyordu → ':' içeren geçersiz dosya adı
        assertEquals("Getapi_v1_tests_healthTest",
                CodeCleaner.buildTestName("http://localhost:8080/api/v1/tests/health", "GET"));
        assertEquals("Getapi_v1_tests_healthTest",
                CodeCleaner.buildTestName("/api/v1/tests/health", "GET"));
        assertEquals("Postapi_v1_suites_suiteId_casesTest",
                CodeCleaner.buildTestName("/api/v1/suites/{suiteId}/cases", "POST"));
        assertTrue(!CodeCleaner.buildTestName("http://localhost:8080/x", "GET").contains(":"));
    }

    private int countOccurrences(String text, String subStr) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(subStr, idx)) != -1) {
            count++;
            idx += subStr.length();
        }
        return count;
    }
}
