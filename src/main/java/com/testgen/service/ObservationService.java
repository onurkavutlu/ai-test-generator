package com.testgen.service;

import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestType;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.parser.core.models.ParseOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * "Önce Gözlemle" adımı — üretim hattının ilk halkası.
 *
 * Ajanlar ve LLM tahmin etmesin diye hedef sistemden GERÇEK veri toplar:
 *  - BACKEND_API + Swagger  → parametresiz GET endpoint'leri güvenle problanır (yan etkisiz),
 *    gerçek status + body örnekleri bağlama yazılır.
 *  - BACKEND_API + cURL     → yalnız GET/HEAD otomatik koşulur; mutasyonlu istekler
 *    (POST/PUT/DELETE) bilinçli akış (Runner → Yanıttan Üret) gerektirir.
 *  - FRONTEND_WEB           → sayfa HTML'i çekilir; gerçek title, input/buton/link
 *    kimlikleri çıkarılır — selector uydurma biter.
 *
 * Tüm gözlemler best-effort'tur: erişilemeyen hedef üretimi durdurmaz,
 * yalnızca "gözlem yapılamadı" notu düşülür.
 */
@Slf4j
@Service
@lombok.RequiredArgsConstructor
public class ObservationService {

    /**
     * Problanan yanıtlardan doğrulama gerçeklerini türetir.
     *
     * NEDEN: Gözlem bloğuna ham gövde yazmak modele "şu alanlar var" demiyordu; model
     * gövdeyi yanlış okuyup olmayan alana assertion yazıyordu. Türetilmiş gerçekler
     * (ör. "$.status : #string") tek anlamlıdır ve aynı liste deterministik teste derlenir.
     */
    private final com.testgen.runner.ResponseAssertionDeriver assertionDeriver;

    /**
     * SSRF kapısı. Gözlem katmanı, kullanıcının verdiği Swagger/uygulama adresine
     * sunucunun kendi ağından istek atar ve yanıt gövdesini prompt'a — dolayısıyla
     * kullanıcıya gösterilen ajan raporuna — koyar. Runner ile aynı yüzey.
     */
    private final com.testgen.config.OutboundUrlGuard urlGuard;

    /**
     * cURL'ü kanonik isteğe çevirir: metot, başlıklar ve gövde.
     *
     * <p>ÖLÇÜLEN ARIZA: önceden cURL'den yalnızca URL çıkarılıyor, metot sadece
     * {@code -X} bayrağından okunuyordu. {@code -X} yazılmayan ama {@code --data}
     * taşıyan bir SOAP çağrısı GET sanıldı; başlıklar ve gövde yok sayılarak SOAP
     * ucuna boş bir GET atıldı, yanıt alınamadı ve ajanlar bunu "endpoint erişilemez"
     * diye okuyup analizin tamamını yanlış öncüle dayandırdı.
     */
    private final com.testgen.parser.CurlParser curlParser;

    public static final String SECTION_TITLE = "## OBSERVED";

    private static final int PROBE_LIMIT = 3;
    private static final int BODY_SNIPPET = 800;
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(8);

    private static final Pattern CURL_METHOD = Pattern.compile("-X\\s+(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CURL_URL = Pattern.compile("(https?://[^\\s\"']+)");
    private static final Pattern HTML_TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
    private static final Pattern HTML_ID = Pattern.compile("<(input|button|select|textarea|form|a)\\b[^>]*\\bid=[\"']([^\"']+)[\"']");

    /**
     * Yalnızca güvenilen yerel test ortamlarında kullanılacak geçici kaçış anahtarı.
     * Varsayılan kapalıdır; local profil, kurumsal CA truststore'a eklenene kadar açar.
     */
    @Value("${test-generator.observation.insecure-ssl:false}")
    private boolean insecureSsl;

    private HttpClient client = buildHttpClient(false);

    @PostConstruct
    void configureHttpClient() {
        client = buildHttpClient(insecureSsl);
        if (insecureSsl) {
            log.warn("⚠️  GÖZLEM SSL SERTİFİKA DOĞRULAMASI KAPALI — yalnızca yerel test ortamında kullanın");
        }
    }

    static HttpClient buildHttpClient(boolean insecureSsl) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL);

        if (!insecureSsl) {
            return builder.build();
        }

        try {
            X509TrustManager trustAll = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    // Geçici local profil seçeneği: istemci sertifikası doğrulanmaz.
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    // Geçici local profil seçeneği: sunucu sertifika zinciri doğrulanmaz.
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustAll}, new SecureRandom());
            return builder.sslContext(sslContext).build();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Güvensiz gözlem SSL istemcisi oluşturulamadı", e);
        }
    }

    /**
     * İsteğin hedefinden gözlem toplar ve additionalContext'e OBSERVED bölümü ekler.
     * Idempotent: bölüm zaten varsa (örn. Runner yanıt-temelli akışı) dokunmaz.
     */
    public String enrichWithObservations(TestGenerationRequest request) {
        String existing = request.getAdditionalContext() == null ? "" : request.getAdditionalContext();
        if (existing.contains(SECTION_TITLE)) {
            return existing;
        }

        String section;
        try {
            if (request.getTestType() == TestType.FRONTEND_WEB) {
                section = observePage(request.getApplicationUrl());
            } else if (request.getRawPayload() != null && !request.getRawPayload().isBlank()) {
                section = observeCurl(request.getRawPayload(), request);
            } else if (request.getSwaggerUrl() != null && !request.getSwaggerUrl().isBlank()) {
                section = observeSwagger(request.getSwaggerUrl());
            } else {
                return existing; // gözlemlenecek somut hedef yok (yalnız user story)
            }
        } catch (Exception e) {
            log.warn("Gözlem adımı başarısız (üretim devam ediyor): {}", e.getMessage());
            section = null;
        }

        if (section == null || section.isBlank()) {
            return existing;
        }
        // Eski log HER durumda "gerçek veri eklendi" diyordu — hiç istek gönderilmemiş
        // olsa bile. Bu, sorunu gizleyen bir yanlış beyandı: 197 karakterlik
        // "gözlemlenemedi" notu, gerçek gözlemle aynı satırla raporlanıyordu.
        if (isObserved(section)) {
            log.info("🔭 Gözlem tamamlandı — canlı yanıt bağlama eklendi ({} karakter)", section.length());
        } else {
            log.warn("⚠️  GÖZLEM YAPILAMADI — üretim ölçülmüş veriye dayanmıyor. Not: {}",
                    firstLine(section));
        }
        return existing.isBlank() ? section : existing + "\n\n" + section;
    }

    // ─────────────────────────────────────────────────────────
    // FRONTEND: gerçek sayfa yapısı
    // ─────────────────────────────────────────────────────────
    private String observePage(String url) {
        if (url == null || url.isBlank()) return null;
        HttpResponse<String> resp = get(url);
        if (resp == null) {
            return SECTION_TITLE + " PAGE\nSayfa gözlemi yapılamadı (" + url + " erişilemedi) — selector'lar için kullanıcı ipuçlarına güven, uydurma.";
        }
        String html = resp.body() == null ? "" : resp.body();

        Matcher t = HTML_TITLE.matcher(html);
        String title = t.find() ? t.group(1).trim().replaceAll("\\s+", " ") : "(title yok)";

        List<String> elements = new ArrayList<>();
        Matcher m = HTML_ID.matcher(html);
        while (m.find() && elements.size() < 25) {
            elements.add(m.group(1) + "#" + m.group(2));
        }

        StringBuilder sb = new StringBuilder(SECTION_TITLE + " PAGE (canlı çekildi — selector'lar GERÇEK)\n");
        sb.append("URL: ").append(url).append("\n");
        sb.append("HTTP Status: ").append(resp.statusCode()).append("\n");
        sb.append("Gerçek <title>: ").append(title).append("\n");
        sb.append(elements.isEmpty()
                ? "id'li form elemanı bulunamadı — yalnızca sayfa yükleme/başlık senaryoları yaz.\n"
                : "Gerçek elementler (tag#id): " + String.join(", ", elements) + "\n");
        sb.append("KURAL: Yalnızca yukarıda listelenen id'leri selector olarak kullan; listede olmayan selector UYDURMA.");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────
    // BACKEND: cURL — kullanıcının verdiği isteğin AYNISI koşulur
    // ─────────────────────────────────────────────────────────

    /**
     * Kullanıcının verdiği isteği <b>metoduna bakmaksızın</b> gönderir.
     *
     * <p><b>Neden metot ayrımı yok:</b> isteği kullanıcı seçti ve kendi eliyle buraya
     * yapıştırdı. "Bu isteğe test yaz" demenin, "bu isteği çalıştır" dışında bir anlamı
     * yoktur — Postman'de Send'e basmakla aynı şey. Bir dönem burada POST/DELETE için
     * onay kapısı vardı; hiçbir şeyi güvenli hâle getirmedi, yalnızca kullanıcının kendi
     * verdiği isteği gözlemlemesini engelledi ve üretim ölçümsüz kaldı.
     *
     * <p>Onay kuralı isteğin <b>metoduna</b> değil <b>kaynağına</b> bağlıdır ve doğru
     * yerde uygulanır: aracın kendi keşfettiği uçlar (Swagger tarama) yalnızca yan
     * etkisiz olarak problanır — bkz. {@code observeSwagger}. Orada kullanıcı o
     * çağrıları hiç istememiştir.
     */
    private String observeCurl(String rawPayload, TestGenerationRequest request) {
        var parsed = curlParser.parse(rawPayload);
        if (parsed == null) return null;

        String method = parsed.method();
        String url = parsed.url();

        Observed observed = send(parsed);
        if (!observed.ok()) {
            recordSkip(request, method + " " + url,
                    "İstek gönderildi ancak yanıt alınamadı: " + observed.error());
            return SECTION_TITLE + " NOTE\n"
                    + "Hedefe (" + method + " " + url + ") istek gönderildi ancak yanıt alınamadı: "
                    + observed.error() + "\n"
                    + "Ajanlar: status/alan UYDURMAYIN; hedefin iş davranışı hakkında çıkarım yapmayın.";
        }

        // Kanıtı isteğe iliştir: kullanıcı, üretilen testin neye dayandığını görebilsin.
        recordObservation(request, method + " " + url, observed);

        StringBuilder sb = new StringBuilder();
        sb.append(SECTION_TITLE).append(" RESPONSE (canlı koşumdan yakalandı — assertion'lar BU yanıta göre)\n");
        sb.append("İstek        : ").append(method).append(' ').append(url).append('\n');
        if (!parsed.headers().isEmpty()) {
            sb.append("Gönderilen Başlıklar: ").append(parsed.headers().keySet()).append('\n');
        }
        sb.append("Gözlenen Status: ").append(observed.status()).append('\n');
        // Süre GERÇEKTEN ölçüldü; SLA yalnızca bu değerden türetilebilir.
        sb.append("Gözlenen Süre  : ").append(observed.durationMs()).append(" ms\n");
        sb.append("Gözlenen Body (kısaltılmış):\n").append(snippet(observed.body())).append('\n');
        appendDerivedFacts(sb, observed.status(), observed.headers(), observed.body());
        sb.append("KURAL: Gözlenmeyen alan/status/header UYDURMA; auth izi yoksa 401/403 senaryosu YAZMA. ")
          .append("Ölçülmemiş SLA yazma — yukarıdaki süre dışında eşik üretme.");
        return sb.toString();
    }

    /** Ölçülen değerleri isteğe iliştirir — üretilen testin kanıtı budur. */
    private static void recordObservation(TestGenerationRequest request, String requestLine,
                                          Observed observed) {
        if (request == null) return;
        request.setObservedRequestLine(requestLine);
        request.setObservedStatus(observed.status());
        request.setObservedDurationMs(observed.durationMs());
        request.setObservedBody(observed.body());
        request.setObservedResponseHeaders(observed.headerLines());
        request.setObservedResponseCookies(observed.cookieLines());
        request.setObservedResponseSizeBytes(observed.responseSizeBytes());
        request.setObservedHttpVersion(observed.httpVersion());
        request.setObservationSkipReason(null);
        request.setObservedAt(java.time.LocalDateTime.now());
    }

    /** Gözlem yapılamadıysa NEDENİ saklanır; sessizce boş bırakılmaz. */
    private static void recordSkip(TestGenerationRequest request, String requestLine, String reason) {
        if (request == null) return;
        request.setObservedRequestLine(requestLine);
        request.setObservedStatus(null);
        request.setObservedDurationMs(null);
        request.setObservedBody(null);
        request.setObservedResponseHeaders(null);
        request.setObservedResponseCookies(null);
        request.setObservedResponseSizeBytes(null);
        request.setObservedHttpVersion(null);
        request.setObservationSkipReason(reason);
        request.setObservedAt(java.time.LocalDateTime.now());
    }

    /** Gözlem sonucu: gerçekten ölçülen değerler ya da hatanın kendisi. */
    private record Observed(boolean ok, String error, int status, long durationMs,
                            java.util.Map<String, String> headers, String body,
                            String headerLines, String cookieLines,
                            long responseSizeBytes, String httpVersion) {
        static Observed fail(String error) {
            return new Observed(false, error, 0, 0, java.util.Map.of(), null,
                    "", "", 0, null);
        }
    }

    /**
     * Kullanıcının verdiği isteğin AYNISINI gönderir: aynı metot, aynı başlıklar,
     * aynı gövde. Süre ölçülür.
     */
    private Observed send(com.testgen.parser.ParsedRequestDto parsed) {
        try {
            urlGuard.verify(parsed.url());

            HttpRequest.BodyPublisher publisher = (parsed.body() == null || parsed.body().isBlank())
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(parsed.body());

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(parsed.url()))
                    .timeout(PROBE_TIMEOUT)
                    .method(parsed.method(), publisher);

            parsed.headers().forEach((k, v) -> {
                // HttpClient bazı başlıkları kendisi yönetir; kullanıcının verdiğini
                // eklemeye çalışmak IllegalArgumentException fırlatır.
                if (!RESTRICTED_HEADERS.contains(k.toLowerCase(Locale.ROOT))) {
                    builder.header(k, v);
                }
            });

            long start = System.currentTimeMillis();
            HttpResponse<String> resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            long durationMs = System.currentTimeMillis() - start;

            java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
            resp.headers().map().forEach((k, v) -> {
                if (!k.startsWith(":")) headers.put(k, String.join(", ", v));
            });

            String headerLines = resp.headers().map().entrySet().stream()
                    .filter(e -> !e.getKey().startsWith(":"))
                    .flatMap(e -> e.getValue().stream().map(v -> e.getKey() + ": " + v))
                    .collect(java.util.stream.Collectors.joining("\n"));
            String cookieLines = String.join("\n", resp.headers().allValues("set-cookie"));
            String responseBody = resp.body();
            long responseSize = responseBody == null ? 0
                    : responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

            return new Observed(true, null, resp.statusCode(), durationMs, headers, responseBody,
                    headerLines, cookieLines, responseSize, resp.version().name());

        } catch (Exception e) {
            String reason = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            log.warn("Gözlem isteği başarısız ({} {}): {}", parsed.method(), parsed.url(), reason);
            return Observed.fail(reason);
        }
    }

    private static final java.util.Set<String> RESTRICTED_HEADERS = java.util.Set.of(
            "connection", "content-length", "expect", "host", "upgrade");

    // ─────────────────────────────────────────────────────────
    // BACKEND: Swagger'dan güvenli endpoint probları
    // ─────────────────────────────────────────────────────────
    private String observeSwagger(String swaggerUrl) {
        OpenAPI openAPI;
        try {
            openAPI = new OpenAPIParser().readLocation(swaggerUrl, null, new ParseOptions()).getOpenAPI();
        } catch (Exception e) {
            return null;
        }
        if (openAPI == null || openAPI.getPaths() == null) return null;

        String baseUrl = resolveBaseUrl(openAPI, swaggerUrl);
        if (baseUrl == null) return null;

        StringBuilder sb = new StringBuilder(SECTION_TITLE + " API (parametresiz GET endpoint'leri canlı problandı — yan etkisiz)\n");
        sb.append("Base URL: ").append(baseUrl).append("\n");
        int probed = 0;
        for (var entry : openAPI.getPaths().entrySet()) {
            if (probed >= PROBE_LIMIT) break;
            String path = entry.getKey();
            PathItem item = entry.getValue();
            if (item.getGet() == null || path.contains("{")) continue;

            HttpResponse<String> resp = get(joinUrl(baseUrl, path));
            probed++;
            if (resp == null) {
                sb.append("- GET ").append(path).append(" → erişilemedi\n");
            } else {
                sb.append("- GET ").append(path).append(" → ").append(resp.statusCode())
                        .append(" | body: ").append(snippet(resp.body()).replace("\n", " ")).append("\n");
                // Türetilmiş gerçekler: hangi alan var, hangi tipte. Deterministik test
                // üretimi bu satırlardan derlenir (bkz. ObservedApiTestBuilder).
                appendDerivedFacts(sb, resp);
            }
        }
        if (probed == 0) return null;
        sb.append("KURAL: Yukarıdaki gözlenen status/body örnekleri kontratın gerçeğidir; ")
          .append("assertion'ları bunlara hizala, gözlenmeyeni uydurma.");
        return sb.toString();
    }

    /** Problanan yanıttan türetilen gerçekleri endpoint satırının altına girintili yazar. */
    private void appendDerivedFacts(StringBuilder sb, HttpResponse<String> resp) {
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        resp.headers().map().forEach((k, v) -> {
            if (!k.startsWith(":")) {
                headers.put(k, String.join(", ", v));
            }
        });
        appendDerivedFacts(sb, resp.statusCode(), headers, resp.body());
    }

    private void appendDerivedFacts(StringBuilder sb, int status,
                                    java.util.Map<String, String> headers, String body) {
        var result = new com.testgen.runner.DirectRequestService.DirectRunResult(
                status, 0, headers, body, null, java.util.List.of());

        for (var a : assertionDeriver.derive(result)) {
            // Süre gerçeği burada anlamsız: gözlem gecikmesi ölçülmüyor (0 verildi)
            if (a.type() == com.testgen.runner.HttpAssertion.Type.RESPONSE_TIME) {
                continue;
            }
            sb.append("    ").append(factLine(a)).append('\n');
        }
    }

    private static String factLine(com.testgen.runner.HttpAssertion a) {
        return switch (a.type()) {
            case STATUS -> "status: " + a.expected();
            case HEADER -> "header " + a.path() + ": " + a.expected();
            case JSON_ARRAY_SIZE -> a.path() + " : array[" + a.expected() + "]";
            default -> a.path() + " : " + a.expected();
        };
    }

    private String resolveBaseUrl(OpenAPI openAPI, String swaggerUrl) {
        try {
            if (openAPI.getServers() != null && !openAPI.getServers().isEmpty()) {
                String server = openAPI.getServers().get(0).getUrl();
                if (server != null && server.startsWith("http")) {
                    return server;
                }
                // Göreli server ("/api/v3" gibi) → swagger host'una eklenir
                URI su = URI.create(swaggerUrl);
                if (server != null && server.startsWith("/")) {
                    return su.getScheme() + "://" + su.getAuthority() + server;
                }
            }
            URI su = URI.create(swaggerUrl);
            return su.getScheme() + "://" + su.getAuthority();
        } catch (Exception e) {
            return null;
        }
    }

    private static String joinUrl(String base, String path) {
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return b + (path.startsWith("/") ? path : "/" + path);
    }

    private HttpResponse<String> get(String url) {
        try {
            // Prob atmadan ÖNCE kapıdan geçir. Gözlem best-effort olduğu için reddedilen
            // adres üretimi durdurmaz; null dönüp "gözlem yapılamadı" notuna düşer.
            urlGuard.verify(url);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(PROBE_TIMEOUT)
                    .header("Accept", "application/json, text/html;q=0.9, */*;q=0.8")
                    .GET()
                    .build();
            return client.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.debug("Gözlem probu başarısız ({}): {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * Bölüm gerçek bir ölçüm mü taşıyor, yoksa "yapılamadı" notu mu?
     * Üretim akışı bu ayrımı bilmeden ilerlerse ajanlar notu veriymiş gibi okur.
     */
    public static boolean isObserved(String section) {
        if (section == null || section.isBlank()) return false;
        return section.startsWith(SECTION_TITLE + " RESPONSE")
                || section.startsWith(SECTION_TITLE + " API")
                || (section.startsWith(SECTION_TITLE + " PAGE")
                    && !section.contains("Sayfa gözlemi yapılamadı"));
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        String head = i < 0 ? s : s.substring(0, i);
        int j = s.indexOf('\n', i + 1);
        return j < 0 ? head : head + " " + s.substring(i + 1, j);
    }

    private static String snippet(String body) {
        if (body == null || body.isBlank()) return "(boş)";
        String clean = body.strip();
        return clean.length() > BODY_SNIPPET ? clean.substring(0, BODY_SNIPPET) + "…[kısaltıldı]" : clean;
    }
}
