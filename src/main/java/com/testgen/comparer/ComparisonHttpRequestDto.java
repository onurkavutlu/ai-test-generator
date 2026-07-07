package com.testgen.comparer;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * Karşılaştırılacak tek bir HTTP isteği.
 * path her iki base URL'in sonuna eklenir (örn: /api/v1/pets?limit=10).
 */
public record ComparisonHttpRequestDto(
        String name,
        @NotBlank(message = "method zorunludur") String method,
        @NotBlank(message = "path zorunludur") String path,
        Map<String, String> headers,
        String body
) {
    public String displayName() {
        return name != null && !name.isBlank() ? name : method + " " + path;
    }
}
