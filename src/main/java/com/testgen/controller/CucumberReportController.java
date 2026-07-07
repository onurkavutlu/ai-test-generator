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

    private final CucumberReportService cucumberReportService;
    private final TestGenerationService testGenerationService;

    @Operation(summary = "Cucumber HTML Raporunu Görüntüle",
               description = "Seçili request için Cucumber HTML raporunu tarayıcıda gösterir.")
    @GetMapping(value = "/{requestId}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> getReport(@PathVariable String requestId) {
        return cucumberReportService.readReport(requestId)
                .map(html -> ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html))
                .orElseGet(() -> {
                    // Rapor yoksa hızlıca üret ve sun
                    var testCases = testGenerationService.getTestCasesByRequestId(requestId);
                    if (testCases.isEmpty()) {
                        return ResponseEntity.notFound().<String>build();
                    }
                    cucumberReportService.generateReport(requestId, testCases);
                    return cucumberReportService.readReport(requestId)
                            .map(html -> ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html))
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
