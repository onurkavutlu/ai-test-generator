package com.testgen.parser;

import java.util.Map;

/**
 * Bir kaynaktan (Postman collection, HAR, GraphQL, SOAP) ayrıştırılmış tek istek.
 *
 * NEDEN YAPILANDIRILMIŞ ALANLAR: Önceden yalnızca {@code payloadDetails} adında
 * serbest metin alanı vardı ve iki farklı iş birden yapıyordu — hem LLM prompt'una
 * giden metin, hem de Comparer'ın header/body okuduğu veri taşıyıcısı. Comparer bu
 * yüzden metnin içinden JSON'u YENİDEN AYRIŞTIRIYORDU; prompt'u kısaltmak da
 * imkânsızdı çünkü ham JSON veri kaynağıydı.
 *
 * Artık ikisi ayrıldı: {@code headers}/{@code body} yapılandırılmış veriyi taşır,
 * {@code payloadDetails} yalnızca LLM'e giden kompakt özettir.
 *
 * @param payloadDetails LLM prompt'una giden kompakt özet (ham JSON değil)
 * @param headers        ayrıştırılmış istek başlıkları; hiç yoksa boş harita
 * @param body           istek gövdesi; yoksa null
 */
public record ParsedRequestDto(
        String name,
        String method,
        String url,
        String payloadDetails,
        Map<String, String> headers,
        String body
) {
    /** Header/body taşımayan kaynaklar (GraphQL, SOAP) için kısa yol. */
    public ParsedRequestDto(String name, String method, String url, String payloadDetails) {
        this(name, method, url, payloadDetails, Map.of(), null);
    }
}
