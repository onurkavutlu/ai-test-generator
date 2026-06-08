package com.testgen.controller;

import com.testgen.model.TestFramework;
import com.testgen.model.TestType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record TestGenerationRequestDto(

        @Schema(description = "Test türü", example = "BACKEND_API")
        @NotNull(message = "testType zorunludur (BACKEND_API, FRONTEND_WEB, MOBILE)")
        TestType testType,

        @Schema(description = "Test otomasyon kütüphanesi", example = "KARATE")
        @NotNull(message = "framework zorunludur (KARATE, SELENIUM, APPIUM)")
        TestFramework framework,

        @Schema(description = "Kullanıcı hikayesi (User Story - Selenium/Appium için)", example = "Kullanıcı yeni evcil hayvan ekleyebilmeli ve durumunu listeleyebilmeli.")
        String userStory,

        @Schema(description = "API dökümantasyon adresi (Karate API için)", example = "https://petstore3.swagger.io/api/v3/openapi.json")
        String swaggerUrl,

        @Schema(description = "Uygulama giriş adresi (Selenium Web için)", example = "https://example.com/login")
        String applicationUrl,

        @Schema(description = "Android app package / bundle ID (Appium Mobil için)", example = "com.company.myapp")
        String appPackage,

        @Schema(description = "Ekstra veri setleri veya bağlam bilgileri", example = "Kullanıcı login: testuser, sifre: pass123")
        String additionalContext
) {}
