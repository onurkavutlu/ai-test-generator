package com.testgen.runner;

import com.testgen.config.BadRequestException;
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
public class DirectRequestService {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_BODY_CHARS = 100_000;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
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
            HttpResponse<String> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
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
            return new DirectRunResult(response.statusCode(), latency, respHeaders, body, null);

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Direkt koşum hatası: {} {} → {} ({}ms)", method, req.url(), message, latency);
            return new DirectRunResult(null, latency, Map.of(), null, message);
        }
    }

    private void validate(DirectRunRequest req) {
        if (req.url() == null || req.url().isBlank()) {
            throw new BadRequestException("url zorunludur.");
        }
        URI uri;
        try {
            uri = URI.create(req.url().trim());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("url geçerli değil: " + req.url());
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            throw new BadRequestException("url http veya https ile başlamalı: " + req.url());
        }
        if (uri.getHost() == null) {
            throw new BadRequestException("url geçerli bir host içermeli: " + req.url());
        }
    }

    public record DirectRunRequest(
            String url,
            String method,
            Map<String, String> headers,
            String body,
            Integer timeoutSeconds
    ) {}

    public record DirectRunResult(
            Integer status,
            long latencyMs,
            Map<String, String> headers,
            String body,
            String error
    ) {}
}
