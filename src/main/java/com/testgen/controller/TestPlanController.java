package com.testgen.controller;

import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestPlan;
import com.testgen.model.TestSuite;
import com.testgen.runner.TestRunnerService;
import com.testgen.service.TestPlanService;
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
 * Test Plan yönetimi — Jira/Xray hiyerarşisinin en üst katmanı.
 *
 * POST   /api/v1/plans                        → plan oluştur
 * GET    /api/v1/plans                        → listele
 * GET    /api/v1/plans/{id}                   → detay (suite'ler ve case sayılarıyla)
 * DELETE /api/v1/plans/{id}                   → sil
 * POST   /api/v1/plans/{id}/suites/{suiteId}  → plana suite ekle
 * DELETE /api/v1/plans/{id}/suites/{suiteId}  → plandan suite çıkar
 * POST   /api/v1/plans/{id}/run               → planı koştur (async, Test Execution üretir)
 */
@Tag(name = "7. Test Plan", description = "Test Plan → Test Suite → Test Execution hiyerarşisi; kapsamı istenildiği zaman yeniden koşturma")
@RestController
@RequestMapping("/api/v1/plans")
@RequiredArgsConstructor
public class TestPlanController {

    private final TestPlanService planService;
    private final TestRunnerService testRunnerService;

    @Operation(summary = "Test Plan Oluştur")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, String> body) {
        TestPlan plan = planService.create(body.get("name"), body.get("description"), body.get("version"));
        return ResponseEntity.ok(summaryOf(plan));
    }

    @Operation(summary = "Test Plan Listesi")
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(planService.list().stream()
                .map(p -> summaryOf(planService.get(p.getId())))
                .toList());
    }

    @Operation(summary = "Test Plan Detayı", description = "Plandaki suite'leri, her suite'in case sayısını ve toplam koşulabilir case sayısını döner.")
    @GetMapping("/{planId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String planId) {
        TestPlan plan = planService.get(planId);
        Map<String, Object> out = summaryOf(plan);
        // summaryOf null suite listesine karşı korumalıydı, burası değildi: aynı plan için
        // suiteCount 0 dönerken detay ucu NPE ile 500 veriyordu. Boş liste "plan boş"
        // demektir — sunucu hatası değil.
        out.put("suites", (plan.getSuites() == null ? List.<TestSuite>of() : plan.getSuites())
                .stream().map(TestPlanController::suiteOf).toList());
        out.put("cases", planService.resolveCases(planId).stream().map(TestPlanController::caseOf).toList());
        return ResponseEntity.ok(out);
    }

    @Operation(summary = "Test Plan Sil")
    @DeleteMapping("/{planId}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable String planId) {
        planService.delete(planId);
        return ResponseEntity.ok(Map.of("planId", planId, "message", "Test plan silindi."));
    }

    @Operation(summary = "Plana Suite Ekle")
    @PostMapping("/{planId}/suites/{suiteId}")
    public ResponseEntity<Map<String, Object>> addSuite(@PathVariable String planId,
                                                        @PathVariable String suiteId) {
        return ResponseEntity.ok(summaryOf(planService.addSuite(planId, suiteId)));
    }

    @Operation(summary = "Plandan Suite Çıkar")
    @DeleteMapping("/{planId}/suites/{suiteId}")
    public ResponseEntity<Map<String, Object>> removeSuite(@PathVariable String planId,
                                                           @PathVariable String suiteId) {
        return ResponseEntity.ok(summaryOf(planService.removeSuite(planId, suiteId)));
    }

    @Operation(summary = "Test Planı Koştur",
            description = "Plandaki tüm suite'lerin test case'lerini tek bir Test Execution altında koşturur. "
                    + "Koşum asenkrondur; sonuçlar /api/v1/executions üzerinden izlenir.")
    @PostMapping("/{planId}/run")
    public ResponseEntity<Map<String, Object>> run(@PathVariable String planId) {
        TestPlan plan = planService.get(planId);
        int caseCount = planService.resolveCases(planId).size();
        testRunnerService.runPlan(planId);
        return ResponseEntity.accepted().body(Map.of(
                "planId", planId,
                "planName", plan.getName(),
                "caseCount", caseCount,
                "message", "Plan koşumu başlatıldı. Sonuçları /api/v1/executions üzerinden takip edebilirsiniz."
        ));
    }

    // ── İç dönüştürücüler ─────────────────────────────────────

    private Map<String, Object> summaryOf(TestPlan plan) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", plan.getId());
        out.put("name", plan.getName());
        out.put("description", plan.getDescription());
        out.put("version", plan.getVersion());
        out.put("suiteCount", plan.getSuites() == null ? 0 : plan.getSuites().size());
        out.put("lastExecutedAt", plan.getLastExecutedAt());
        out.put("lastExecutionStatus", plan.getLastExecutionStatus());
        out.put("lastExecutionPassed", plan.getLastExecutionPassed());
        out.put("lastExecutionFailed", plan.getLastExecutionFailed());
        out.put("createdAt", plan.getCreatedAt());
        return out;
    }

    private static Map<String, Object> suiteOf(TestSuite suite) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", suite.getId());
        out.put("name", suite.getName());
        out.put("description", suite.getDescription());
        out.put("caseCount", suite.getTestCases() == null ? 0 : suite.getTestCases().size());
        return out;
    }

    private static Map<String, Object> caseOf(GeneratedTestCase tc) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", tc.getId());
        out.put("testName", tc.getTestName());
        out.put("framework", tc.getFramework());
        out.put("runStatus", tc.getRunStatus());
        appendEvidence(out, tc);
        return out;
    }

    /** Plan görünümüne yalnız güvenli kanıt meta verisini taşır; ham prompt taşınmaz. */
    private static void appendEvidence(Map<String, Object> out, GeneratedTestCase testCase) {
        var request = testCase.getRequest();
        String context = request == null ? "" : request.getAdditionalContext();
        String evidenceType = context != null && context.contains("## OBSERVED USER FLOW")
                ? "OBSERVED_USER_FLOW"
                : context != null && context.contains("## OBSERVED") ? "OBSERVED" : "NONE";
        out.put("requestId", request == null ? null : request.getId());
        out.put("evidenceType", evidenceType);
        out.put("deterministic", testCase.isDeterministic());
        out.put("validationStatus", testCase.getValidationStatus() == null
                ? "NOT_VALIDATED" : testCase.getValidationStatus().name());
    }
}
