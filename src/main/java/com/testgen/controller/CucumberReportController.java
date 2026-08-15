package com.testgen.controller;

import com.testgen.report.CucumberReportService;
import com.testgen.service.TestGenerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Cucumber HTML raporlarını sunar.
 *
 * GET /reports/cucumber/{requestId}        → HTML rapor (tarayıcıda aç)
 * POST /reports/cucumber/{requestId}/build → Raporu yeniden üret
 */
@Tag(name = "4. Cucumber Raporlama", description = "Cucumber BDD HTML rapor üretimi ve görüntüleme")
@RestController
@RequestMapping("/reports/cucumber")
@RequiredArgsConstructor
public class CucumberReportController {

    /** text/html;charset=UTF-8 — charset olmadan Türkçe karakterler bozulur. */
    private static final MediaType HTML_UTF8 =
            new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8);

    private final CucumberReportService cucumberReportService;
    private final TestGenerationService testGenerationService;

    @Operation(summary = "Cucumber HTML Raporunu Görüntüle",
               description = "Seçili request için Cucumber HTML raporunu tarayıcıda gösterir.")
    /**
     * Charset BİLEREK açık yazılıyor. {@code MediaType.TEXT_HTML} charset taşımaz ve
     * Spring bu durumda ISO-8859-1 varsayar; Türkçe senaryo adları ("Ürün silinemez")
     * tarayıcıya bozuk ulaşır ("Ã¼"). Rapor içeriği UTF-8 üretildiği için sunum da
     * UTF-8 olarak işaretlenmelidir.
     */
    @GetMapping(value = "/{requestId}", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> getReport(@PathVariable String requestId) {
        return cucumberReportService.readReport(requestId)
                .map(html -> ResponseEntity.ok().contentType(HTML_UTF8).body(html))
                .orElseGet(() -> {
                    // Rapor yoksa hızlıca üret ve sun
                    var testCases = testGenerationService.getTestCasesByRequestId(requestId);
                    if (testCases.isEmpty()) {
                        return ResponseEntity.notFound().<String>build();
                    }
                    cucumberReportService.generateReport(requestId, testCases);
                    return cucumberReportService.readReport(requestId)
                            .map(html -> ResponseEntity.ok().contentType(HTML_UTF8).body(html))
                            .orElseGet(() -> ResponseEntity.internalServerError()
                                    .<String>build());
                });
    }

    @Operation(summary = "Cucumber Raporunu Yeniden Üret",
               description = "İlgili request'in test sonuçlarından Cucumber HTML raporunu yeniden oluşturur.")
    @PostMapping("/{requestId}/build")
    public ResponseEntity<String> buildReport(@PathVariable String requestId) {
        var testCases = testGenerationService.getTestCasesByRequestId(requestId);
        if (testCases.isEmpty()) {
            return ResponseEntity.badRequest().body("Bu request için test case bulunamadı.");
        }
        var path = cucumberReportService.generateReport(requestId, testCases);
        if (path == null) {
            return ResponseEntity.internalServerError().body("Rapor üretilemedi.");
        }
        return ResponseEntity.ok("Rapor üretildi: /reports/cucumber/" + requestId);
    }
}
