package com.testgen.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ayrıştırıcılar prompt'a ham JSON döküyordu; bu, ölçülmüş bir arızayı besliyordu —
 * prompt bağlam penceresini aşınca Ollama sessizce kırpıyor ve talimat düşüyor.
 */
class RequestDetailFormatterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ApiCollectionParser postman = new ApiCollectionParser(mapper);
    private final HarFileParser har = new HarFileParser(mapper);

    @Test
    @DisplayName("Postman: meta veri atılır, method/header/body korunur")
    void postmanKeepsSignalDropsNoise() throws Exception {
        String collection = """
                {"item":[{"name":"Kitap ekle","request":{
                  "method":"POST",
                  "url":{"raw":"https://api.test/books"},
                  "description":"Bu uzun aciklama teste hic gerekmiyor ama prompt'a giriyordu",
                  "auth":{"type":"bearer","bearer":[{"key":"token","value":"abc"}]},
                  "header":[{"key":"Content-Type","value":"application/json"},
                            {"key":"X-Kapali","value":"v","disabled":true}],
                  "body":{"mode":"raw","raw":"{\\"title\\":\\"Kitap\\"}",
                          "options":{"raw":{"language":"json"}}}
                }}]}
                """;

        List<ParsedRequestDto> parsed = postman.parse(collection);
        String details = parsed.get(0).payloadDetails();

        assertTrue(details.contains("POST"), details);
        assertTrue(details.contains("header Content-Type: application/json"), details);
        assertTrue(details.contains("\"title\":\"Kitap\""), details);

        // Prompt'a taşınmaması gerekenler
        assertFalse(details.contains("Bu uzun aciklama"), "description prompt'a girmemeli");
        assertFalse(details.contains("options"), "Postman'e ozgu meta veri girmemeli");
        assertFalse(details.contains("X-Kapali"), "kapali header girmemeli");
    }

    @Test
    @DisplayName("HAR: zamanlama ve çerezler atılır, istek + gözlenen yanıt korunur")
    void harKeepsSignalDropsNoise() {
        String harJson = """
                {"log":{"entries":[{
                  "startedDateTime":"2026-01-01T00:00:00Z",
                  "time":123.456,
                  "timings":{"blocked":1,"dns":2,"connect":3,"send":4,"wait":5,"receive":6},
                  "request":{"method":"GET","url":"https://api.test/books",
                    "cookies":[{"name":"oturum","value":"gizli-cerez-degeri"}],
                    "headers":[{"name":"Accept","value":"application/json"},
                               {"name":":authority","value":"api.test"}]},
                  "response":{"status":200,
                    "content":{"mimeType":"application/json","text":"{\\"id\\":1}"}}
                }]}}
                """;

        List<ParsedRequestDto> parsed = har.parse(harJson);
        String details = parsed.get(0).payloadDetails();

        assertTrue(details.contains("GET https://api.test/books"), details);
        assertTrue(details.contains("header Accept: application/json"), details);
        assertTrue(details.contains("gözlenen status: 200"), details);
        assertTrue(details.contains("\"id\":1"), details);

        assertFalse(details.contains("timings"), "zamanlama prompt'a girmemeli");
        assertFalse(details.contains("gizli-cerez-degeri"), "cerez degeri prompt'a girmemeli");
        assertFalse(details.contains(":authority"), "sozde-header girmemeli");
    }

    @Test
    @DisplayName("Uzun gövde kesilir — prompt bütçesi korunur")
    void longBodyIsTruncated() throws Exception {
        String hugeBody = "x".repeat(5000);
        String collection = mapper.writeValueAsString(mapper.readTree(("""
                {"item":[{"name":"Buyuk","request":{"method":"POST",
                  "url":{"raw":"https://api.test/x"},
                  "body":{"mode":"raw","raw":"BODY"}}}]}
                """).replace("BODY", hugeBody)));

        String details = postman.parse(collection).get(0).payloadDetails();

        assertTrue(details.contains("[kısaltıldı]"), "uzun govde kesilmeli");
        assertTrue(details.length() < 1000, "ozet kompakt kalmali: " + details.length());
    }
}
