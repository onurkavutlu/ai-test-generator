package com.testgen.service;

import com.sun.net.httpserver.HttpServer;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "Önce Gözlemle" adımını gerçek lokal HTTP sunucusuyla doğrular.
 */
public class ObservationServiceTest {

    private static HttpServer server;
    private static String baseUrl;
    private static final AtomicInteger mutationHits = new AtomicInteger();

    private final ObservationService service = new ObservationService(
            new com.testgen.runner.ResponseAssertionDeriver(new com.fasterxml.jackson.databind.ObjectMapper()),
            testGuard(),
            new com.testgen.parser.CurlParser());

    /** Testler localhost'a istek atıyor; guard'ın varsayılan (özel ağ serbest) hâli. */
    private static com.testgen.config.OutboundUrlGuard testGuard() {
        var g = new com.testgen.config.OutboundUrlGuard();
        g.setAllowPrivateNetworks(true);
        return g;
    }


    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/page", ex -> respond(ex, 200, "text/html",
                "<html><title>Gerçek Sayfa Başlığı</title><body>"
                        + "<form id=\"login-form\"><label for=\"username\">Kullanıcı adı</label>"
                        + "<input id=\"username\" data-testid=\"login-username\" required>"
                        + "<input name=\"password\" type=\"password\">"
                        + "<button id=\"submit-btn\">Gir</button></form></body></html>"));
        server.createContext("/api/pets", ex -> {
            if (!ex.getRequestMethod().equals("GET")) mutationHits.incrementAndGet();
            respond(ex, 200, "application/json", "{\"id\":7,\"name\":\"Pamuk\"}");
        });
        server.createContext("/api/credentials", ex -> {
            ex.getResponseHeaders().add("Authorization", "Bearer response-secret");
            ex.getResponseHeaders().add("X-Api-Key", "response-api-key");
            ex.getResponseHeaders().add("Set-Cookie", "session=response-cookie; HttpOnly");
            respond(ex, 200, "application/json", "{\"ok\":true}");
        });
        server.createContext("/openapi.json", ex -> respond(ex, 200, "application/json", """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "servers":[{"url":"%s"}],
                 "paths":{"/api/pets":{"get":{"responses":{"200":{"description":"ok"}}}},
                          "/api/pets/{id}":{"get":{"responses":{"200":{"description":"ok"}}}}}}
                """.formatted(baseUrlPlaceholder())));
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    // servers[].url'i gerçek porta çevirmek için: test başlarken port bilinmiyor,
    // bu yüzden swagger içinde göreli URL kullanıyoruz
    private static String baseUrlPlaceholder() { return "/"; }

    @AfterAll
    static void stop() { if (server != null) server.stop(0); }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status,
                                String type, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        ex.close();
    }

    @Test
    public void frontendObservationExtractsRealTitleAndSelectors() {
        TestGenerationRequest req = TestGenerationRequest.builder()
                .testType(TestType.FRONTEND_WEB).framework(TestFramework.SELENIUM)
                .applicationUrl(baseUrl + "/page").build();

        String ctx = service.enrichWithObservations(req);

        assertTrue(ctx.contains("## OBSERVED PAGE"));
        assertTrue(ctx.contains("Gerçek Sayfa Başlığı"));
        assertTrue(ctx.contains("input#username"));
        assertTrue(ctx.contains("button#submit-btn"));
        assertTrue(ctx.contains("## OBSERVED UI CONTRACT"));
        assertTrue(ctx.contains("selector: data-testid=login-username"));
        assertTrue(ctx.contains("label: Kullanıcı adı"));
        assertTrue(ctx.contains("selector: name=password"), ctx);
        assertTrue(ctx.contains("type: password"));
        assertTrue(ctx.contains("UYDURMA"));
    }

    @Test
    public void renderedDomContractIsAddedWithoutLeakingInputValues() {
        RenderedPageInspector inspector = url -> Optional.of(new RenderedPageInspector.RenderedPageObservation(
                "JavaScript Sonrası Başlık", url,
                List.of(new RenderedPageInspector.UiElement("input", "id", "live-search",
                        "Site içinde ara", "text", true))));
        ObservationService renderedService = new ObservationService(
                new com.testgen.runner.ResponseAssertionDeriver(new com.fasterxml.jackson.databind.ObjectMapper()),
                testGuard(), new com.testgen.parser.CurlParser(), inspector);
        TestGenerationRequest req = TestGenerationRequest.builder()
                .testType(TestType.FRONTEND_WEB).framework(TestFramework.SELENIUM)
                .applicationUrl(baseUrl + "/page").build();

        String ctx = renderedService.enrichWithObservations(req);

        assertTrue(ctx.contains("Gerçek <title>: JavaScript Sonrası Başlık"), ctx);
        assertTrue(ctx.contains("## OBSERVED RENDERED UI CONTRACT"), ctx);
        assertTrue(ctx.contains("selector: id=live-search"), ctx);
        assertTrue(ctx.contains("label: Site içinde ara"), ctx);
        assertTrue(ctx.contains("state: visible"), ctx);
        assertFalse(ctx.contains("value="), "Form değerleri gözlem sözleşmesine taşınmamalı");
    }

    @Test
    public void verifiedUserFlowIsAddedAsSeparateEvidenceSection() {
        RenderedPageInspector inspector = new RenderedPageInspector() {
            @Override
            public Optional<RenderedPageObservation> inspect(String url) {
                return Optional.empty();
            }

            @Override
            public Optional<UserFlowObservation> inspectUserFlow(String url, String userStory) {
                return Optional.of(new UserFlowObservation(url + "/redbox", "RedBox", List.of(
                        new FlowStep(1, "tıkla: Ev İnterneti", "visible link text 'Ev İnterneti'",
                                "URL=" + url + "; ana menü açıldı"),
                        new FlowStep(2, "tıkla: 5G RedBox", "visible link text '5G RedBox'",
                                "URL=" + url + "/redbox; 5G RedBox Tarifeleri")),
                        List.of("5G RedBox Tarifeleri")));
            }
        };
        ObservationService renderedService = new ObservationService(
                new com.testgen.runner.ResponseAssertionDeriver(new com.fasterxml.jackson.databind.ObjectMapper()),
                testGuard(), new com.testgen.parser.CurlParser(), inspector);
        TestGenerationRequest req = TestGenerationRequest.builder()
                .testType(TestType.FRONTEND_WEB).framework(TestFramework.SELENIUM)
                .applicationUrl(baseUrl + "/page").userStory("Ev İnterneti > 5G RedBox").build();

        String ctx = renderedService.enrichWithObservations(req);

        assertTrue(ctx.contains("## OBSERVED USER FLOW"), ctx);
        assertTrue(ctx.contains("visible link text 'Ev İnterneti'"), ctx);
        assertTrue(ctx.contains("5G RedBox Tarifeleri"), ctx);
        assertTrue(ctx.contains("Bu akışta görünmeyen adım"), ctx);
    }

    @Test
    public void browserEvidenceRemainsUsableWhenRawHttpFetchFails() {
        RenderedPageInspector inspector = new RenderedPageInspector() {
            @Override
            public Optional<RenderedPageObservation> inspect(String url) {
                return Optional.of(new RenderedPageObservation("Tarayıcı Başlığı", url,
                        List.of(new UiElement("a", "id", "menu", "Ev İnterneti", "", false))));
            }

            @Override
            public Optional<UserFlowObservation> inspectUserFlow(String url, String userStory) {
                return Optional.of(new UserFlowObservation(url, "Tarayıcı Başlığı", List.of(
                        new FlowStep(1, "tıkla: Ev İnterneti", "visible link text 'Ev İnterneti'", "URL=" + url)),
                        List.of()));
            }
        };
        ObservationService renderedService = new ObservationService(
                new com.testgen.runner.ResponseAssertionDeriver(new com.fasterxml.jackson.databind.ObjectMapper()),
                testGuard(), new com.testgen.parser.CurlParser(), inspector);
        TestGenerationRequest req = TestGenerationRequest.builder()
                .testType(TestType.FRONTEND_WEB).framework(TestFramework.SELENIUM)
                .applicationUrl("http://localhost:1/unreachable").userStory("Ev İnterneti").build();

        String ctx = renderedService.enrichWithObservations(req);

        assertTrue(ObservationService.isObserved(ctx), ctx);
        assertTrue(ctx.contains("kaynak HTTP alınamadı"), ctx);
        assertTrue(ctx.contains("## OBSERVED USER FLOW"), ctx);
    }

    @Test
    public void getCurlIsExecutedAndResponseCaptured() {
        TestGenerationRequest req = TestGenerationRequest.builder()
                .testType(TestType.BACKEND_API).framework(TestFramework.KARATE)
                .rawPayload("curl -X GET " + baseUrl + "/api/pets -H 'Accept: application/json'")
                .payloadType("CURL").build();

        String ctx = service.enrichWithObservations(req);

        assertTrue(ctx.contains("## OBSERVED RESPONSE"));
        assertTrue(ctx.contains("Gözlenen Status: 200"));
        assertTrue(ctx.contains("Pamuk"));
    }

    @Test
    public void sensitiveResponseHeadersAndCookiesAreRedactedBeforePersistence() {
        TestGenerationRequest req = TestGenerationRequest.builder()
                .testType(TestType.BACKEND_API).framework(TestFramework.KARATE)
                .rawPayload("curl -X GET " + baseUrl + "/api/credentials")
                .payloadType("CURL").build();

        service.enrichWithObservations(req);

        String headers = req.getObservedResponseHeaders().toLowerCase(java.util.Locale.ROOT);
        assertTrue(headers.contains("authorization: [redacted]"));
        assertTrue(headers.contains("x-api-key: [redacted]"));
        assertFalse(headers.contains("response-secret"));
        assertEquals("[REDACTED: Set-Cookie present]", req.getObservedResponseCookies());
        assertFalse(req.getObservedResponseCookies().contains("response-cookie"));
    }

    /**
     * Onay kuralı isteğin METODUNA değil KAYNAĞINA bağlıdır.
     *
     * <p>Kullanıcının kendi yapıştırdığı istek, DELETE bile olsa gönderilir — "bu isteğe
     * test yaz" demenin başka bir anlamı yok; Postman'de Send'e basmakla aynı şey. Bir
     * dönem burada metot bazlı onay kapısı vardı: hiçbir şeyi güvenli hâle getirmedi,
     * yalnızca kullanıcının kendi verdiği isteği gözlemlemesini engelledi ve üretim
     * ölçümsüz kaldı.
     *
     * <p>Aracın KENDİ keşfettiği uçlar için kural farklıdır ve yerinde durur — bkz.
     * {@link #swaggerObservationProbesParameterlessGetEndpoints()}: orada kullanıcı o
     * çağrıları hiç istememiştir, bu yüzden yalnızca yan etkisiz problar atılır.
     */
    @Test
    public void userSuppliedMutatingCurlIsExecuted() {
        int before = mutationHits.get();
        TestGenerationRequest req = TestGenerationRequest.builder()
                .testType(TestType.BACKEND_API).framework(TestFramework.KARATE)
                .rawPayload("curl -X DELETE " + baseUrl + "/api/pets")
                .payloadType("CURL").build();

        String ctx = service.enrichWithObservations(req);

        assertEquals(before + 1, mutationHits.get(),
                "Kullanıcının kendi verdiği istek gönderilmedi");
        assertTrue(ctx.contains("## OBSERVED RESPONSE"), ctx);
        assertTrue(ctx.contains("DELETE"), ctx);
    }

    @Test
    public void swaggerObservationProbesParameterlessGetEndpoints() {
        TestGenerationRequest req = TestGenerationRequest.builder()
                .testType(TestType.BACKEND_API).framework(TestFramework.KARATE)
                .swaggerUrl(baseUrl + "/openapi.json").build();

        String ctx = service.enrichWithObservations(req);

        assertTrue(ctx.contains("## OBSERVED API"));
        assertTrue(ctx.contains("GET /api/pets → 200"));
        assertTrue(ctx.contains("Pamuk"));
        // Parametreli path problanmaz
        assertTrue(!ctx.contains("/api/pets/{id} →"));
    }

    @Test
    public void observationIsIdempotentAndSafeOnUnreachableTarget() {
        TestGenerationRequest already = TestGenerationRequest.builder()
                .testType(TestType.BACKEND_API).framework(TestFramework.KARATE)
                .rawPayload("curl -X GET " + baseUrl + "/api/pets")
                .additionalContext("x\n## OBSERVED RESPONSE\nönceden var").build();
        assertEquals(already.getAdditionalContext(), service.enrichWithObservations(already));

        TestGenerationRequest unreachable = TestGenerationRequest.builder()
                .testType(TestType.FRONTEND_WEB).framework(TestFramework.SELENIUM)
                .applicationUrl("http://localhost:1/yok").build();
        String ctx = service.enrichWithObservations(unreachable);
        assertTrue(ctx.contains("gözlemi yapılamadı") || ctx.contains("erişilemedi"));
    }
}
