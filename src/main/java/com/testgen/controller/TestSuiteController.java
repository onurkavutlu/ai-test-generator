package com.testgen.controller;

import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestSuite;
import com.testgen.runner.TestRunnerService;
import com.testgen.service.TestSuiteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test Suite yönetimi:
 *
 * POST   /api/v1/suites                       → suite oluştur
 * GET    /api/v1/suites                       → listele
 * GET    /api/v1/suites/{id}                  → detay (case'lerle)
 * DELETE /api/v1/suites/{id}                  → sil
 * POST   /api/v1/suites/{id}/cases/{caseId}   → case ekle
 * DELETE /api/v1/suites/{id}/cases/{caseId}   → case çıkar
 * POST   /api/v1/suites/{id}/run              → suite'i koştur (async)
 */
@Tag(name = "6. Test Suite", description = "Farklı isteklerden gelen test case'leri paketleyip istenildiği zaman yeniden koşturma")
@RestController
@RequestMapping("/api/v1/suites")
@RequiredArgsConstructor
public class TestSuiteController {

    private final TestSuiteService suiteService;
    private final TestRunnerService testRunnerService;

    @Operation(summary = "Suite Oluştur")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, String> body) {
        TestSuite suite = suiteService.create(body.get("name"), body.get("description"));
        return ResponseEntity.ok(summaryOf(suite, 0));
    }

    @Operation(summary = "Suite Listesi")
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(suiteService.list().stream()
                .map(s -> summaryOf(s, suiteService.get(s.getId()).getTestCases().size()))
                .toList());
    }

    @Operation(summary = "Suite Detayı (case'lerle)")
    @GetMapping("/{suiteId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String suiteId) {
        TestSuite suite = suiteService.get(suiteId);
        Map<String, Object> out = summaryOf(suite, suite.getTestCases().size());
        out.put("testCases", suite.getTestCases().stream().map(this::caseOf).toList());
        return ResponseEntity.ok(out);
    }

    @Operation(summary = "Suite Sil")
    @DeleteMapping("/{suiteId}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable String suiteId) {
        suiteService.delete(suiteId);
        return ResponseEntity.ok(Map.of("message", "Suite silindi", "suiteId", suiteId));
    }

    @Operation(summary = "Suite'e Case Ekle", description = "Herhangi bir üretim isteğinden (veya manuel eklenen) case suite'e bağlanır.")
    @PostMapping("/{suiteId}/cases/{caseId}")
    public ResponseEntity<Map<String, Object>> addCase(@PathVariable String suiteId, @PathVariable String caseId) {
        TestSuite suite = suiteService.addCase(suiteId, caseId);
        return ResponseEntity.ok(summaryOf(suite, suite.getTestCases().size()));
    }

    @Operation(summary = "Suite'ten Case Çıkar")
    @DeleteMapping("/{suiteId}/cases/{caseId}")
    public ResponseEntity<Map<String, Object>> removeCase(@PathVariable String suiteId, @PathVariable String caseId) {
        TestSuite suite = suiteService.removeCase(suiteId, caseId);
        return ResponseEntity.ok(summaryOf(suite, suite.getTestCases().size()));
    }

    @Operation(summary = "Suite'i Koştur", description = "Paketteki tüm case'leri paralel koşar; sonuçlar case bazında güncellenir, özet suite'e yazılır.")
    @PostMapping("/{suiteId}/run")
    public ResponseEntity<Map<String, String>> run(@PathVariable String suiteId) {
        suiteService.get(suiteId); // 404 kontrolü
        testRunnerService.runSuite(suiteId);
        return ResponseEntity.accepted().body(Map.of(
                "suiteId", suiteId,
                "message", "Suite koşumu başlatıldı. Detay endpoint'inden sonuçları takip edebilirsiniz."));
    }

    private Map<String, Object> summaryOf(TestSuite s, int caseCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("description", s.getDescription() != null ? s.getDescription() : "");
        m.put("caseCount", caseCount);
        m.put("lastRunAt", s.getLastRunAt() != null ? s.getLastRunAt().toString() : null);
        m.put("lastRunPassed", s.getLastRunPassed());
        m.put("lastRunFailed", s.getLastRunFailed());
        m.put("createdAt", s.getCreatedAt().toString());
        return m;
    }

    private Map<String, Object> caseOf(GeneratedTestCase c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("testName", c.getTestName());
        m.put("fileName", c.getFileName());
        m.put("framework", c.getFramework().name());
        m.put("runStatus", c.getRunStatus() != null ? c.getRunStatus().name() : "NOT_RUN");
        m.put("passedScenarios", c.getPassedScenarios());
        m.put("totalScenarios", c.getTotalScenarios());
        m.put("lastRunAt", c.getLastRunAt() != null ? c.getLastRunAt().toString() : null);
        return m;
    }
}
