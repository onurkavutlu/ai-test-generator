package com.testgen.runner;

import com.sun.net.httpserver.HttpServer;
import com.testgen.config.BadRequestException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class DirectRequestServiceTest {

    private static HttpServer server;
    private static String baseUrl;

    private final DirectRequestService service = new DirectRequestService(
            new ResponseAssertionDeriver(new com.fasterxml.jackson.databind.ObjectMapper()),
            guardAllowingPrivateNetworks());

    /** Testler localhost'a istek atıyor; guard'ın varsayılan (özel ağ serbest) hâli. */
    private static com.testgen.config.OutboundUrlGuard guardAllowingPrivateNetworks() {
        var g = new com.testgen.config.OutboundUrlGuard();
        g.setAllowPrivateNetworks(true);
        return g;
    }

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/pets", exchange -> {
            byte[] body = "{\"id\":7,\"name\":\"Pamuk\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
            exchange.close();
        });
        server.createContext("/api/echo", exchange -> {
            String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String method = exchange.getRequestMethod();
            byte[] body = ("{\"method\":\"" + method + "\",\"received\":" +
                    (reqBody.isBlank() ? "null" : reqBody) + "}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
            exchange.close();
        });
        // Normal yönlendirme: aynı sunucu içinde /api/pets'e gönderir
        server.createContext("/redirect/ok", exchange -> {
            exchange.getResponseHeaders().add("Location", "/api/pets");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        // SSRF atlatma denemesi: izin verilen host, metadata adresine yönlendiriyor
        server.createContext("/redirect/metadata", exchange -> {
            exchange.getResponseHeaders().add("Location",
                    "http://169.254.169.254/latest/meta-data/iam/security-credentials/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        // Sonsuz yönlendirme döngüsü
        server.createContext("/redirect/loop", exchange -> {
            exchange.getResponseHeaders().add("Location", "/redirect/loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        // Location başlığı olmayan 302
        server.createContext("/redirect/noloc", exchange -> {
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    public void getRequestReturnsStatusLatencyAndBody() {
        DirectRequestService.DirectRunResult result = service.execute(
                new DirectRequestService.DirectRunRequest(baseUrl + "/api/pets", "GET", Map.of(), null, 10));

        assertEquals(200, result.status());
        assertNull(result.error());
        assertTrue(result.latencyMs() >= 0);
        assertTrue(result.body().contains("Pamuk"));
        assertTrue(result.headers().keySet().stream().anyMatch(k -> k.equalsIgnoreCase("content-type")));
    }

    @Test
    public void postRequestSendsBodyWithDefaultContentType() {
        DirectRequestService.DirectRunResult result = service.execute(
                new DirectRequestService.DirectRunRequest(
                        baseUrl + "/api/echo", "post", null, "{\"ping\":\"pong\"}", null));

        assertEquals(201, result.status());
        assertTrue(result.body().contains("\"method\":\"POST\""));
        assertTrue(result.body().contains("pong"));
    }

    @Test
    public void unreachableHostReturnsErrorNotException() {
        DirectRequestService.DirectRunResult result = service.execute(
                new DirectRequestService.DirectRunRequest("http://localhost:1/x", "GET", null, null, 3));

        assertNull(result.status());
        assertNotNull(result.error());
    }

    @Test
    public void invalidUrlsAreRejected() {
        assertThrows(BadRequestException.class, () -> service.execute(
                new DirectRequestService.DirectRunRequest(null, "GET", null, null, null)));
        assertThrows(BadRequestException.class, () -> service.execute(
                new DirectRequestService.DirectRunRequest("ftp://x.com/a", "GET", null, null, null)));
        assertThrows(BadRequestException.class, () -> service.execute(
                new DirectRequestService.DirectRunRequest("not-a-url", "GET", null, null, null)));
    }

    @Test
    @DisplayName("Metadata adresi doğrudan yazıldığında istek atılmaz")
    public void directMetadataUrlIsRejected() {
        assertThrows(BadRequestException.class, () -> service.execute(
                new DirectRequestService.DirectRunRequest(
                        "http://169.254.169.254/latest/meta-data/", "GET", null, null, null)));
    }

    @Test
    @DisplayName("Yönlendirme takip edilir ve hedefin gövdesi döner")
    public void followsRedirectToAllowedTarget() {
        DirectRequestService.DirectRunResult result = service.execute(
                new DirectRequestService.DirectRunRequest(baseUrl + "/redirect/ok", "GET", null, null, 10));

        assertEquals(200, result.status());
        assertTrue(result.body().contains("Pamuk"), "Yönlendirme sonrası hedefin gövdesi dönmeli");
    }

    /**
     * SSRF kapısının asıl atlatma yolu: guard yalnızca kullanıcının yazdığı URL'yi görürse,
     * izin verilen bir host 302 ile metadata adresine yönlendirip kimlik bilgisi sızdırabilir.
     * Yönlendirme elle takip edilip her adım yeniden doğrulandığı için burada isteğin
     * ATILMAMASI gerekir.
     */
    @Test
    @DisplayName("Metadata adresine yönlendirme SSRF kapısını atlatamaz")
    public void redirectToMetadataIsBlocked() {
        assertThrows(BadRequestException.class, () -> service.execute(
                new DirectRequestService.DirectRunRequest(
                        baseUrl + "/redirect/metadata", "GET", null, null, 10)));
    }

    @Test
    @DisplayName("Sonsuz yönlendirme döngüsü hata olarak döner, askıda kalmaz")
    public void redirectLoopTerminates() {
        DirectRequestService.DirectRunResult result = service.execute(
                new DirectRequestService.DirectRunRequest(baseUrl + "/redirect/loop", "GET", null, null, 10));

        assertNull(result.status());
        assertNotNull(result.error());
        assertTrue(result.error().toLowerCase().contains("yönlendirme"), result.error());
    }

    @Test
    @DisplayName("Location başlığı olmayan 302 olduğu gibi döner")
    public void redirectWithoutLocationReturnsResponseAsIs() {
        DirectRequestService.DirectRunResult result = service.execute(
                new DirectRequestService.DirectRunRequest(baseUrl + "/redirect/noloc", "GET", null, null, 10));

        assertEquals(302, result.status());
    }
}
