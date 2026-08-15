package com.testgen.runner;

import com.testgen.config.OutboundUrlGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Runner ekranından girilen endpoint + request'i anında çalıştırır (ad-hoc koşum).
 * Test üretimi gerektirmez — hızlı endpoint doğrulaması ve keşif için kullanılır.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DirectRequestService {

    /** Yanıttan doğrulama kurallarını türetir — Runner'ın "Postman kadar faydalı" olma noktası. */
    private final ResponseAssertionDeriver assertionDeriver;

    /** SSRF kapısı: kullanıcının yazdığı adrese sunucunun kendi ağından istek atılıyor. */
    private final OutboundUrlGuard urlGuard;

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_BODY_CHARS = 100_000;
    private static final int MAX_REDIRECTS = 5;

    /**
     * Yönlendirmeler BİLEREK otomatik takip edilmiyor (NEVER). Otomatik takipte, izin
     * verilen bir host 302 ile metadata adresine yönlendirerek SSRF kapısını atlatabilir —
     * guard yalnızca ilk URL'yi görür. Bunun yerine her adım {@link #followRedirects}
     * içinde yeniden doğrulanarak elle takip edilir.
     */
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public DirectRunResult execute(DirectRunRequest req) {
        validate(req);

        String method = req.method() == null ? "GET" : req.method().toUpperCase(Locale.ROOT);
        int timeout = req.timeoutSeconds() != null ? Math.min(Math.max(req.timeoutSeconds(), 1), 120)
                                                   : DEFAULT_TIMEOUT_SECONDS;

        boolean hasBody = req.body() != null && !req.body().isBlank()
                && !method.equals("GET") && !method.equals("HEAD");

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(req.url().trim()))
                .timeout(Duration.ofSeconds(timeout))
                .method(method, hasBody
                        ? HttpRequest.BodyPublishers.ofString(req.body())
                        : HttpRequest.BodyPublishers.noBody());

        boolean contentTypeSet = false;
        if (req.headers() != null) {
            for (Map.Entry<String, String> h : req.headers().entrySet()) {
                String key = h.getKey().toLowerCase(Locale.ROOT);
                if (key.equals("host") || key.equals("content-length") || key.equals("connection")) {
                    continue; // JDK HttpClient'ın izin vermediği header'lar
                }
                builder.header(h.getKey(), h.getValue());
                if (key.equals("content-type")) {
                    contentTypeSet = true;
                }
            }
        }
        if (hasBody && !contentTypeSet) {
            builder.header("Content-Type", "application/json");
        }

        long start = System.currentTimeMillis();
        try {
            HttpResponse<String> response = followRedirects(builder.build());
            long latency = System.currentTimeMillis() - start;

            Map<String, String> respHeaders = new LinkedHashMap<>();
            response.headers().map().forEach((k, v) -> {
                if (!k.startsWith(":")) {
                    respHeaders.put(k, String.join(", ", v));
                }
            });

            String body = response.body();
            if (body != null && body.length() > MAX_BODY_CHARS) {
                body = body.substring(0, MAX_BODY_CHARS) + "\n…[kısaltıldı]";
            }

            log.info("Direkt koşum: {} {} → {} ({}ms)", method, req.url(), response.statusCode(), latency);
            DirectRunResult result = new DirectRunResult(
                    response.statusCode(), latency, respHeaders, body, null, java.util.List.of());
            // Gözlenen yanıttan aday doğrulamalar; kullanıcı düzenler, LLM de veri olarak kullanır
            return result.withAssertions(assertionDeriver.derive(result));

        } catch (com.testgen.config.BadRequestException e) {
            // Güvenlik reddi ağ hatası DEĞİLDİR ve yutulmamalıdır. Yönlendirme zincirinin
            // ortasında guard devreye girdiğinde, bunu genel hata sonucuna çevirmek
            // "hedefe ulaşılamadı" gibi görünür ve engellemenin BİLEREK yapıldığı bilgisi
            // kaybolur. Çağırana aynen iletilir; controller 400 + açıklayıcı mesaja çevirir.
            log.warn("Direkt koşum reddedildi (SSRF kapısı): {} {} → {}", method, req.url(), e.getMessage());
            throw e;
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Direkt koşum hatası: {} {} → {} ({}ms)", method, req.url(), message, latency);
            return new DirectRunResult(null, latency, Map.of(), null, message, java.util.List.of());
        }
    }

    /**
     * Yönlendirmeleri elle takip eder ve HER adımı SSRF kapısından geçirir.
     * Otomatik takip (Redirect.NORMAL) kapıyı atlatılabilir kılıyordu: guard yalnızca
     * kullanıcının yazdığı ilk URL'yi görür, 302 ile gidilen hedefi görmezdi.
     */
    private HttpResponse<String> followRedirects(HttpRequest initial)
            throws java.io.IOException, InterruptedException {
        HttpRequest current = initial;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpResponse<String> response = client.send(current, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 300 || status > 399) {
                return response;
            }
            String location = response.headers().firstValue("location").orElse(null);
            if (location == null || location.isBlank()) {
                return response; // Location yoksa yönlendirme takip edilemez; yanıtı olduğu gibi ver
            }
            // Göreli Location mutlak adrese çevrilmeli, aksi halde guard host'u göremez
            URI target = current.uri().resolve(location.trim());
            urlGuard.verify(target);

            HttpRequest.Builder next = HttpRequest.newBuilder(target)
                    .timeout(current.timeout().orElse(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS)));
            current.headers().map().forEach((k, values) -> {
                if (!RESTRICTED_HEADERS.contains(k.toLowerCase(Locale.ROOT))) {
                    values.forEach(v -> next.header(k, v));
                }
            });
            // Yönlendirmede gövde düşürülüp GET'e dönülür (tarayıcı davranışı, RFC 9110)
            next.method("GET", HttpRequest.BodyPublishers.noBody());
            current = next.build();
        }
        throw new java.io.IOException("Çok fazla yönlendirme (en fazla " + MAX_REDIRECTS + ")");
    }

    /** JDK HttpClient'ın uygulama tarafından set edilmesine izin vermediği header'lar. */
    private static final java.util.Set<String> RESTRICTED_HEADERS =
            java.util.Set.of("host", "content-length", "connection", "upgrade", "expect");

    /**
     * Doğrulama artık {@link OutboundUrlGuard}'a devredildi: şema/host kontrolüne ek olarak
     * hedef adres link-local ve bulut metadata aralıklarına karşı da denetlenir.
     */
    private void validate(DirectRunRequest req) {
        urlGuard.verify(req.url());
    }

    public record DirectRunRequest(
            String url,
            String method,
            Map<String, String> headers,
            String body,
            Integer timeoutSeconds
    ) {}

    /**
     * @param assertions gözlenen yanıttan türetilen aday doğrulamalar (bkz. {@link ResponseAssertionDeriver})
     */
    public record DirectRunResult(
            Integer status,
            long latencyMs,
            Map<String, String> headers,
            String body,
            String error,
            java.util.List<HttpAssertion> assertions
    ) {
        DirectRunResult withAssertions(java.util.List<HttpAssertion> derived) {
            return new DirectRunResult(status, latencyMs, headers, body, error, derived);
        }
    }
}
