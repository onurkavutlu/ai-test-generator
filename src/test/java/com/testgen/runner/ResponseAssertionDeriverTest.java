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
 * Gerçek yanıttan türetilen doğrulamalar — hiçbiri tahmin olmamalı.
 *
 * Bu testler, ölçülen sorunu bağlar: LLM'in uydurduğu beklentiler (auth'suz uçta 401,
 * sağlıklı uçta 400) testleri toptan düşürüyordu. Türetici yalnızca gözlenen değeri
 * yazar, bu yüzden ürettiği assertion'ların yakalama anında geçmesi garantidir.
 */
class ResponseAssertionDeriverTest {

    private final ResponseAssertionDeriver deriver = new ResponseAssertionDeriver(new ObjectMapper());

    private DirectRequestService.DirectRunResult result(int status, long latency, String body) {
        return new DirectRequestService.DirectRunResult(
                status, latency, Map.of("Content-Type", "application/json; charset=utf-8"),
                body, null, List.of());
    }

    @Test
    @DisplayName("Status, content-type ve süre eşiği gözlemden türetilir")
    void derivesStatusContentTypeAndTime() {
        var assertions = deriver.derive(result(200, 40, "{}"));

        assertTrue(assertions.stream().anyMatch(a -> a.type() == HttpAssertion.Type.STATUS
                && "200".equals(a.expected())));
        assertTrue(assertions.stream().anyMatch(a -> a.type() == HttpAssertion.Type.HEADER
                && "application/json".equals(a.expected())), "charset ayıklanmalı");
        // Süre eşiği sabit değil, gözlenenden türetilir (eski kod hep 10000 yazıyordu)
        assertTrue(assertions.stream().anyMatch(a -> a.type() == HttpAssertion.Type.RESPONSE_TIME
                && "500".equals(a.expected())), "40ms×3 taban değerin altında → taban uygulanır");
    }

    @Test
    @DisplayName("Süre eşiği yavaş uçta gözlenenin katı olur")
    void timeThresholdScalesWithObservedLatency() {
        var assertions = deriver.derive(result(200, 400, "{}"));

        assertTrue(assertions.stream().anyMatch(a -> a.type() == HttpAssertion.Type.RESPONSE_TIME
                && "1200".equals(a.expected())));
    }

    @Test
    @DisplayName("JSON alanlarının tipleri gözlemden çıkarılır")
    void derivesFieldTypesFromBody() {
        var assertions = deriver.derive(result(200, 10,
                "{\"status\":\"UP\",\"count\":3,\"ready\":true}"));

        assertTrue(has(assertions, "$.status", "#string"));
        assertTrue(has(assertions, "$.count", "#number"));
        assertTrue(has(assertions, "$.ready", "#boolean"));
    }

    @Test
    @DisplayName("Dizi boyutu ve ilk elemanın alanları türetilir")
    void derivesArraySizeAndSampleElement() {
        var assertions = deriver.derive(result(200, 10,
                "{\"items\":[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"b\"}]}"));

        assertTrue(assertions.stream().anyMatch(a -> a.type() == HttpAssertion.Type.JSON_ARRAY_SIZE
                && "$.items".equals(a.path()) && "2".equals(a.expected())));
        assertTrue(has(assertions, "$.items[0].id", "#number"));
        assertTrue(has(assertions, "$.items[0].name", "#string"));
    }

    @Test
    @DisplayName("JSON olmayan gövde için gövde assertion'ı üretilmez")
    void nonJsonBodyProducesNoFieldAssertions() {
        var assertions = deriver.derive(result(200, 10, "<html><body>merhaba</body></html>"));

        assertFalse(assertions.stream().anyMatch(a -> a.type() == HttpAssertion.Type.JSON_FIELD_TYPE));
        // Status ve süre yine de türetilir
        assertTrue(assertions.stream().anyMatch(a -> a.type() == HttpAssertion.Type.STATUS));
    }

    @Test
    @DisplayName("Hatalı koşumdan assertion üretilmez")
    void failedRunProducesNothing() {
        var failed = new DirectRequestService.DirectRunResult(
                null, 120, Map.of(), null, "Connection refused", List.of());

        assertTrue(deriver.derive(failed).isEmpty());
    }

    @Test
    @DisplayName("Liste sınırı aşılmaz — çok alanlı yanıt okunabilir kalır")
    void assertionCountIsBounded() {
        StringBuilder body = new StringBuilder("{");
        for (int i = 0; i < 100; i++) {
            body.append("\"f").append(i).append("\":").append(i).append(i < 99 ? "," : "");
        }
        body.append("}");

        assertTrue(deriver.derive(result(200, 10, body.toString())).size() <= 30);
    }

    @Test
    @DisplayName("LLM'e giden gerçek bloğu kompakt ve tek anlamlıdır")
    void promptFactsAreCompactAndExplicit() {
        var assertions = deriver.derive(result(200, 10, "{\"status\":\"UP\",\"items\":[1,2]}"));

        String facts = deriver.toPromptFacts(assertions);

        assertTrue(facts.contains("## OBSERVED FACTS"), facts);
        assertTrue(facts.contains("status: 200"), facts);
        assertTrue(facts.contains("$.status : #string"), facts);
        assertTrue(facts.contains("$.items : array[2]"), facts);
        assertTrue(facts.contains("OLMAYAN"), "uydurmayı yasaklayan kural bulunmalı");
        // Ham gövde dökümünden çok daha kısa olmalı — bağlam bütçesi ölçülen darboğazdı
        assertTrue(facts.length() < 500, "gerçek bloğu kompakt kalmalı: " + facts.length());
    }

    @Test
    @DisplayName("Assertion yoksa boş metin döner")
    void emptyAssertionsProduceEmptyFacts() {
        assertEquals("", deriver.toPromptFacts(List.of()));
    }

    private static boolean has(List<HttpAssertion> assertions, String path, String type) {
        return assertions.stream().anyMatch(a -> path.equals(a.path()) && type.equals(a.expected()));
    }
}
