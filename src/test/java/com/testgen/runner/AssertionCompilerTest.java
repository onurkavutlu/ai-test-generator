package com.testgen.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Türetilen doğrulamaların koşulabilir koda derlenmesi.
 *
 * Kritik nokta: üretilen kod SÖZDİZİMİ AÇISINDAN GEÇERLİ olmalı. Bu oturumda ölçülen
 * iki yıkıcı hata tam buradaydı — Karate'de geçersiz adım tüm senaryoları düşürüyordu,
 * REST Assured'da var olmayan {@code timeLessThan} sınıfı hiç derletmiyordu.
 */
class AssertionCompilerTest {

    private final ResponseAssertionDeriver deriver = new ResponseAssertionDeriver(new ObjectMapper());

    private List<HttpAssertion> sample() {
        return deriver.derive(new DirectRequestService.DirectRunResult(
                200, 40, Map.of("Content-Type", "application/json"),
                "{\"status\":\"UP\",\"count\":3,\"items\":[{\"id\":1}]}", null, List.of()));
    }

    // ─── Karate ──────────────────────────────────────────────

    @Test
    @DisplayName("Karate adımları geçerli sözdiziminde üretilir")
    void producesValidKarateSteps() {
        List<String> steps = AssertionCompiler.toKarateSteps(sample());

        assertTrue(steps.get(0).startsWith("Then "), "ilk adım Then olmalı: " + steps.get(0));
        assertTrue(steps.subList(1, steps.size()).stream().allMatch(s -> s.startsWith("And ")),
                "sonraki adımlar And olmalı: " + steps);
        assertTrue(steps.contains("Then status 200"), steps.toString());
        assertTrue(steps.stream().anyMatch(s -> s.equals("And match response.status == '#string'")), steps.toString());
        assertTrue(steps.stream().anyMatch(s -> s.equals("And match response.items == '#[1]'")), steps.toString());
        assertTrue(steps.stream().anyMatch(s -> s.equals("And match response.items[0].id == '#number'")), steps.toString());
    }

    @Test
    @DisplayName("Üretilen Karate adımları doğrulayıcıdan geçer")
    void karateStepsPassValidator() {
        String feature = "Feature: F\n\n  Scenario: S\n    Given url 'http://x'\n    When method get\n"
                + String.join("\n", AssertionCompiler.toKarateSteps(sample()).stream()
                        .map(s -> "    " + s).toList()) + "\n";

        // Ölçülen arıza: geçersiz adım tüm senaryoları düşürüyordu. Denetim burada devreye girer.
        assertEquals(null, com.testgen.generator.GeneratedTestValidator.findUnmatchableStep(feature),
                "uretilen adimlar step-definition'a eslesmeli:\n" + feature);
    }

    @Test
    @DisplayName("JSON yolu Karate biçimine çevrilir")
    void convertsJsonPathForKarate() {
        assertEquals("response", AssertionCompiler.karatePath("$"));
        assertEquals("response.status", AssertionCompiler.karatePath("$.status"));
        assertEquals("response.items[0].id", AssertionCompiler.karatePath("$.items[0].id"));
    }

    // ─── REST Assured ────────────────────────────────────────

    @Test
    @DisplayName("REST Assured ifadeleri gerçek API'lerle üretilir")
    void producesValidRestAssuredStatements() {
        List<String> stmts = AssertionCompiler.toRestAssuredStatements(sample());

        assertTrue(stmts.contains(".statusCode(200)"), stmts.toString());
        // ValidatableResponse.timeLessThan YOKTUR — ölçülen derleme hatası buydu
        assertFalse(stmts.stream().anyMatch(s -> s.contains("timeLessThan")), stmts.toString());
        assertTrue(stmts.stream().anyMatch(s -> s.startsWith(".time(lessThan(")), stmts.toString());
        assertTrue(stmts.stream().anyMatch(s -> s.equals(".body(\"status\", instanceOf(String.class))")), stmts.toString());
        assertTrue(stmts.stream().anyMatch(s -> s.equals(".body(\"items.size()\", equalTo(1))")), stmts.toString());
    }

    @Test
    @DisplayName("JSON yolu GPath biçimine çevrilir")
    void convertsJsonPathForGPath() {
        assertEquals("status", AssertionCompiler.gpath("$.status"));
        assertEquals("items[0].id", AssertionCompiler.gpath("$.items[0].id"));
    }

    // ─── Kapalı assertion ────────────────────────────────────

    @Test
    @DisplayName("Kapatılan assertion derlenmez")
    void disabledAssertionsAreSkipped() {
        List<HttpAssertion> assertions = List.of(
                HttpAssertion.of(HttpAssertion.Type.STATUS, null,
                        HttpAssertion.Operator.EQUALS, "200", "durum"),
                HttpAssertion.of(HttpAssertion.Type.RESPONSE_TIME, null,
                        HttpAssertion.Operator.LESS_THAN, "500", "sure").disabled());

        assertEquals(List.of("Then status 200"), AssertionCompiler.toKarateSteps(assertions));
        assertEquals(List.of(".statusCode(200)"), AssertionCompiler.toRestAssuredStatements(assertions));
    }

    // ─── Gerçek bloğu gidiş-dönüşü ───────────────────────────

    @Test
    @DisplayName("Prompt gerçek bloğu assertion'lara geri okunur")
    void factsRoundTripBackToAssertions() {
        String facts = deriver.toPromptFacts(sample());

        List<HttpAssertion> parsed = AssertionCompiler.fromPromptFacts(
                "önceki bağlam...\n\n" + facts);

        assertTrue(parsed.stream().anyMatch(a -> a.type() == HttpAssertion.Type.STATUS
                && "200".equals(a.expected())));
        assertTrue(parsed.stream().anyMatch(a -> a.type() == HttpAssertion.Type.JSON_FIELD_TYPE
                && "$.status".equals(a.path()) && "#string".equals(a.expected())));
        assertTrue(parsed.stream().anyMatch(a -> a.type() == HttpAssertion.Type.JSON_ARRAY_SIZE
                && "$.items".equals(a.path()) && "1".equals(a.expected())));
        assertTrue(parsed.stream().anyMatch(a -> a.type() == HttpAssertion.Type.RESPONSE_TIME));
    }

    @Test
    @DisplayName("Gerçek bloğu yoksa boş liste döner")
    void noFactsBlockYieldsEmpty() {
        assertTrue(AssertionCompiler.fromPromptFacts("sadece ajan analizi").isEmpty());
        assertTrue(AssertionCompiler.fromPromptFacts(null).isEmpty());
    }
}
