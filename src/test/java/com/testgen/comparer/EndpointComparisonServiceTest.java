package com.testgen.comparer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.testgen.config.BadRequestException;
import com.testgen.parser.ApiCollectionParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * İki gerçek lokal HTTP sunucusu ayağa kaldırarak comparer'ın uçtan uca
 * davranışını doğrular (harici bağımlılık gerektirmez).
 */
public class EndpointComparisonServiceTest {

    private static HttpServer serverA;
    private static HttpServer serverB;
    private static String baseUrlA;
    private static String baseUrlB;

    private final ObjectMapper mapper = new ObjectMapper();
    private final EndpointComparisonService service =
            new EndpointComparisonService(mapper, new ApiCollectionParser(mapper),
                    org.mockito.Mockito.mock(com.testgen.repository.ComparisonRunRepository.class));

    @BeforeAll
    static void startServers() throws Exception {
        serverA = HttpServer.create(new InetSocketAddress(0), 0);
        serverA.createContext("/api/pets", exchange -> respond(exchange,
                200, "{\"id\":1,\"name\":\"Karabas\",\"status\":\"available\",\"legacyField\":true}"));
        serverA.createContext("/api/echo", exchange -> {
            String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(exchange, 201, "{\"received\":" + (reqBody.isBlank() ? "null" : reqBody) + "}");
        });
        serverA.start();
        baseUrlA = "http://localhost:" + serverA.getAddress().getPort();

        serverB = HttpServer.create(new InetSocketAddress(0), 0);
        serverB.createContext("/api/pets", exchange -> respond(exchange,
                200, "{\"id\":1,\"name\":\"Karabas\",\"status\":\"sold\"}"));
        serverB.createContext("/api/echo", exchange -> {
            String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(exchange, 201, "{\"received\":" + (reqBody.isBlank() ? "null" : reqBody) + "}");
        });
        serverB.start();
        baseUrlB = "http://localhost:" + serverB.getAddress().getPort();
    }

    @AfterAll
    static void stopServers() {
        if (serverA != null) serverA.stop(0);
        if (serverB != null) serverB.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange,
                                int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
        exchange.close();
    }

    @Test
    public void detectsBodyDifferencesBetweenEndpoints() {
        ComparisonRequestDto dto = new ComparisonRequestDto(
                baseUrlA, baseUrlB,
                List.of(new ComparisonHttpRequestDto("Pet detay", "GET", "/api/pets", Map.of(), null)),
                null, null, null, 10);

        ComparisonResultDto result = service.compare(dto);

        assertEquals(1, result.summary().totalRequests());
        assertEquals(0, result.summary().identicalCount());
        assertEquals(1, result.summary().differentCount());

        RequestComparisonResult r = result.results().get(0);
        assertTrue(r.statusMatch());
        assertFalse(r.bodyMatch());
        assertTrue(r.differences().stream().anyMatch(d ->
                d.path().equals("/status") && d.type() == FieldDifference.DifferenceType.VALUE_MISMATCH));
        assertTrue(r.differences().stream().anyMatch(d ->
                d.path().equals("/legacyField") && d.type() == FieldDifference.DifferenceType.MISSING_IN_B));
    }

    @Test
    public void identicalResponsesAreReportedAsMatch() {
        ComparisonRequestDto dto = new ComparisonRequestDto(
                baseUrlA, baseUrlB,
                List.of(new ComparisonHttpRequestDto("Echo", "POST", "/api/echo", Map.of(),
                        "{\"ping\":\"pong\"}")),
                null, null, null, 10);

        ComparisonResultDto result = service.compare(dto);

        assertEquals(1, result.summary().identicalCount());
        RequestComparisonResult r = result.results().get(0);
        assertTrue(r.identical());
        assertEquals(201, r.statusA());
        assertEquals(201, r.statusB());
    }

    @Test
    public void ignoreFieldsSuppressesDynamicDifferences() {
        ComparisonRequestDto dto = new ComparisonRequestDto(
                baseUrlA, baseUrlB,
                List.of(new ComparisonHttpRequestDto("Pet detay", "GET", "/api/pets", Map.of(), null)),
                null, List.of("status", "legacyField"), null, 10);

        ComparisonResultDto result = service.compare(dto);
        assertEquals(1, result.summary().identicalCount());
    }

    @Test
    public void unreachableEndpointIsReportedAsError() {
        ComparisonRequestDto dto = new ComparisonRequestDto(
                baseUrlA, "http://localhost:1",
                List.of(new ComparisonHttpRequestDto(null, "GET", "/api/pets", Map.of(), null)),
                null, null, null, 5);

        ComparisonResultDto result = service.compare(dto);

        assertEquals(1, result.summary().errorCount());
        RequestComparisonResult r = result.results().get(0);
        assertNull(r.errorA());
        assertNotNull(r.errorB());
    }

    @Test
    public void parsesPostmanCollectionIntoRequests() {
        String collection = """
                {
                  "info": {"name": "Pets", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                  "item": [
                    {
                      "name": "Get Pet",
                      "request": {
                        "method": "GET",
                        "header": [{"key": "Accept", "value": "application/json"}],
                        "url": {"raw": "https://petstore.example.com/api/pets?limit=5"}
                      }
                    },
                    {
                      "name": "Create Pet",
                      "request": {
                        "method": "POST",
                        "header": [],
                        "body": {"mode": "raw", "raw": "{\\"name\\":\\"Boncuk\\"}"},
                        "url": {"raw": "https://petstore.example.com/api/pets"}
                      }
                    }
                  ]
                }
                """;

        List<ComparisonHttpRequestDto> requests = service.parseCollection(collection);

        assertEquals(2, requests.size());
        assertEquals("GET", requests.get(0).method());
        assertEquals("/api/pets?limit=5", requests.get(0).path());
        assertEquals("application/json", requests.get(0).headers().get("Accept"));
        assertEquals("POST", requests.get(1).method());
        assertEquals("{\"name\":\"Boncuk\"}", requests.get(1).body());
    }

    @Test
    public void collectionCanBeUsedDirectlyInComparisonRun() {
        String collection = """
                {
                  "item": [
                    {"name": "Pets", "request": {"method": "GET", "url": {"raw": "%s/api/pets"}}}
                  ]
                }
                """.formatted(baseUrlA);

        ComparisonRequestDto dto = new ComparisonRequestDto(
                baseUrlA, baseUrlB, null, collection, null, null, 10);

        ComparisonResultDto result = service.compare(dto);
        assertEquals(1, result.summary().totalRequests());
        assertEquals("/api/pets", result.results().get(0).path());
    }

    @Test
    public void invalidBaseUrlIsRejected() {
        ComparisonRequestDto dto = new ComparisonRequestDto(
                "ftp://invalid", baseUrlB,
                List.of(new ComparisonHttpRequestDto(null, "GET", "/x", Map.of(), null)),
                null, null, null, null);
        assertThrows(BadRequestException.class, () -> service.compare(dto));
    }

    @Test
    public void emptyRequestSetIsRejected() {
        ComparisonRequestDto dto = new ComparisonRequestDto(
                baseUrlA, baseUrlB, List.of(), null, null, null, null);
        assertThrows(BadRequestException.class, () -> service.compare(dto));
    }

    @Test
    public void extractPathHandlesVariousUrlShapes() {
        assertEquals("/api/pets?x=1", EndpointComparisonService.extractPath("https://host.com/api/pets?x=1"));
        assertEquals("/api/pets", EndpointComparisonService.extractPath("{{baseUrl}}/api/pets"));
        assertEquals("/api/pets", EndpointComparisonService.extractPath("/api/pets"));
        assertEquals("/api/pets", EndpointComparisonService.extractPath("api/pets"));
        assertEquals("/", EndpointComparisonService.extractPath(""));
    }
}
