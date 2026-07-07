package com.testgen.comparer;

import com.testgen.config.BadRequestException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Endpoint Comparer REST API
 *
 * POST /api/v1/comparison/run              → İstekleri iki base URL'e paralel gönderip farkları döner
 * POST /api/v1/comparison/parse-collection → Postman collection'ı önizleme için isteklere ayrıştırır
 */
@Slf4j
@Tag(name = "4. Endpoint Comparer", description = "Aynı isteği iki farklı endpoint'e gönderip yanıt farklarını (status, body diff, latency) raporlar")
@RestController
@RequestMapping("/api/v1/comparison")
@RequiredArgsConstructor
public class ComparisonController {

    private final EndpointComparisonService comparisonService;

    @Operation(summary = "Karşılaştırma Koşumu Başlat",
            description = "Verilen istekleri (manuel liste ve/veya Postman collection) her iki base URL'e paralel gönderir; "
                    + "status, JSON body deep-diff, opsiyonel header farkları ve latency karşılaştırması döner.")
    @PostMapping(value = "/run", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ComparisonResultDto> run(@Valid @RequestBody ComparisonRequestDto dto) {
        if (!dto.hasManualRequests() && !dto.hasCollection()) {
            throw new BadRequestException("requests listesi veya collectionJson alanlarından en az biri dolu olmalı.");
        }
        return ResponseEntity.ok(comparisonService.compare(dto));
    }

    @Operation(summary = "Karşılaştırma Geçmişi",
            description = "Son 50 karşılaştırma koşumunun özetini döner (en yenisi ilk).")
    @GetMapping(value = "/history", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Map<String, Object>>> history() {
        return ResponseEntity.ok(comparisonService.history().stream()
                .map(run -> Map.<String, Object>of(
                        "id", run.getId(),
                        "baseUrlA", run.getBaseUrlA(),
                        "baseUrlB", run.getBaseUrlB(),
                        "totalRequests", run.getTotalRequests(),
                        "identicalCount", run.getIdenticalCount(),
                        "differentCount", run.getDifferentCount(),
                        "errorCount", run.getErrorCount(),
                        "totalDurationMs", run.getTotalDurationMs(),
                        "executedAt", run.getExecutedAt().toString()))
                .toList());
    }

    @Operation(summary = "Karşılaştırma Geçmişi Detayı",
            description = "Tek bir koşumun tam sonucunu (diff listesi dahil) döner.")
    @GetMapping(value = "/history/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> historyDetail(@PathVariable String id) {
        return ResponseEntity.ok(comparisonService.historyDetail(id).getResultJson());
    }

    @Operation(summary = "Postman Collection Önizleme",
            description = "Postman Collection v2.x JSON içeriğini karşılaştırılabilir istek listesine ayrıştırır (koşum yapmaz).")
    @PostMapping(value = "/parse-collection",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> parseCollection(@RequestBody Map<String, String> body) {
        String collectionJson = body.get("collectionJson");
        if (collectionJson == null || collectionJson.isBlank()) {
            throw new BadRequestException("collectionJson alanı zorunludur.");
        }
        List<ComparisonHttpRequestDto> requests = comparisonService.parseCollection(collectionJson);
        return ResponseEntity.ok(Map.of(
                "requestCount", requests.size(),
                "requests", requests
        ));
    }
}
