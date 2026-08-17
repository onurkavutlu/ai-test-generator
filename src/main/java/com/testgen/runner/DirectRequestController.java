package com.testgen.runner;

import com.testgen.config.BadRequestException;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestType;
import com.testgen.orchestration.AgentUnavailableException;
import com.testgen.orchestration.FrameworkUnavailableException;
import com.testgen.orchestration.InvalidOrchestrationPlanException;
import com.testgen.orchestration.OrchestrationRequest;
import com.testgen.orchestration.QaOrchestrator;
import com.testgen.orchestration.UnsupportedOrchestrationStepException;
import com.testgen.parser.CurlParser;
import com.testgen.service.TestGenerationService;
import com.testgen.service.TestSuiteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Runner ekranının ad-hoc istek API'leri.
 *
 * POST /api/v1/runner/execute                → isteği anında koşar (status/latency/body)
 * POST /api/v1/runner/generate-from-response → isteği koşar, GERÇEK yanıtı yakalar ve
 *                                              testleri bu yanıta göre üretir (suite'e bağlanabilir)
 */
@Slf4j
@Tag(name = "5. Direkt Runner", description = "Ekrandan girilen endpoint ve request'i anında çalıştırır; gerçek yanıttan test üretir")
@RestController
@RequestMapping("/api/v1/runner")
@RequiredArgsConstructor
public class DirectRequestController {

    private static final int OBSERVED_BODY_MAX = 1500;

    private final DirectRequestService directRequestService;
    private final CurlParser curlParser;
    /** Gözlenen yanıttan türetilmiş gerçekleri prompt'a yazmak için */
    private final ResponseAssertionDeriver assertionDeriver;
    private final TestGenerationService testGenerationService;
    private final TestRunnerService testRunnerService;
    private final TestSuiteService testSuiteService;
    /** Üretim başlamadan önce mevcut agent/framework kayıtlarıyla planı doğrular. */
    private final QaOrchestrator qaOrchestrator;

    @Operation(summary = "Endpoint'e Anında İstek Gönder",
            description = "Verilen URL, method, header ve body ile HTTP isteğini hemen çalıştırır; "
                    + "status kodu, gecikme, yanıt header'ları ve gövdesini döner.")
    @PostMapping(value = "/execute",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DirectRequestService.DirectRunResult> execute(
            @RequestBody DirectRequestService.DirectRunRequest request) {
        return ResponseEntity.ok(directRequestService.execute(request));
    }

    public record CurlParseRequest(String curl) {}

    public record CurlParseResponse(
            String method,
            String methodReason,
            String url,
            Map<String, String> headers,
            String body
    ) {}

    @Operation(summary = "Postman cURL Komutunu Ayrıştır",
            description = "-X olmasa bile cURL kurallarına göre metot, URL, başlık ve gövdeyi çıkarır.")
    @PostMapping(value = "/parse-curl",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CurlParseResponse> parseCurl(@RequestBody CurlParseRequest request) {
        var parsed = curlParser.parse(request.curl());
        if (parsed == null) {
            throw new BadRequestException("cURL içinde geçerli bir http/https URL bulunamadı");
        }
        return ResponseEntity.ok(new CurlParseResponse(
                parsed.method(), CurlParser.describeMethodDetection(request.curl()),
                parsed.url(), parsed.headers(), parsed.body()));
    }

    public record GenerateFromResponseRequest(
            String url,
            String method,
            Map<String, String> headers,
            String body,
            String framework,   // KARATE (varsayılan) | REST_ASSURED
            String suiteId,     // opsiyonel: üretilen case'ler bu suite'e bağlanır
            Boolean autoRun,    // varsayılan true
            String userStory
    ) {}

    @Operation(summary = "Gerçek Yanıttan Test Üret",
            description = "İsteği önce CANLI çalıştırır, dönen gerçek yanıtı (status + body) yakalar ve testleri "
                    + "bu gözlenen davranışa göre üretir — assertion'lar tahmin değil, gözlemdir. "
                    + "suiteId verilirse üretilen case'ler otomatik o suite'e bağlanır.")
    @PostMapping(value = "/generate-from-response",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> generateFromResponse(
            @RequestBody GenerateFromResponseRequest req) {

        // 1) Framework doğrulaması (API testi — Selenium bu akışta anlamsız)
        TestFramework framework = parseFramework(req.framework());

        // 2) İsteği canlı çalıştır, gerçek yanıtı yakala
        DirectRequestService.DirectRunResult observed = directRequestService.execute(
                new DirectRequestService.DirectRunRequest(
                        req.url(), req.method(), req.headers(), req.body(), null));
        if (observed.error() != null) {
            throw new BadRequestException(
                    "Hedef yanıt vermedi, test üretilemez: " + observed.error());
        }

        String method = req.method() == null ? "GET" : req.method().toUpperCase(Locale.ROOT);
        String observedContext = buildObservedContext(method, req.url(), observed);
        String curl = buildCurl(method, req.url(), req.headers(), req.body());

        // 3) Üretim isteğini oluştur — gözlenen yanıt bağlamda, ajanlar da bunu görür
        TestGenerationRequest genRequest = TestGenerationRequest.builder()
                .testType(TestType.BACKEND_API)
                .framework(framework)
                .userStory(req.userStory() != null && !req.userStory().isBlank()
                        ? req.userStory()
                        : "Gerçek istek/yanıt çifti doğrulanmalı: " + method + " " + req.url())
                .rawPayload(framework == TestFramework.KARATE ? curl : null)
                .payloadType(framework == TestFramework.KARATE ? "CAPTURED" : null)
                .additionalContext(observedContext)
                .build();

        // Canlı gözlemden SONRA, kalıcı istek ve async üretimden ÖNCE planı doğrula.
        // Böylece eksik bir agent/framework için kullanıcı 202 alıp sonradan asenkron
        // hata görmez; aynı zamanda gözlemsiz üretim kapısı bu akışta korunur.
        validateGenerationPlan(genRequest);
        var saved = testGenerationService.createRequest(genRequest);

        // 4) Async üretim; bitince suite bağlama + opsiyonel otomatik koşum
        boolean autoRun = req.autoRun() == null || req.autoRun();
        String suiteId = req.suiteId();
        testGenerationService.generateTests(saved.getId()).thenRun(() -> {
            if (suiteId != null && !suiteId.isBlank()) {
                testSuiteService.attachRequestCases(suiteId, saved.getId());
            }
            if (autoRun) {
                testRunnerService.runAllForRequest(saved.getId(), null);
            }
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestId", saved.getId());
        out.put("suiteId", suiteId);
        out.put("autoRun", autoRun);
        out.put("observedStatus", observed.status());
        out.put("observedLatencyMs", observed.latencyMs());
        out.put("observedBodyPreview", truncate(observed.body(), 300));
        out.put("message", "Gerçek yanıt yakalandı; testler gözlenen davranışa göre üretiliyor"
                + (suiteId != null ? " ve suite'e bağlanacak" : "")
                + (autoRun ? ", üretim bitince otomatik koşulacak." : "."));
        return ResponseEntity.accepted().body(out);
    }

    private void validateGenerationPlan(TestGenerationRequest request) {
        try {
            qaOrchestrator.execute(OrchestrationRequest.from(request));
        } catch (InvalidOrchestrationPlanException | AgentUnavailableException
                 | FrameworkUnavailableException | UnsupportedOrchestrationStepException e) {
            throw new BadRequestException("Üretim planı doğrulanamadı: " + e.getMessage());
        }
    }

    private TestFramework parseFramework(String raw) {
        if (raw == null || raw.isBlank()) {
            return TestFramework.KARATE;
        }
        try {
            TestFramework fw = TestFramework.valueOf(raw.toUpperCase(Locale.ROOT));
            if (fw == TestFramework.SELENIUM) {
                throw new BadRequestException("Yanıt-temelli üretim API testleri içindir: KARATE veya REST_ASSURED kullanın.");
            }
            return fw;
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Geçersiz framework: " + raw + " (KARATE | REST_ASSURED)");
        }
    }

    private String buildObservedContext(String method, String url,
                                        DirectRequestService.DirectRunResult observed) {
        String contentType = observed.headers().entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase("content-type"))
                .map(Map.Entry::getValue).findFirst().orElse("(yok)");
        return """
                ## OBSERVED RESPONSE (canlı koşumdan yakalandı — assertion'lar BU yanıta göre yazılır)
                İstek        : %s %s
                Gözlenen Status: %d
                Content-Type : %s
                Gözlenen Body (kısaltılmış):
                %s

                %s
                KURALLAR (KRİTİK):
                - Happy path assertion'larını YALNIZCA yukarıdaki gözlenen status ve body alanlarına göre yaz.
                - Gözlenmeyen alan, status kodu veya header UYDURMA.
                - Yanıtta auth izi yoksa 401/403 senaryosu YAZMA.
                - Body'de görmediğin alan için match yazma; gördüğün alanların değer/tiplerini doğrula.
                """.formatted(method, url, observed.status(), contentType,
                truncate(observed.body(), OBSERVED_BODY_MAX),
                // Ham gövde yanlış okunabiliyor; türetilmiş gerçekler tek anlamlı ve çok daha kısa
                assertionDeriver.toPromptFacts(observed.assertions()));
    }

    private String buildCurl(String method, String url, Map<String, String> headers, String body) {
        StringBuilder sb = new StringBuilder("curl -X ").append(method).append(" '").append(url).append("'");
        if (headers != null) {
            headers.forEach((k, v) -> sb.append(" -H '").append(k).append(": ").append(v).append("'"));
        }
        if (body != null && !body.isBlank()) {
            sb.append(" -d '").append(body.replace("'", "\\'")).append("'");
        }
        return sb.toString();
    }

    private static String truncate(String text, int max) {
        if (text == null) return "(boş)";
        return text.length() > max ? text.substring(0, max) + "…[kısaltıldı]" : text;
    }
}
