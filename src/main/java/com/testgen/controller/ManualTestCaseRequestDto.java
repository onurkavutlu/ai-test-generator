package com.testgen.controller;

import com.testgen.model.TestFramework;
import com.testgen.model.TestRunStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record ManualTestCaseRequestDto(
        @Schema(description = "Test senaryosu adı", example = "GetPetByIdManualTest")
        @NotBlank(message = "testName zorunludur")
        String testName,

        @Schema(description = "Kaydedilecek dosya adı (Uzantısı framework'e göre otomatik belirlenir)", example = "GetPetByIdManualTest.feature")
        String fileName,

        @Schema(description = "Test kod bloğu içeriği", example = "Feature: Get pet by id manual\\n  Scenario: Get pet\\n    Given url 'https://petstore3.swagger.io/api/v3'\\n    When method GET\\n    Then status 200")
        @NotBlank(message = "testContent zorunludur")
        String testContent,

        @Schema(description = "Testin kısa açıklaması", example = "Pet ID 10 endpoint'i için el ile eklenen Karate API testi.")
        String testSummary,

        @Schema(description = "Test framework'ü", example = "KARATE")
        TestFramework framework,

        @Schema(description = "Testin koşum durumu", example = "NOT_RUN")
        TestRunStatus runStatus
) {
}
