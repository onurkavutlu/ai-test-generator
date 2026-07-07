package com.testgen.comparer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testgen.config.BadRequestException;
import com.testgen.model.ComparisonRun;
import com.testgen.parser.ApiCollectionParser;
import com.testgen.parser.ParsedRequestDto;
import com.testgen.repository.ComparisonRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Aynı HTTP isteğini iki farklı base URL'e paralel gönderip
 * status, gövde (JSON deep-diff) ve opsiyonel header farklarını raporlar.
 *
 * Kullanım senaryoları: legacy vs. yeni servis migrasyon doğrulaması,
 * staging vs. production kontrat kontrolü, blue/green deployment karşılaştırması.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EndpointComparisonService {

    private static final int MAX_STORED_BODY_CHARS = 100_000;
    private static final int MAX_PARALLEL_REQUESTS = 8;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** Karşılaştırmada anlamsız, sunucuya/ana göre değişen header'lar. */
    private static final Set<String> VOLATILE_HEADERS = Set.of(
            "date", "server", "x-request-id", "x-correlation-id", "x-trace-id",
            "set-cookie", "content-length", "connection", "keep-alive",
            "transfer-encoding", "etag", "x-response-time", "age", "via", "cf-ray");

    private final ObjectMapper objectMapper;
    private final ApiCollectionParser apiCollectionParser;
    private final ComparisonRunRepository comparisonRunRepository;

    public ComparisonResultDto compare(ComparisonRequestDto dto) {
        validateBaseUrl(dto.baseUrlA(), "baseUrlA");
        validateBaseUrl(dto.baseUrlB(), "baseUrlB");

        List<ComparisonHttpRequestDto> requests = resolveRequests(dto);
        if (requests.isEmpty()) {
            throw new BadRequestException(
                    "Karşılaştırılacak istek bulunamadı: requests listesi veya geçerli bir collectionJson verin.");
        }

        int timeoutSeconds = dto.timeoutSeconds() != null ? dto.timeoutSeconds() : DEFAULT_TIMEOUT_SECONDS;
        boolean compareHeaders = Boolean.TRUE.equals(dto.compareHeaders());
        Set<String> ignoreFields = dto.ignoreFields() == null
                ? Set.of()
                : new HashSet<>(dto.ignoreFields());

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 15)))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        long start = System.currentTimeMillis();
        List<RequestComparisonResult> results;

        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(MAX_PARALLEL_REQUESTS, requests.size()));
        try {
            List<CompletableFuture<RequestComparisonResult>> futures = requests.stream()
                    .map(req -> CompletableFuture.supplyAsync(
                            () -> compareOne(client, dto.baseUrlA(), dto.baseUrlB(), req,
                                    timeoutSeconds, compareHeaders, ignoreFields),
                            executor))
                    .toList();
            results = futures.stream().map(CompletableFuture::join).toList();
        } finally {
            executor.shutdown();
        }

        long totalMs = System.currentTimeMillis() - start;
        log.info("Comparison tamamlandı: {} istek, {}ms — A: {}, B: {}",
                requests.size(), totalMs, dto.baseUrlA(), dto.baseUrlB());

        ComparisonResultDto resultDto = new ComparisonResultDto(
                dto.baseUrlA(), dto.baseUrlB(), LocalDateTime.now(), totalMs,
                ComparisonResultDto.Summary.of(results), results);
        persistRun(resultDto);
        return resultDto;
    }

    /** Koşum geçmişini comparison_runs tablosuna yazar (best-effort). */
    private void persistRun(ComparisonResultDto result) {
        try {
            String resultJson = objectMapper.writeValueAsString(result);
            comparisonRunRepository.save(ComparisonRun.builder()
                    .baseUrlA(result.baseUrlA())
                    .baseUrlB(result.baseUrlB())
                    .totalRequests(result.summary().totalRequests())
                    .identicalCount(result.summary().identicalCount())
                    .differentCount(result.summary().differentCount())
                    .errorCount(result.summary().errorCount())
                    .totalDurationMs(result.totalDurationMs())
                    .resultJson(resultJson)
                    .executedAt(result.executedAt())
                    .build());
        } catch (Exception e) {
            log.warn("Comparison koşumu DB'ye yazılamadı: {}", e.getMessage());
        }
    }

    public List<ComparisonRun> history() {
        return comparisonRunRepository.findTop50ByOrderByExecutedAtDesc();
    }

    public ComparisonRun historyDetail(String id) {
        return comparisonRunRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Comparison koşumu bulunamadı: " + id));
    }

    // ─────────────────────────────────────────────────────────
    // İstek çözümleme — manuel liste + Postman collection
    // ─────────────────────────────────────────────────────────

    List<ComparisonHttpRequestDto> resolveRequests(ComparisonRequestDto dto) {
        List<ComparisonHttpRequestDto> requests = new ArrayList<>();
        if (dto.hasManualRequests()) {
            requests.addAll(dto.requests());
        }
        if (dto.hasCollection()) {
            requests.addAll(parseCollection(dto.collectionJson()));
        }
        return requests;
    }

    /** Postman Collection v2.x içeriğini karşılaştırılabilir isteklere dönüştürür. */
    public List<ComparisonHttpRequestDto> parseCollection(String collectionJson) {
        List<ParsedRequestDto> parsed = apiCollectionParser.parse(collectionJson);
        List<ComparisonHttpRequestDto> requests = new ArrayList<>();
        for (ParsedRequestDto item : parsed) {
            requests.add(new ComparisonHttpRequestDto(
                    item.name(),
                    item.method() != null ? item.method().toUpperCase(Locale.ROOT) : "GET",
                    extractPath(item.url()),
                    extractHeaders(item.payloadDetails()),
                    extractBody(item.payloadDetails())
            ));
        }
        return requests;
    }

    /** Tam URL'den path+query çıkarır; zaten path ise olduğu gibi döner. */
    static String extractPath(String url) {
        if (url == null || url.isBlank()) {
            return "/";
        }
        String trimmed = url.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            try {
                URI uri = URI.create(trimmed);
                String path = uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                        ? uri.getRawPath() : "/";
                return uri.getRawQuery() != null ? path + "?" + uri.getRawQuery() : path;
            } catch (IllegalArgumentException e) {
                return trimmed;
            }
        }
        // Postman değişkenli URL'ler ({{baseUrl}}/pets) → path kısmını al
        int slashIdx = trimmed.indexOf('/');
        if (trimmed.contains("{{") && slashIdx >= 0) {
            return trimmed.substring(slashIdx);
        }
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private Map<String, String> extractHeaders(String requestNodeJson) {
        Map<String, String> headers = new LinkedHashMap<>();
        try {
            JsonNode node = objectMapper.readTree(requestNodeJson);
            JsonNode headerArray = node.get("header");
            if (headerArray != null && headerArray.isArray()) {
                for (JsonNode h : headerArray) {
                    if (h.has("key") && h.has("value") && !h.path("disabled").asBoolean(false)) {
                        headers.put(h.get("key").asText(), h.get("value").asText());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Collection header parse edilemedi: {}", e.getMessage());
        }
        return headers;
    }

    private String extractBody(String requestNodeJson) {
        try {
            JsonNode node = objectMapper.readTree(requestNodeJson);
            JsonNode body = node.get("body");
            if (body != null && body.has("raw")) {
                return body.get("raw").asText();
            }
        } catch (Exception e) {
            log.debug("Collection body parse edilemedi: {}", e.getMessage());
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────
    // Tek isteğin iki tarafa gönderimi ve karşılaştırması
    // ─────────────────────────────────────────────────────────

    private RequestComparisonResult compareOne(HttpClient client, String baseUrlA, String baseUrlB,
                                               ComparisonHttpRequestDto req, int timeoutSeconds,
                                               boolean compareHeaders, Set<String> ignoreFields) {
        CompletableFuture<SideResult> futureA = executeSide(client, baseUrlA, req, timeoutSeconds);
        CompletableFuture<SideResult> futureB = executeSide(client, baseUrlB, req, timeoutSeconds);
        SideResult a = futureA.join();
        SideResult b = futureB.join();

        boolean statusMatch = a.status != null && a.status.equals(b.status);

        List<FieldDifference> bodyDiffs = List.of();
        boolean bodyMatch = false;
        if (a.error == null && b.error == null) {
            bodyDiffs = diffBodies(a.body, b.body, ignoreFields);
            bodyMatch = bodyDiffs.isEmpty();
        }

        List<FieldDifference> headerDiffs = compareHeaders && a.error == null && b.error == null
                ? diffHeaders(a.headers, b.headers)
                : List.of();

        return new RequestComparisonResult(
                req.displayName(), req.method().toUpperCase(Locale.ROOT), req.path(),
                a.status, b.status, statusMatch,
                a.latencyMs, b.latencyMs,
                bodyMatch, bodyDiffs, headerDiffs,
                truncate(a.body), truncate(b.body),
                a.error, b.error);
    }

    private CompletableFuture<SideResult> executeSide(HttpClient client, String baseUrl,
                                                      ComparisonHttpRequestDto req, int timeoutSeconds) {
        long start = System.currentTimeMillis();
        HttpRequest httpRequest;
        try {
            httpRequest = buildRequest(baseUrl, req, timeoutSeconds);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                    SideResult.error("İstek oluşturulamadı: " + e.getMessage(), 0));
        }

        return client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> new SideResult(
                        resp.statusCode(), resp.body(), flatten(resp.headers().map()),
                        System.currentTimeMillis() - start, null))
                .exceptionally(e -> SideResult.error(
                        rootMessage(e), System.currentTimeMillis() - start));
    }

    private HttpRequest buildRequest(String baseUrl, ComparisonHttpRequestDto req, int timeoutSeconds) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String path = req.path().startsWith("/") ? req.path() : "/" + req.path();
        URI uri = URI.create(base + path);

        String method = req.method().toUpperCase(Locale.ROOT);
        boolean hasBody = req.body() != null && !req.body().isBlank()
                && !method.equals("GET") && !method.equals("HEAD");

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .method(method, hasBody
                        ? HttpRequest.BodyPublishers.ofString(req.body())
                        : HttpRequest.BodyPublishers.noBody());

        boolean contentTypeSet = false;
        if (req.headers() != null) {
            for (Map.Entry<String, String> h : req.headers().entrySet()) {
                if (isRestrictedHeader(h.getKey())) {
                    continue;
                }
                builder.header(h.getKey(), h.getValue());
                if (h.getKey().equalsIgnoreCase("content-type")) {
                    contentTypeSet = true;
                }
            }
        }
        if (hasBody && !contentTypeSet) {
            builder.header("Content-Type", "application/json");
        }
        return builder.build();
    }

    /** JDK HttpClient'ın izin vermediği header'lar (host, content-length vs.). */
    private boolean isRestrictedHeader(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("host") || lower.equals("content-length") || lower.equals("connection");
    }

    private List<FieldDifference> diffBodies(String bodyA, String bodyB, Set<String> ignoreFields) {
        String a = bodyA == null ? "" : bodyA;
        String b = bodyB == null ? "" : bodyB;

        JsonNode nodeA = tryParseJson(a);
        JsonNode nodeB = tryParseJson(b);

        if (nodeA != null && nodeB != null) {
            return JsonDiff.diff(nodeA, nodeB, ignoreFields);
        }
        // JSON değilse düz metin karşılaştırması
        if (a.equals(b)) {
            return List.of();
        }
        return List.of(new FieldDifference("/", FieldDifference.DifferenceType.VALUE_MISMATCH,
                previewText(a), previewText(b)));
    }

    private List<FieldDifference> diffHeaders(Map<String, String> headersA, Map<String, String> headersB) {
        List<FieldDifference> diffs = new ArrayList<>();
        Set<String> allKeys = new TreeSet<>();
        allKeys.addAll(headersA.keySet());
        allKeys.addAll(headersB.keySet());
        for (String key : allKeys) {
            if (VOLATILE_HEADERS.contains(key.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String valA = headersA.get(key);
            String valB = headersB.get(key);
            if (valA == null) {
                diffs.add(new FieldDifference("/headers/" + key,
                        FieldDifference.DifferenceType.MISSING_IN_A, null, valB));
            } else if (valB == null) {
                diffs.add(new FieldDifference("/headers/" + key,
                        FieldDifference.DifferenceType.MISSING_IN_B, valA, null));
            } else if (!valA.equals(valB)) {
                diffs.add(new FieldDifference("/headers/" + key,
                        FieldDifference.DifferenceType.VALUE_MISMATCH, valA, valB));
            }
        }
        return diffs;
    }

    private JsonNode tryParseJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return null;
        }
        try {
            return objectMapper.readTree(trimmed);
        } catch (Exception e) {
            return null;
        }
    }

    private void validateBaseUrl(String url, String fieldName) {
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
                throw new BadRequestException(fieldName + " http veya https ile başlamalı: " + url);
            }
            if (uri.getHost() == null) {
                throw new BadRequestException(fieldName + " geçerli bir host içermeli: " + url);
            }
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(fieldName + " geçerli bir URL değil: " + url);
        }
    }

    private static Map<String, String> flatten(Map<String, List<String>> headers) {
        Map<String, String> flat = new LinkedHashMap<>();
        headers.forEach((k, v) -> {
            if (!k.startsWith(":")) {
                flat.put(k, String.join(", ", v));
            }
        });
        return flat;
    }

    private static String truncate(String body) {
        if (body == null) {
            return null;
        }
        return body.length() > MAX_STORED_BODY_CHARS
                ? body.substring(0, MAX_STORED_BODY_CHARS) + "\n…[kısaltıldı]"
                : body;
    }

    private static String previewText(String text) {
        return text.length() > 500 ? text.substring(0, 500) + "…" : text;
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        return msg != null && !msg.isBlank() ? msg : cause.getClass().getSimpleName();
    }

    private record SideResult(Integer status, String body, Map<String, String> headers,
                              long latencyMs, String error) {
        static SideResult error(String message, long latencyMs) {
            return new SideResult(null, null, Map.of(), latencyMs, message);
        }
    }
}
