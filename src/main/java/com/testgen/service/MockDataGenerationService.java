package com.testgen.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testgen.llm.LlmService;
import com.testgen.model.GeneratedTestCase;
import com.testgen.model.MockResponse;
import com.testgen.model.TestFramework;
import com.testgen.repository.MockResponseRepository;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MockDataGenerationService {

    private final LlmService llmService;
    private final MockResponseRepository mockResponseRepository;
    private final ObjectMapper objectMapper;

    /**
     * Karate feature test kodunu analiz eder, LLM ile mock verileri üretip veritabanına kaydeder.
     */
    public void generateMockDataForTestCase(GeneratedTestCase testCase) {
        if (testCase.getFramework() != TestFramework.KARATE) {
            return;
        }

        log.info("LLM ile mock veri üretiliyor - testCase: {}", testCase.getTestName());

        String prompt = """
                Asagidaki Karate DSL test senaryosunu incele. Bu test senaryosunun yerel bir mock sunucu üzerinde basariyla calisabilmesi icin donmesi gereken mock HTTP yanitlarini (path, method, statusCode, responseBody) JSON formatinda uret.
                
                ## Karate Test Kodu:
                %s
                
                ## Gorevin:
                1. Testteki tum path'leri ve method'lari belirle.
                2. Bu endpoint'lerin basarili (200, 201) ve hata senaryolari (400, 404 vb.) icin test kodunun bekledigi mock yanitlari tasarla.
                3. Sadece asagidaki JSON formatinda bir liste dondur. Baska hicbir aciklama, yorum veya markdown ```json blogu ekleme.
                
                ## Cikti Formatı:
                [
                  {
                    "path": "/pet/10",
                    "method": "GET",
                    "statusCode": 200,
                    "responseBody": "{\\"id\\": 10, \\"name\\": \\"Mavi\\"}"
                  }
                ]
                """.formatted(testCase.getTestContent());

        try {
            String llmResponse = llmService.generateTestCase(prompt);
            String cleanJson = cleanLlmJson(llmResponse);

            log.debug("LLM mock data response: {}", cleanJson);

            List<MockDto> mocks = objectMapper.readValue(cleanJson, new TypeReference<List<MockDto>>() {});

            for (MockDto dto : mocks) {
                String path = dto.getPath();
                if (!path.startsWith("/")) {
                    path = "/" + path;
                }

                MockResponse mockResponse = MockResponse.builder()
                        .path(path)
                        .method(dto.getMethod().toUpperCase())
                        .statusCode(dto.getStatusCode())
                        .responseBody(dto.getResponseBody())
                        .build();

                // Save or update existing path/method
                var existing = mockResponseRepository.findByPathAndMethod(mockResponse.getPath(), mockResponse.getMethod());
                if (existing.isPresent()) {
                    var mock = existing.get();
                    mock.setStatusCode(mockResponse.getStatusCode());
                    mock.setResponseBody(mockResponse.getResponseBody());
                    mockResponseRepository.save(mock);
                } else {
                    mockResponseRepository.save(mockResponse);
                }
            }

            log.info("{} adet mock endpoint başarıyla veritabanına kaydedildi.", mocks.size());

        } catch (Exception e) {
            log.error("Mock data üretimi/kaydı sırasında hata oluştu", e);
        }
    }

    /**
     * Karate feature test kodundaki base URL tanımını yerel mock servise yönlendirir.
     */
    public String redirectKarateUrl(String testContent) {
        if (testContent == null) return null;
        // Match lines like "* url 'http://...'" or "* url "http://..."" and replace with local mock server endpoint
        return testContent.replaceAll("(?m)^\\s*(\\*|Given|And)\\s+url\\s+['\"][^'\"]+['\"]", "* url 'http://localhost:8080/api/v1/mock'");
    }

    private String cleanLlmJson(String content) {
        if (content == null) return "[]";
        String clean = content.trim();
        if (clean.startsWith("```")) {
            int firstNewLine = clean.indexOf('\n');
            if (firstNewLine != -1) {
                clean = clean.substring(firstNewLine + 1);
            }
            if (clean.endsWith("```")) {
                clean = clean.substring(0, clean.length() - 3);
            }
        }
        return clean.trim();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MockDto {
        private String path;
        private String method;
        private int statusCode;
        private String responseBody;
    }
}
