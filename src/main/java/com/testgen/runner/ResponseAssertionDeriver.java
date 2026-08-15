package com.testgen.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Gerçek bir HTTP yanıtından doğrulama kuralları türetir — LLM KULLANMADAN.
 *
 * NEDEN DETERMİNİSTİK: Bu oturumdaki üç uçtan uca koşumda LLM'in yazdığı 30'dan
 * fazla test düştü; geçen testlerin tamamı gözlemden deterministik üretilenlerdi.
 * Uydurulan beklenti (auth'suz uçta 401, sağlıklı uçta 400) testleri toptan
 * düşürüyordu. Burada hiçbir değer tahmin edilmez: her assertion, gözlenen
 * yanıttan okunur, bu yüzden yakalama anında geçmesi garantidir.
 *
 * Postman farkı: orada assertion'ları elle yazarsınız, snippet'ler şablondur.
 * Burada aday liste gerçek yanıttan önceden doldurulur; kullanıcı yalnızca
 * istemediğini kapatır.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResponseAssertionDeriver {

    /** Gövdeden en fazla kaç alan assertion'ı üretilsin — liste okunabilir kalmalı. */
    private static final int MAX_FIELD_ASSERTIONS = 25;

    /** İç içe kaç seviye inilsin. Derin ağaçlarda liste hızla kullanılamaz hale gelir. */
    private static final int MAX_DEPTH = 2;

    /**
     * Süre eşiği gözlenen gecikmenin katı olarak konur — sabit bir sayı değil.
     * Eski deterministik üretim "responseTime < 10000" yazıyordu; 50 ms'lik bir uç
     * için bu eşik hiçbir şey doğrulamaz, 12 sn'lik bir uç için ise hep düşer.
     */
    private static final double TIME_TOLERANCE = 3.0;
    private static final long MIN_TIME_THRESHOLD_MS = 500;

    private final ObjectMapper objectMapper;

    /** Yanıttan aday doğrulama listesi. Hatalı koşumda boş liste döner. */
    public List<HttpAssertion> derive(DirectRequestService.DirectRunResult result) {
        List<HttpAssertion> assertions = new ArrayList<>();
        if (result == null || result.status() == null) {
            return assertions;
        }

        assertions.add(HttpAssertion.of(HttpAssertion.Type.STATUS, null,
                HttpAssertion.Operator.EQUALS, String.valueOf(result.status()),
                "Durum kodu " + result.status() + " olmalı"));

        contentType(result.headers()).ifPresent(ct -> assertions.add(
                HttpAssertion.of(HttpAssertion.Type.HEADER, "Content-Type",
                        HttpAssertion.Operator.CONTAINS, ct,
                        "Content-Type '" + ct + "' içermeli")));

        long threshold = Math.max(MIN_TIME_THRESHOLD_MS,
                Math.round(result.latencyMs() * TIME_TOLERANCE));
        assertions.add(HttpAssertion.of(HttpAssertion.Type.RESPONSE_TIME, null,
                HttpAssertion.Operator.LESS_THAN, String.valueOf(threshold),
                "Yanıt süresi " + threshold + " ms altında olmalı (gözlenen: "
                        + result.latencyMs() + " ms)"));

        assertions.addAll(bodyAssertions(result.body()));
        return assertions;
    }

    // ─────────────────────────────────────────────────────────
    // JSON gövdesi
    // ─────────────────────────────────────────────────────────

    private List<HttpAssertion> bodyAssertions(String body) {
        List<HttpAssertion> out = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return out;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            // JSON değil (HTML, düz metin, XML) — gövde doğrulaması üretilmez.
            log.debug("Yanıt JSON değil, gövde assertion'ı üretilmedi: {}", e.getMessage());
            return out;
        }
        walk(root, "$", 0, out);
        return out;
    }

    private void walk(JsonNode node, String path, int depth, List<HttpAssertion> out) {
        if (out.size() >= MAX_FIELD_ASSERTIONS || node == null) {
            return;
        }

        if (node.isArray()) {
            out.add(HttpAssertion.of(HttpAssertion.Type.JSON_ARRAY_SIZE, path,
                    HttpAssertion.Operator.EQUALS, String.valueOf(node.size()),
                    path + " dizisi " + node.size() + " eleman içermeli"));
            // Dizinin tamamı yerine ilk eleman örneklenir: 200 elemanlı bir yanıtta
            // her eleman için assertion üretmek listeyi kullanılamaz hale getirir.
            if (!node.isEmpty() && depth < MAX_DEPTH) {
                walk(node.get(0), path + "[0]", depth + 1, out);
            }
            return;
        }

        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext() && out.size() < MAX_FIELD_ASSERTIONS) {
                Map.Entry<String, JsonNode> field = fields.next();
                String childPath = path + "." + field.getKey();
                JsonNode value = field.getValue();

                if (value.isObject() || value.isArray()) {
                    if (depth < MAX_DEPTH) {
                        walk(value, childPath, depth + 1, out);
                    }
                    continue;
                }
                out.add(HttpAssertion.of(HttpAssertion.Type.JSON_FIELD_TYPE, childPath,
                        HttpAssertion.Operator.TYPE_IS, typeOf(value),
                        childPath + " alanı " + typeOf(value) + " tipinde olmalı"));
            }
        }
    }

    /**
     * Türetilen gerçekleri LLM prompt'una girecek kompakt bloğa çevirir.
     *
     * NEDEN HAM GÖVDE DEĞİL: Prompt'a ham JSON gövdesi koymak iki sorun üretiyordu —
     * (1) ölçümde bağlam 34.000 karaktere çıktı ve model penceresinde sessizce kırpıldı,
     * (2) model gövdeyi yanlış okuyup olmayan alanlara assertion yazdı. Gerçek listesi
     * hem çok daha kısa hem de tek anlamlı: hangi alan var, hangi tipte, dizi kaç eleman.
     */
    public String toPromptFacts(List<HttpAssertion> assertions) {
        if (assertions == null || assertions.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(
                "## OBSERVED FACTS (gerçek yanıttan türetildi — hepsi doğrulanmış)\n");
        for (HttpAssertion a : assertions) {
            switch (a.type()) {
                case STATUS -> sb.append("status: ").append(a.expected()).append('\n');
                case HEADER -> sb.append("header ").append(a.path()).append(": ")
                        .append(a.expected()).append('\n');
                case RESPONSE_TIME -> sb.append("responseTime < ").append(a.expected())
                        .append(" ms\n");
                case JSON_ARRAY_SIZE -> sb.append(a.path()).append(" : array[")
                        .append(a.expected()).append("]\n");
                case JSON_FIELD_TYPE, JSON_FIELD_EXISTS -> sb.append(a.path()).append(" : ")
                        .append(a.expected()).append('\n');
            }
        }
        sb.append("KURAL: Yukarıdaki listede OLMAYAN bir alan, status ya da header için assertion YAZMA.\n");
        return sb.toString();
    }

    /** Karate'nin fuzzy matcher biçimiyle uyumlu tip adı — derleme adımında doğrudan kullanılır. */
    private static String typeOf(JsonNode value) {
        if (value.isTextual()) return "#string";
        if (value.isNumber()) return "#number";
        if (value.isBoolean()) return "#boolean";
        if (value.isNull()) return "#null";
        return "#notnull";
    }

    private static java.util.Optional<String> contentType(Map<String, String> headers) {
        if (headers == null) {
            return java.util.Optional.empty();
        }
        return headers.entrySet().stream()
                .filter(e -> "content-type".equalsIgnoreCase(e.getKey()))
                .map(Map.Entry::getValue)
                .filter(v -> v != null && !v.isBlank())
                // "application/json; charset=utf-8" → "application/json"
                .map(v -> v.split(";")[0].trim())
                .findFirst();
    }
}
