package com.testgen.service;

import com.testgen.llm.LlmService;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiTestDataGenerationService {

    private static final String SECTION_TITLE = "## AI-GENERATED TEST DATA";

    private final LlmService llmService;

    public String enrichAdditionalContext(TestGenerationRequest request) {
        String existingContext = request.getAdditionalContext() == null ? "" : request.getAdditionalContext().trim();
        if (existingContext.contains(SECTION_TITLE)) {
            return existingContext;
        }

        String aiData = generateFrameworkData(request);
        if (aiData == null || aiData.isBlank()) {
            return existingContext;
        }

        if (existingContext.isBlank()) {
            return SECTION_TITLE + "\n" + aiData.trim();
        }
        return existingContext + "\n\n" + SECTION_TITLE + "\n" + aiData.trim();
    }

    private String generateFrameworkData(TestGenerationRequest request) {
        String prompt = buildPrompt(request);
        try {
            String response = llmService.generateTestCase(prompt);
            if (response != null && !response.isBlank()) {
                return clean(response);
            }
        } catch (Exception e) {
            log.error("LLM test datası üretilemedi - framework: {}",
                    request.getFramework(), e);
            throw new TestGenerationException("LLM test datası üretilemedi: " + e.getMessage(), e);
        }
        throw new TestGenerationException("LLM test datası için boş çıktı döndürdü.");
    }

    private String buildPrompt(TestGenerationRequest request) {
        return """
                Secilen framework icin test yaziminda kullanilacak gercekci test datası uret.

                Framework: %s
                Test tipi: %s
                User story: %s
                Swagger URL: %s
                Application URL: %s
                Ek baglam: %s

                Kurallar:
                1. En az 7 farkli veri seti uret ve her birini kapsam etiketiyle ayir:
                   - API
                   - SMOKE
                   - REGRESSION
                   - NEGATIVE
                   - EDGE
                   - SECURITY
                   - PERFORMANCE
                2. Veri setleri gercekci isim, tutar, tarih, durum, selector veya endpoint detaylari icersin.
                3. Secilen frameworke gore kullanilabilir olsun:
                   - KARATE: endpoint path, method, request body, expected status, expected response alanlari.
                   - SELENIUM: kullanici, form inputlari, selectorlar, beklenen UI mesajlari.
                4. Regression icin en az 3 veri varyasyonu, negative icin en az 2 hata varyasyonu ver.
                5. Performance datasinda makul timeout/budget beklentisi belirt.
                6. Security datasinda yetkisiz/eksik token/rol uyumsuzlugu gibi kontrol belirt.
                7. Sadece sade metin veya JSON benzeri liste dondur; markdown kod blogu kullanma.
                """.formatted(
                request.getFramework(),
                request.getTestType(),
                valueOrDash(request.getUserStory()),
                valueOrDash(request.getSwaggerUrl()),
                valueOrDash(request.getApplicationUrl()),
                valueOrDash(request.getAdditionalContext())
        );
    }

    private String clean(String response) {
        String clean = response.trim();
        if (clean.startsWith("```")) {
            int firstNewLine = clean.indexOf('\n');
            if (firstNewLine >= 0) {
                clean = clean.substring(firstNewLine + 1);
            }
            if (clean.endsWith("```")) {
                clean = clean.substring(0, clean.length() - 3);
            }
        }
        return clean.trim();
    }

    private String valueOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
