package com.testgen.service;

import com.sun.net.httpserver.HttpServer;
import com.testgen.config.OutboundUrlGuard;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestType;
import com.testgen.parser.CurlParser;
import com.testgen.runner.ResponseAssertionDeriver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gözlem kapısı: <b>ölçülemeyen değer üretilmez.</b>
 *
 * <p><b>Kapatılan gerçek arıza.</b> Kullanıcı, {@code -X POST} yazmadan yalnızca
 * {@code --data} ile bir SOAP cURL'ü verdi. Sistem:
 * <ol>
 *   <li>metodu GET sandı ve başlıksız/gövdesiz bir GET attı,</li>
 *   <li>yanıt alamayınca bağlama "hedefe erişilemedi" notu yazdı,</li>
 *   <li>log yine de <i>"Gözlem tamamlandı — gerçek veri eklendi"</i> dedi,</li>
 *   <li>ajanlar bu notu "endpoint erişilemez" diye okuyup analizin tamamını yanlış
 *       öncüle dayandırdı ve aynı cümleyi sayfalarca tekrarladı.</li>
 * </ol>
 * Üretilen hiçbir şey ölçüme dayanmıyordu ama sistem başarılı göründü.
 */
class ObservationGateTest {

    private static HttpServer server;
    private static String baseUrl;
    private static final AtomicInteger postHits = new AtomicInteger();
    private static final AtomicReference<String> lastMethod = new AtomicReference<>();
    private static final AtomicReference<String> lastBody = new AtomicReference<>();
    private static final AtomicReference<String> lastSoapAction = new AtomicReference<>();

    private final ObservationService service = new ObservationService(
            new ResponseAssertionDeriver(new ObjectMapper()), guard(), new CurlParser());

    private static OutboundUrlGuard guard() {
        var g = new OutboundUrlGuard();
        g.setAllowPrivateNetworks(true);
        return g;
    }

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/soap", ex -> {
            lastMethod.set(ex.getRequestMethod());
            lastSoapAction.set(ex.getRequestHeaders().getFirst("SOAPAction"));
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if ("POST".equals(ex.getRequestMethod())) {
                postHits.incrementAndGet();
                byte[] out = ("<Envelope><Body><out><returnCode>0000</returnCode>"
                        + "<returnMessage>İşlem Başarılı</returnMessage></out></Body></Envelope>")
                        .getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "text/xml");
                ex.sendResponseHeaders(200, out.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(out); }
            } else {
                // Gerçek SOAP uçları gibi: GET'e anlamlı yanıt vermez
                ex.sendResponseHeaders(405, -1);
            }
            ex.close();
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        if (server != null) server.stop(0);
    }

    /** Kullanıcının verdiği cURL'ün birebir aynısı — {@code -X} yok, gövde var. */
    private static String soapCurl() {
        return "curl --location '" + baseUrl + "/soap' \\\n"
                + "--header 'Content-Type: text/xml; charset=utf-8' \\\n"
                + "--header 'SOAPAction: http://ornek/v2/sorgula' \\\n"
                + "--data '<soapenv:Envelope>\n  <soapenv:Body>\n"
                + "    <v1:input><pageNo>1</pageNo></v1:input>\n"
                + "  </soapenv:Body>\n</soapenv:Envelope>'";
    }

    private static TestGenerationRequest request(boolean consent) {
        return TestGenerationRequest.builder()
                .testType(TestType.BACKEND_API).framework(TestFramework.KARATE)
                .rawPayload(soapCurl()).payloadType("CURL")
                .observeMutating(consent)
                .build();
    }

    @Test
    @DisplayName("Onay yokken yan etkili istek GÖNDERİLMEZ ve 'erişilemedi' denmez")
    void withoutConsentNothingIsSentAndNoWrongConclusion() {
        int before = postHits.get();

        String ctx = service.enrichWithObservations(request(false));

        assertEquals(before, postHits.get(), "Onaysız yan etkili istek gönderildi");
        assertFalse(ObservationService.isObserved(ctx), "Not, gözlem sayıldı");
        // En kritik nokta: ajanlar 'erişilemez' çıkarımı yapmasın diye açıkça yazılır.
        assertTrue(ctx.contains("hedefin erişilemez olduğu ANLAMINA GELMEZ"), ctx);
        assertTrue(ctx.contains("ÇIKARIM YAPMAYIN"), ctx);
    }

    @Test
    @DisplayName("Onay verilince isteğin AYNISI gider: POST, başlıklar ve gövde")
    void withConsentTheExactRequestIsSent() {
        String ctx = service.enrichWithObservations(request(true));

        assertEquals("POST", lastMethod.get(),
                "-X yokken metot GET sanıldı — yanlış istek gönderildi");
        assertEquals("http://ornek/v2/sorgula", lastSoapAction.get(), "SOAPAction düştü");
        assertTrue(lastBody.get().contains("<pageNo>1</pageNo>"), "Gövde gönderilmedi");

        assertTrue(ObservationService.isObserved(ctx), ctx);
        assertTrue(ctx.contains("Gözlenen Status: 200"), ctx);
        assertTrue(ctx.contains("returnCode"), ctx);
    }

    /**
     * SLA yalnızca ölçülen süreden türetilebilir. Postman'in ürettiği
     * {@code responseTime < 5000} gibi alışkanlık sayıları, ölçümün kendisinde bile
     * başarısız olabiliyor.
     */
    @Test
    @DisplayName("Gözlenen süre bağlama yazılır — SLA ancak ölçümden türetilebilir")
    void measuredDurationIsRecorded() {
        String ctx = service.enrichWithObservations(request(true));

        assertTrue(ctx.contains("Gözlenen Süre"), ctx);
        assertTrue(ctx.contains("Ölçülmemiş SLA yazma"), ctx);
    }

    @Test
    @DisplayName("isObserved: 'yapılamadı' notu gözlem sayılmaz")
    void notesAreNotObservations() {
        assertFalse(ObservationService.isObserved(null));
        assertFalse(ObservationService.isObserved(""));
        assertFalse(ObservationService.isObserved("## OBSERVED NOTE\nHedefe istek gönderildi ancak..."));
        assertFalse(ObservationService.isObserved("## OBSERVED PAGE\nSayfa gözlemi yapılamadı (x)"));
        assertTrue(ObservationService.isObserved("## OBSERVED RESPONSE (canlı)\nGözlenen Status: 200"));
        assertTrue(ObservationService.isObserved("## OBSERVED API\nBase URL: x"));
    }
}
