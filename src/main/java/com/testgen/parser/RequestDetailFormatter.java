package com.testgen.parser;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Ayrıştırılan isteği LLM'e verilecek KOMPAKT bir özete çevirir.
 *
 * NEDEN VAR — ölçülen sorun: Ayrıştırıcılar prompt'a ham JSON'un tamamını
 * ({@code node.toPrettyString()}) koyuyordu. Postman'de bu, isteğin tüm
 * meta verisini (auth blokları, description, Postman'e özgü {@code options})
 * getiriyordu; HAR'da ise istek VE yanıtın tamamını — başlıklar, çerezler,
 * zamanlama bilgileri, tam gövde dahil. Gerçek bir HAR girdisi onlarca KB'dır.
 *
 * Bu, ölçülmüş bir arızayı tetikliyordu: prompt bağlam penceresini aşınca Ollama
 * fazlasını sessizce kırpıyor ve modelin gördüğü talimat düşüyor. Kompakt özet
 * hem sığar hem de modelin işine yaramayan meta veriyi taşımaz.
 */
final class RequestDetailFormatter {

    /** Gövde bu uzunlukta kesilir — şekli anlamak için yeterli, prompt'u şişirmez. */
    private static final int BODY_LIMIT = 600;

    /** En fazla kaç header taşınsın — anlamlı olanlar (auth, content-type) baştadır. */
    private static final int HEADER_LIMIT = 8;

    private RequestDetailFormatter() {}

    /**
     * Postman collection isteği → kompakt özet.
     * Postman'in {@code header} dizisi ve {@code body.raw} alanı okunur; gerisi atılır.
     */
    static String fromPostman(JsonNode requestNode, String method, String url) {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(url).append('\n');

        JsonNode headers = requestNode.get("header");
        if (headers != null && headers.isArray()) {
            int written = 0;
            for (JsonNode h : headers) {
                if (written >= HEADER_LIMIT) break;
                if (h.path("disabled").asBoolean(false)) continue;
                String key = h.path("key").asText("");
                if (key.isBlank()) continue;
                sb.append("header ").append(key).append(": ").append(h.path("value").asText("")).append('\n');
                written++;
            }
        }

        String body = requestNode.path("body").path("raw").asText("");
        if (!body.isBlank()) {
            sb.append("body:\n").append(truncate(body)).append('\n');
        }
        return sb.toString();
    }

    /**
     * HAR girdisi → kompakt özet.
     * İstek başlıkları ve gövdesi ile YANITIN status ve gövde örneği alınır;
     * zamanlama, çerez ve tarayıcıya özgü alanlar atılır.
     */
    static String fromHar(JsonNode request, JsonNode response, String method, String url) {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(url).append('\n');

        appendHarHeaders(sb, request.get("headers"), "header ");

        String requestBody = request.path("postData").path("text").asText("");
        if (!requestBody.isBlank()) {
            sb.append("body:\n").append(truncate(requestBody)).append('\n');
        }

        sb.append("gözlenen status: ").append(response.path("status").asInt(0)).append('\n');
        String responseBody = response.path("content").path("text").asText("");
        if (!responseBody.isBlank()) {
            sb.append("gözlenen yanıt:\n").append(truncate(responseBody)).append('\n');
        }
        return sb.toString();
    }

    private static void appendHarHeaders(StringBuilder sb, JsonNode headers, String prefix) {
        if (headers == null || !headers.isArray()) {
            return;
        }
        int written = 0;
        for (JsonNode h : headers) {
            if (written >= HEADER_LIMIT) break;
            String name = h.path("name").asText("");
            // Tarayıcının eklediği sözde-başlıklar (:method, :authority) teste taşınmaz
            if (name.isBlank() || name.startsWith(":")) continue;
            sb.append(prefix).append(name).append(": ").append(h.path("value").asText("")).append('\n');
            written++;
        }
    }

    // ─────────────────────────────────────────────────────────
    // Yapılandırılmış çıkarım — Comparer bunları kullanır, metin ayrıştırmaz
    // ─────────────────────────────────────────────────────────

    /** Postman isteğinin etkin (disabled olmayan) başlıkları. */
    static java.util.Map<String, String> postmanHeaders(JsonNode requestNode) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        JsonNode headers = requestNode.get("header");
        if (headers == null || !headers.isArray()) {
            return out;
        }
        for (JsonNode h : headers) {
            if (h.path("disabled").asBoolean(false)) continue;
            String key = h.path("key").asText("");
            if (!key.isBlank()) {
                out.put(key, h.path("value").asText(""));
            }
        }
        return out;
    }

    /** Postman isteğinin ham gövdesi; yoksa null. */
    static String postmanBody(JsonNode requestNode) {
        String body = requestNode.path("body").path("raw").asText("");
        return body.isBlank() ? null : body;
    }

    /** HAR isteğinin başlıkları; tarayıcının sözde-başlıkları atılır. */
    static java.util.Map<String, String> harHeaders(JsonNode request) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        JsonNode headers = request.get("headers");
        if (headers == null || !headers.isArray()) {
            return out;
        }
        for (JsonNode h : headers) {
            String name = h.path("name").asText("");
            if (!name.isBlank() && !name.startsWith(":")) {
                out.put(name, h.path("value").asText(""));
            }
        }
        return out;
    }

    /** HAR isteğinin gövdesi; yoksa null. */
    static String harBody(JsonNode request) {
        String body = request.path("postData").path("text").asText("");
        return body.isBlank() ? null : body;
    }

    private static String truncate(String text) {
        String clean = text.strip();
        return clean.length() <= BODY_LIMIT
                ? clean
                : clean.substring(0, BODY_LIMIT) + "\n…[kısaltıldı]";
    }
}
