package com.testgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.testgen.config.OutboundUrlGuard;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestType;
import com.testgen.parser.CurlParser;
import com.testgen.runner.ResponseAssertionDeriver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gözlem kanıtının isteğe iliştirilmesi.
 *
 * <p>Üretilen testin neye dayandığı saklanmazsa kullanıcı "bu iddia nereden çıktı"
 * sorusunu soramaz. Gözlem yapılamadığında da <b>nedeni</b> saklanır: ekranda sessizce
 * boş bir alan yerine gerekçe görünür.
 */
class ObservationPersistenceTest {

    private static HttpServer server;
    private static String baseUrl;

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
        server.createContext("/echo", ex -> {
            byte[] out = "{\"durum\":\"tamam\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(201, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
            ex.close();
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    @DisplayName("Gözlem yapılınca status, süre, gövde ve istek satırı isteğe yazılır")
    void observationEvidenceIsAttached() {
        TestGenerationRequest req = TestGenerationRequest.builder()
                .testType(TestType.BACKEND_API).framework(TestFramework.KARATE)
                .rawPayload("curl --location '" + baseUrl + "/echo' --data '{\"a\":1}'")
                .payloadType("CURL")
                .observeMutating(true)
                .build();

        service.enrichWithObservations(req);

        assertEquals(201, req.getObservedStatus());
        assertNotNull(req.getObservedDurationMs(), "Süre ölçülmedi — SLA türetilemez");
        assertTrue(req.getObservedDurationMs() >= 0);
        assertTrue(req.getObservedBody().contains("tamam"), req.getObservedBody());
        assertTrue(req.getObservedRequestLine().startsWith("POST "), req.getObservedRequestLine());
        assertNotNull(req.getObservedAt());
        assertNull(req.getObservationSkipReason(), "Başarılı gözlemde atlama nedeni olmamalı");
    }

    @Test
    @DisplayName("Onay yokken atlama NEDENİ saklanır — sessizce boş bırakılmaz")
    void skipReasonIsRecorded() {
        TestGenerationRequest req = TestGenerationRequest.builder()
                .testType(TestType.BACKEND_API).framework(TestFramework.KARATE)
                .rawPayload("curl --location '" + baseUrl + "/echo' --data '{\"a\":1}'")
                .payloadType("CURL")
                .observeMutating(false)
                .build();

        service.enrichWithObservations(req);

        assertNull(req.getObservedStatus());
        assertNull(req.getObservedDurationMs());
        assertNotNull(req.getObservationSkipReason());
        assertTrue(req.getObservationSkipReason().contains("onayı"),
                req.getObservationSkipReason());
        // İstek satırı yine saklanır: kullanıcı NEYİN gözlenmediğini görmeli.
        assertTrue(req.getObservedRequestLine().startsWith("POST "), req.getObservedRequestLine());
    }

    @Test
    @DisplayName("Hedefe ulaşılamazsa neden saklanır, uydurma status yazılmaz")
    void unreachableTargetRecordsReason() {
        TestGenerationRequest req = TestGenerationRequest.builder()
                .testType(TestType.BACKEND_API).framework(TestFramework.KARATE)
                .rawPayload("curl --location 'http://localhost:1/yok' --data '{}'")
                .payloadType("CURL")
                .observeMutating(true)
                .build();

        service.enrichWithObservations(req);

        assertNull(req.getObservedStatus(), "Erişilemeyen hedefe status uyduruldu");
        assertNotNull(req.getObservationSkipReason());
        assertTrue(req.getObservationSkipReason().contains("yanıt alınamadı"),
                req.getObservationSkipReason());
    }
}
