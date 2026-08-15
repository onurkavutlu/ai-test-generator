package com.testgen.controller;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import com.testgen.model.TestFramework;
import com.testgen.model.TestType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

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

        @Schema(description = "API dökümantasyon adresi (Karate API için)", example = "https://fakerestapi.azurewebsites.net/swagger/v1/swagger.json")
        String swaggerUrl,

        @Schema(description = "Uygulama giriş adresi (Selenium Web için)", example = "https://example.com/login")
        String applicationUrl,

        @Schema(description = "Ekstra veri setleri veya bağlam bilgileri", example = "Kullanıcı login: testuser, sifre: pass123")
        String additionalContext,

        @Schema(description = "Ham veri yükü (cURL, JSON, XML vs.)", example = "curl -X POST https://api.example.com -d '{\"data\":\"test\"}'")
        String rawPayload,

        @Schema(description = "Ham veri formatı (CURL, JSON, XML)", example = "CURL")
        String payloadType,

        /*
         * Swagger'dan üretimde case sayısı endpoint sayısına eşittir; 37 yollu bir API
         * 45 case ve ~25 dakika üretim demek. Bunu prompt'la sınırlamak İŞE YARAMAZ
         * (prompt yalnızca dosya içindeki senaryo sayısını etkiler), bu yüzden sınır
         * üretici döngüsünde uygulanır.
         */
        @Schema(description = "OPSİYONEL — üretilecek en fazla test case sayısı. Verilirse her zaman kazanır. "
                + "Boş bırakılırsa test-generator.generation.default-max-cases yapılandırması, "
                + "o da 0 ise sınırsız (Swagger'daki her endpoint için bir case) uygulanır.",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED, example = "5")
        @Positive(message = "maxCases pozitif olmalıdır")
        Integer maxCases
) {}
