package com.testgen.parser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * cURL komutunu, diğer ayrıştırıcılarla aynı kanonik modele ({@link ParsedRequestDto})
 * çevirir. Postman koleksiyonu, HAR, GraphQL ve SOAP ayrıştırıcılarıyla aynı aileden.
 *
 * <p><b>Kapatılan arıza — ölçüldü, varsayılmadı.</b> Gözlem katmanı önceden cURL'den
 * yalnızca URL'i çıkarıp <b>her zaman parametresiz bir GET</b> atıyordu:
 * <ul>
 *   <li>Metot yalnızca {@code -X} bayrağından okunuyordu. Oysa cURL'de gövde verildiğinde
 *       {@code -X} yazmak gerekmez; {@code --data} tek başına POST demektir. Gerçek bir
 *       SOAP çağrısı bu yüzden GET olarak algılandı ve yanlış istek gönderildi.</li>
 *   <li>Başlıklar ({@code Content-Type}, {@code SOAPAction}, {@code Authorization}) ve
 *       gövde tamamen yok sayılıyordu.</li>
 * </ul>
 * Sonuç: gözlem "hedefe erişilemedi" diyor, ajanlar bunu "endpoint erişilemez" diye
 * okuyup tüm analizi yanlış öncül üzerine kuruyordu.
 */
@Slf4j
@Component
public class CurlParser {

    private static final Pattern URL_FLAG =
            Pattern.compile("--(?:location|url)\\s+(['\"]?)(https?://[^'\"\\s]+)\\1");
    private static final Pattern URL_BARE =
            Pattern.compile("(?<![-\\w])(['\"])(https?://[^'\"]+)\\1|(?<![-\\w=])(https?://[^'\"\\s]+)");
    private static final Pattern METHOD =
            Pattern.compile("(?:-X|--request)\\s+(['\"]?)([A-Za-z]+)\\1");
    private static final Pattern HEADER =
            Pattern.compile("(?:-H|--header)\\s+'([^']*)'|(?:-H|--header)\\s+\"([^\"]*)\"");
    private static final Pattern BODY_SQ =
            Pattern.compile("(?:-d|--data|--data-raw|--data-binary|--data-ascii)\\s+'(.*?)'(?=\\s*(?:\\\\\\s*)?(?:-{1,2}[A-Za-z]|$))",
                    Pattern.DOTALL);
    private static final Pattern BODY_DQ =
            Pattern.compile("(?:-d|--data|--data-raw|--data-binary|--data-ascii)\\s+\"(.*?)\"(?=\\s*(?:\\\\\\s*)?(?:-{1,2}[A-Za-z]|$))",
                    Pattern.DOTALL);
    private static final Pattern HEAD_FLAG =
            Pattern.compile("(?:^|\\s)(?:-I|--head)(?:\\s|$)");

    /** Yan etkisiz kabul edilen metotlar — kullanıcı onayı olmadan gözlemlenebilir. */
    private static final List<String> SAFE_METHODS = List.of("GET", "HEAD", "OPTIONS");

    public static boolean isSafeMethod(String method) {
        return method != null && SAFE_METHODS.contains(method.toUpperCase(Locale.ROOT));
    }

    /**
     * @return ayrıştırılmış istek; URL bulunamazsa {@code null}
     */
    public ParsedRequestDto parse(String raw) {
        if (raw == null || raw.isBlank()) return null;

        String url = extractUrl(raw);
        if (url == null) {
            log.debug("cURL içinde URL bulunamadı");
            return null;
        }

        Map<String, String> headers = extractHeaders(raw);
        String body = extractBody(raw);
        String method = extractMethod(raw, body);

        return new ParsedRequestDto(
                method + " " + url,
                method,
                url,
                buildPayloadDetails(method, url, headers, body),
                headers,
                body);
    }

    private static String extractUrl(String raw) {
        Matcher m = URL_FLAG.matcher(raw);
        if (m.find()) return m.group(2);
        Matcher b = URL_BARE.matcher(raw);
        if (b.find()) return b.group(2) != null ? b.group(2) : b.group(3);
        return null;
    }

    private static Map<String, String> extractHeaders(String raw) {
        Map<String, String> headers = new LinkedHashMap<>();
        Matcher m = HEADER.matcher(raw);
        while (m.find()) {
            String h = m.group(1) != null ? m.group(1) : m.group(2);
            if (h == null) continue;
            int colon = h.indexOf(':');
            if (colon > 0) {
                headers.put(h.substring(0, colon).trim(), h.substring(colon + 1).trim());
            }
        }
        return headers;
    }

    private static String extractBody(String raw) {
        Matcher m = BODY_SQ.matcher(raw);
        if (m.find()) return m.group(1);
        Matcher d = BODY_DQ.matcher(raw);
        if (d.find()) return d.group(1);
        return null;
    }

    /** cURL'ün yöntem seçme önceliğini uygular. */
    private static String extractMethod(String raw, String body) {
        Matcher m = METHOD.matcher(raw);
        if (m.find()) return m.group(2).toUpperCase(Locale.ROOT);
        if (HEAD_FLAG.matcher(raw).find()) return "HEAD";
        return (body != null && !body.isBlank()) ? "POST" : "GET";
    }

    /** Arayüzde kullanıcıya yöntemin neden seçildiğini açıklar. */
    public static String describeMethodDetection(String raw) {
        if (raw == null) return "cURL varsayılanı";
        if (METHOD.matcher(raw).find()) return "-X/--request ile açıkça belirtildi";
        if (HEAD_FLAG.matcher(raw).find()) return "-I/--head bulundu";
        if (extractBody(raw) != null) return "gövde parametresi bulundu; cURL varsayılanı POST";
        return "metot ve gövde yok; cURL varsayılanı GET";
    }

    private static String buildPayloadDetails(String method, String url,
                                              Map<String, String> headers, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("İstek : ").append(method).append(' ').append(url).append('\n');
        if (!headers.isEmpty()) {
            sb.append("Başlıklar:\n");
            headers.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append('\n'));
        }
        if (body != null && !body.isBlank()) {
            sb.append("Gövde:\n").append(body).append('\n');
        }
        return sb.toString();
    }
}
