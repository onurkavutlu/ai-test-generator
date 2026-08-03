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
                "    Given url 'https://petstore.swagger.io/v2'\n" +
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
