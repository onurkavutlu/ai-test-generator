package com.testgen.controller;

import com.testgen.config.BadRequestException;
import com.testgen.model.*;
import com.testgen.runner.TestRunnerService;
import com.testgen.service.TestGenerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * AI Test Generator REST API
 *
 * POST /api/v1/tests/generate     → Test üretme isteği oluştur + async üret
 * GET  /api/v1/tests/{id}          → İstek durumunu sorgula
 * GET  /api/v1/tests/{id}/cases   → Üretilen test case'leri getir
 * POST /api/v1/tests/{id}/run     → Test case'leri çalıştır
 * GET  /api/v1/tests               → Tüm istekleri listele
 */
@Slf4j
@Tag(name = "1. AI Test Üretim ve Çalıştırma", description = "Yapay zeka ile Karate, Selenium ve Appium test üretimi ve koşumu API'leri")
@RestController
@RequestMapping("/api/v1/tests")
@RequiredArgsConstructor
public class TestGenerationController {

    private final TestGenerationService testGenerationService;
    private final TestRunnerService testRunnerService;

    /**
     * Test üretme isteği oluştur.
     * Üretim async olarak başlar, status PENDING → GENERATING → GENERATED
     */
    @Operation(summary = "Yapay Zeka ile Test Üretim İsteği Başlat", description = "Verilen kullanıcı hikayesi (User Story) veya OpenAPI/Swagger linkine göre Karate DSL, Selenium veya Appium test senaryolarını asenkron olarak üretir.", tags = { "3. Sunum ve Demo Akışı (Adım Adım)", "1. AI Test Üretim ve Çalıştırma" })
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generate(
            @Valid @RequestBody TestGenerationRequestDto dto,
            @RequestParam(defaultValue = "true") boolean autoRun,
            @RequestParam(required = false) List<String> recipients) {

        validateFrameworkCompatibility(dto);

        var request = TestGenerationRequest.builder()
                .testType(dto.testType())
                .framework(dto.framework())
                .userStory(dto.userStory())
                .swaggerUrl(dto.swaggerUrl())
                .applicationUrl(dto.applicationUrl())
                .appPackage(dto.appPackage())
                .additionalContext(dto.additionalContext())
                .build();

        var saved = testGenerationService.createRequest(request);

        // Async olarak test datası + test üretimini başlat. Varsayılan olarak üretim bitince koşum ve email raporu da tetiklenir.
        var generation = testGenerationService.generateTests(saved.getId());
        if (autoRun) {
            generation.thenRun(() -> testRunnerService.runAllForRequest(saved.getId(), recipients));
        }

        return ResponseEntity.accepted().body(Map.of(
                "requestId", saved.getId(),
                "status", saved.getStatus(),
                "autoRun", autoRun,
                "recipients", recipients != null ? recipients : List.of("(config default)"),
                "message", autoRun
                        ? "AI test datası ve test üretimi başlatıldı. Üretim bitince testler otomatik koşulacak, Allure ve email raporu gönderilecek."
                        : "AI test datası ve test üretimi başlatıldı. requestId ile durumu takip edebilirsiniz."
        ));
    }

    /**
     * İstek durumunu getir
     */
    @Operation(summary = "Test Üretim İstek Durumunu Sorgula", description = "Verilen requestId ile üretim işleminin anlık durumunu sorgular (PENDING, GENERATING, GENERATED, FAILED).", tags = { "3. Sunum ve Demo Akışı (Adım Adım)", "1. AI Test Üretim ve Çalıştırma" })
    @GetMapping("/{requestId}")
    public ResponseEntity<TestGenerationRequestResponseDto> getRequest(@PathVariable String requestId) {
        return ResponseEntity.ok(TestGenerationRequestResponseDto.from(
                testGenerationService.getRequest(requestId)));
    }

    /**
     * Üretilen test case'leri getir
     */
    @Operation(summary = "Üretilen Test Kodlarını Listele", description = "Belirli bir istek sonucu üretilmiş ve veritabanına kaydedilmiş tüm test case içeriklerini ve kodlarını getirir.", tags = { "3. Sunum ve Demo Akışı (Adım Adım)", "1. AI Test Üretim ve Çalıştırma" })
    @GetMapping("/{requestId}/cases")
    public ResponseEntity<List<GeneratedTestCaseResponseDto>> getTestCases(@PathVariable String requestId) {
        return ResponseEntity.ok(testGenerationService.getTestCasesByRequestId(requestId)
                .stream()
                .map(GeneratedTestCaseResponseDto::from)
                .toList());
    }

    /**
     * LLM ve Agent Çıktı Raporunu Getir (JSON)
     */
    @Operation(summary = "LLM ve Agent Rapor Detaylarını Getir", description = "Yapay zeka modellerinin, çoklu agentların ürettiği detaylı analizlerin ve test case kodlarının detaylı raporunu JSON olarak getirir.")
    @GetMapping("/{requestId}/llm-report")
    public ResponseEntity<Map<String, Object>> getLlmReportData(@PathVariable String requestId) {
        var request = testGenerationService.getRequest(requestId);
        var testCases = testGenerationService.getTestCasesByRequestId(requestId);

        java.util.List<Map<String, String>> analyses = new java.util.ArrayList<>();
        String additionalContext = request.getAdditionalContext();
        if (additionalContext != null && additionalContext.contains("## AI AGENT ANALYSIS")) {
            String section = additionalContext.substring(additionalContext.indexOf("## AI AGENT ANALYSIS") + "## AI AGENT ANALYSIS".length()).trim();
            String[] parts = section.split("### ");
            for (String part : parts) {
                part = part.trim();
                if (part.isEmpty()) continue;

                int firstNewLine = part.indexOf('\n');
                if (firstNewLine > 0) {
                    String title = part.substring(0, firstNewLine).trim();
                    String content = part.substring(firstNewLine).trim();
                    analyses.add(Map.of(
                            "title", title,
                            "role", title.replace(" Agent", ""),
                            "analysis", content
                    ));
                } else {
                    analyses.add(Map.of(
                            "title", part,
                            "role", part.replace(" Agent", ""),
                            "analysis", ""
                    ));
                }
            }
        }

        return ResponseEntity.ok(Map.of(
                "requestId", request.getId(),
                "testType", request.getTestType().name(),
                "framework", request.getFramework().name(),
                "userStory", request.getUserStory() != null ? request.getUserStory() : "",
                "createdAt", request.getCreatedAt().toString(),
                "agentAnalyses", analyses,
                "testCases", testCases.stream().map(tc -> {
                    var dto = GeneratedTestCaseResponseDto.from(tc);
                    java.util.Map<String, Object> map = new java.util.HashMap<>();
                    map.put("id", dto.id());
                    map.put("testName", dto.testName());
                    map.put("fileName", dto.fileName());
                    map.put("testSummary", dto.testSummary() != null ? dto.testSummary() : "");
                    map.put("generationNarrative", dto.generationNarrative() != null ? dto.generationNarrative() : "");
                    map.put("improvementNarrative", dto.improvementNarrative() != null ? dto.improvementNarrative() : "");
                    map.put("runStatus", dto.runStatus().name());
                    map.put("executionTimeMs", dto.executionTimeMs() != null ? dto.executionTimeMs() : 0);
                    map.put("testContent", dto.testContent());
                    return map;
                }).toList()
        ));
    }

    /**
     * LLM erişilemediğinde veya kullanıcı kendi case'ini eklemek istediğinde manuel test case ekler.
     */
    @Operation(summary = "İsteğe Manuel Test Case Ekle", description = "Yapay zeka üretimi dışında kullanıcının kendi yazdığı Karate feature veya JUnit Java test kodlarını isteğe el ile bağlamasını sağlar.")
    @PostMapping("/{requestId}/cases")
    public ResponseEntity<GeneratedTestCaseResponseDto> addManualTestCase(
            @PathVariable String requestId,
            @Valid @RequestBody ManualTestCaseRequestDto dto) {

        GeneratedTestCase testCase = GeneratedTestCase.builder()
                .testName(dto.testName())
                .fileName(dto.fileName())
                .testContent(dto.testContent())
                .testSummary(dto.testSummary())
                .framework(dto.framework())
                .runStatus(dto.runStatus())
                .build();

        return ResponseEntity.ok(GeneratedTestCaseResponseDto.from(
                testGenerationService.addManualTestCase(requestId, testCase)));
    }

    /**
     * Belirli bir test case'i çalıştır
     */
    @Operation(summary = "Tek Bir Test Case Çalıştır", description = "Belirtilen testCaseId'ye sahip tek bir testi Karate Runner veya Maven alt süreci üzerinden anında çalıştırır.")
    @PostMapping("/cases/{testCaseId}/run")
    public ResponseEntity<Map<String, String>> runTest(@PathVariable String testCaseId) {
        testRunnerService.runTest(testCaseId);
        return ResponseEntity.accepted().body(Map.of(
                "testCaseId", testCaseId,
                "message", "Test çalıştırması başlatıldı."
        ));
    }

    /**
     * Bir request'e ait TÜM test case'leri çalıştır + Allure raporu üret + email gönder.
     * recipients query param ile alıcıları override edebilirsiniz.
     *
     * POST /api/v1/tests/{requestId}/run-all?recipients=a@b.com,c@d.com
     */
    @Operation(summary = "İsteğe Ait Tüm Testleri Koştur & Raporla", description = "Seçilen isteğe ait tüm testleri çalıştırır. Koşum bitince Allure HTML raporunu derler ve MailHog SMTP servisine rapor maillini iletir.", tags = { "3. Sunum ve Demo Akışı (Adım Adım)", "1. AI Test Üretim ve Çalıştırma" })
    @PostMapping("/{requestId}/run-all")
    public ResponseEntity<Map<String, Object>> runAll(
            @PathVariable String requestId,
            @RequestParam(required = false) List<String> recipients) {

        // Request var mı kontrol et
        testGenerationService.getRequest(requestId);

        testRunnerService.runAllForRequest(requestId, recipients);
        return ResponseEntity.accepted().body(Map.of(
                "requestId", requestId,
                "message", "Tüm testler başlatıldı. Allure raporu ve email bildirimi hazır olduğunda gönderilecek.",
                "recipients", recipients != null ? recipients : List.of("(config default)")
        ));
    }

    /**
     * Tüm üretme isteklerini listele
     */
    @Operation(summary = "Tüm Test İsteklerini Listele", description = "Veritabanında kayıtlı olan tüm geçmiş test üretim isteklerini getirir.")
    @GetMapping
    public ResponseEntity<List<TestGenerationRequestResponseDto>> listAll() {
        return ResponseEntity.ok(testGenerationService.getAllRequests()
                .stream()
                .map(TestGenerationRequestResponseDto::from)
                .toList());
    }

    /**
     * Health check (actuator'a ek)
     */
    @Operation(summary = "Sağlık Kontrolü (Health Check)", description = "Servisin ayakta ve sağlıklı olduğunu doğrular.")
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "AI Test Generator"));
    }

    private void validateFrameworkCompatibility(TestGenerationRequestDto dto) {
        boolean valid = switch (dto.testType()) {
            case BACKEND_API -> dto.framework() == TestFramework.KARATE;
            case FRONTEND_WEB -> dto.framework() == TestFramework.SELENIUM;
            case MOBILE -> dto.framework() == TestFramework.APPIUM;
        };

        if (!valid) {
            throw new BadRequestException(
                    "testType/framework uyumsuz: " + dto.testType() + " icin " + dto.framework()
                            + " kullanilamaz. Gecerli eslesmeler: BACKEND_API/KARATE, "
                            + "FRONTEND_WEB/SELENIUM, MOBILE/APPIUM");
        }
    }
}
