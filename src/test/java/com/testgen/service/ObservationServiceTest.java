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
                        + "<form id=\"login-form\"><input id=\"username\"><input id=\"password\">"
                        + "<button id=\"submit-btn\">Gir</button></form></body></html>"));
        server.createContext("/api/pets", ex -> {
            if (!ex.getRequestMethod().equals("GET")) mutationHits.incrementAndGet();
            respond(ex, 200, "application/json", "{\"id\":7,\"name\":\"Pamuk\"}");
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
        assertTrue(ctx.contains("UYDURMA"));
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
