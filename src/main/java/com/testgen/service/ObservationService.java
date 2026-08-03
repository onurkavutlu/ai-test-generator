package com.testgen.service;

import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestType;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.parser.core.models.ParseOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
public class ObservationService {

    public static final String SECTION_TITLE = "## OBSERVED";

    private static final int PROBE_LIMIT = 3;
    private static final int BODY_SNIPPET = 800;
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(8);

    private static final Pattern CURL_METHOD = Pattern.compile("-X\\s+(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CURL_URL = Pattern.compile("(https?://[^\\s\"']+)");
    private static final Pattern HTML_TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
    private static final Pattern HTML_ID = Pattern.compile("<(input|button|select|textarea|form|a)\\b[^>]*\\bid=[\"']([^\"']+)[\"']");

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

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
                section = observeCurl(request.getRawPayload());
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
        log.info("🔭 Gözlem tamamlandı — gerçek veri bağlama eklendi ({} karakter)", section.length());
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
    // BACKEND: cURL güvenli koşum (yalnız GET/HEAD)
    // ─────────────────────────────────────────────────────────
    private String observeCurl(String rawPayload) {
        Matcher mu = CURL_URL.matcher(rawPayload);
        if (!mu.find()) return null;
        String url = mu.group(1);

        Matcher mm = CURL_METHOD.matcher(rawPayload);
        String method = mm.find() ? mm.group(1).toUpperCase(Locale.ROOT) : "GET";

        if (!method.equals("GET") && !method.equals("HEAD")) {
            return SECTION_TITLE + " NOTE\n"
                    + "İstek " + method + " (mutasyonlu) olduğu için otomatik gözlem koşumu YAPILMADI. "
                    + "Gerçek yanıtla üretim için Runner ekranındaki 'Yanıttan Test Üret' akışını kullanın. "
                    + "Ajanlar: doğrulanmamış varsayımları 'varsayım' olarak işaretleyin.";
        }

        HttpResponse<String> resp = get(url);
        if (resp == null) {
            return SECTION_TITLE + " NOTE\nHedef (" + url + ") gözlem sırasında erişilemedi — status/alan uydurmayın.";
        }
        return SECTION_TITLE + " RESPONSE (canlı koşumdan yakalandı — assertion'lar BU yanıta göre)\n"
                + "İstek        : " + method + " " + url + "\n"
                + "Gözlenen Status: " + resp.statusCode() + "\n"
                + "Gözlenen Body (kısaltılmış):\n" + snippet(resp.body()) + "\n"
                + "KURAL: Gözlenmeyen alan/status/header UYDURMA; auth izi yoksa 401/403 senaryosu YAZMA.";
    }

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
            }
        }
        if (probed == 0) return null;
        sb.append("KURAL: Yukarıdaki gözlenen status/body örnekleri kontratın gerçeğidir; ")
          .append("assertion'ları bunlara hizala, gözlenmeyeni uydurma.");
        return sb.toString();
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

    private static String snippet(String body) {
        if (body == null || body.isBlank()) return "(boş)";
        String clean = body.strip();
        return clean.length() > BODY_SNIPPET ? clean.substring(0, BODY_SNIPPET) + "…[kısaltıldı]" : clean;
    }
}
