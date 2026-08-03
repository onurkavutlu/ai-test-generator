package com.testgen.generator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class KarateTestGeneratorTest {

    @Test
    public void deterministicCapturedFeatureUsesObservedData() {
        String context = """
                ## OBSERVED RESPONSE (canlı koşumdan yakalandı)
                İstek        : GET http://localhost:8080/api/v1/tests/health
                Gözlenen Status: 200
                """;

        String feature = KarateTestGenerator.buildDeterministicCapturedFeature(context);

        assertTrue(feature.contains("Feature:"));
        assertTrue(feature.contains("* url 'http://localhost:8080/api/v1/tests/health'"));
        assertTrue(feature.contains("When method GET"));
        assertTrue(feature.contains("Then status 200"));
        assertTrue(feature.contains("@testCaseLLM"));
    }

    @Test
    public void deterministicCapturedFeatureFallsBackToDefaultsOnMissingContext() {
        String feature = KarateTestGenerator.buildDeterministicCapturedFeature(null);
        assertTrue(feature.contains("Feature:"));
        assertTrue(feature.contains("Then status 200"));
    }

    @Test
    public void validatorRejectsKnownLlmSyntaxErrors() {
        // llama'nın canlıda ürettiği geçersiz kalıplar
        String badUrlEquals = "Feature: X\n  Background:\n    * url = 'http://x'\n  Scenario: s\n    Then status 200";
        String badDefUrl = "Feature: X\n  Background:\n    * def url = 'http://x'\n  Scenario: s\n    Then status 200";
        String noFeature = "markdown\n## API\n";
        assertFalse(KarateTestGenerator.looksLikeValidCapturedFeature(badUrlEquals));
        assertFalse(KarateTestGenerator.looksLikeValidCapturedFeature(badDefUrl));
        assertFalse(KarateTestGenerator.looksLikeValidCapturedFeature(noFeature));

        String valid = "Feature: X\n  Background:\n    * url 'http://x'\n  Scenario: s\n    When method GET\n    Then status 200";
        assertTrue(KarateTestGenerator.looksLikeValidCapturedFeature(valid));

        // Deterministik fallback kendi doğrulamasından geçmeli
        assertTrue(KarateTestGenerator.looksLikeValidCapturedFeature(
                KarateTestGenerator.buildDeterministicCapturedFeature("İstek : GET http://x\nGözlenen Status: 200")));
    }

    @Test
    public void deterministicCapturedFeatureHandlesNonGetAndOtherStatus() {
        String context = "İstek : POST http://api.example.com/v1/pets\nGözlenen Status: 201";
        String feature = KarateTestGenerator.buildDeterministicCapturedFeature(context);
        assertTrue(feature.contains("When method POST"));
        assertTrue(feature.contains("Then status 201"));
        assertTrue(feature.contains("'http://api.example.com/v1/pets'"));
    }
}
