package com.testgen.controller;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import com.testgen.model.TestFramework;
import com.testgen.model.TestType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@JacksonXmlRootElement(localName = "testGenerationRequest")
public record TestGenerationRequestDto(

        @Schema(description = "Test türü", example = "BACKEND_API")
        @NotNull(message = "testType zorunludur (BACKEND_API, FRONTEND_WEB)")
        TestType testType,

        @Schema(description = "Test otomasyon kütüphanesi", example = "KARATE")
        @NotNull(message = "framework zorunludur (KARATE, SELENIUM)")
        TestFramework framework,

        @Schema(description = "Kullanıcı hikayesi (User Story - Selenium için)", example = "Kullanıcı yeni evcil hayvan ekleyebilmeli ve durumunu listeleyebilmeli.")
        String userStory,

        @Schema(description = "API dökümantasyon adresi (Karate API için)", example = "https://petstore3.swagger.io/api/v3/openapi.json")
        String swaggerUrl,

        @Schema(description = "Uygulama giriş adresi (Selenium Web için)", example = "https://example.com/login")
        String applicationUrl,

        @Schema(description = "Ekstra veri setleri veya bağlam bilgileri", example = "Kullanıcı login: testuser, sifre: pass123")
        String additionalContext,

        @Schema(description = "Ham veri yükü (cURL, JSON, XML vs.)", example = "curl -X POST https://api.example.com -d '{\"data\":\"test\"}'")
        String rawPayload,

        @Schema(description = "Ham veri formatı (CURL, JSON, XML)", example = "CURL")
        String payloadType
) {}
