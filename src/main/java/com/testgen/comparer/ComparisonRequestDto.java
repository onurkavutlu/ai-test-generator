package com.testgen.comparer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Endpoint karşılaştırma isteği.
 *
 * İstekler iki şekilde verilebilir (en az biri dolu olmalı):
 *  - requests: elle tanımlanmış istek listesi
 *  - collectionJson: Postman Collection v2.x JSON içeriği
 *
 * ignoreFields: diff sırasında yok sayılacak alanlar. Alan adı ("timestamp")
 * veya tam JSON pointer ("/data/id") olarak verilebilir.
 */
public record ComparisonRequestDto(
        @NotBlank(message = "baseUrlA zorunludur") String baseUrlA,
        @NotBlank(message = "baseUrlB zorunludur") String baseUrlB,
        @Valid List<ComparisonHttpRequestDto> requests,
        String collectionJson,
        List<String> ignoreFields,
        Boolean compareHeaders,
        @Min(1) @Max(120) Integer timeoutSeconds
) {
    public boolean hasManualRequests() {
        return requests != null && !requests.isEmpty();
    }

    public boolean hasCollection() {
        return collectionJson != null && !collectionJson.isBlank();
    }
}
