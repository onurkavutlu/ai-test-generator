package com.testgen.controller;

import com.testgen.model.TestExecution;
import com.testgen.model.TestExecutionResult;
import com.testgen.runner.TestRunnerService;
import com.testgen.service.TestExecutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test Execution — koşum geçmişi ve yeniden koşum.
 *
 * GET  /api/v1/executions                 → koşum geçmişi (planId / suiteId ile filtrelenebilir)
 * GET  /api/v1/executions/{id}            → koşum detayı (case bazlı sonuçlarla)
 * POST /api/v1/executions/{id}/rerun      → aynı kapsamı yeniden koştur (yeni koşum kaydı üretir)
 */
@Tag(name = "8. Test Execution", description = "Koşum geçmişi, case bazlı sonuçlar ve aynı kapsamı yeniden koşturma")
@RestController
@RequestMapping("/api/v1/executions")
@RequiredArgsConstructor
public class TestExecutionController {

    private final TestExecutionService executionService;
    private final TestRunnerService testRunnerService;

    @Operation(summary = "Koşum Geçmişi",
            description = "Tüm koşumları en yeniden eskiye listeler. planId veya suiteId ile filtrelenebilir.")
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(required = false) String planId,
            @RequestParam(required = false) String suiteId) {
        return ResponseEntity.ok(executionService.list(planId, suiteId).stream()
                .map(TestExecutionController::summaryOf)
                .toList());
    }

    @Operation(summary = "Koşum Detayı",
            description = "Koşumun özetini ve koşulan her test case'in o andaki sonucunu döner.")
    @GetMapping("/{executionId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String executionId) {
        TestExecution execution = executionService.get(executionId);
        Map<String, Object> out = summaryOf(execution);
        out.put("results", execution.getResults().stream()
                .map(TestExecutionController::resultOf)
                .toList());
        return ResponseEntity.ok(out);
    }

    @Operation(summary = "Koşumu Yeniden Çalıştır",
            description = "Geçmiş koşumun kapsamındaki test case'leri yeniden koşturur ve YENİ bir koşum kaydı üretir; "
                    + "eski kayıt olduğu gibi korunur. Silinmiş case'ler kapsam dışı kalır.")
    @PostMapping("/{executionId}/rerun")
    public ResponseEntity<Map<String, Object>> rerun(@PathVariable String executionId) {
        TestExecution source = executionService.get(executionId);
        int caseCount = executionService.resolveCasesForRerun(executionId).size();
        testRunnerService.rerunExecution(executionId);
        return ResponseEntity.accepted().body(Map.of(
                "sourceExecutionId", executionId,
                "sourceName", source.getName(),
                "caseCount", caseCount,
                "message", "Yeniden koşum başlatıldı. Yeni koşum kaydı /api/v1/executions altında görünecek."
        ));
    }

    // ── İç dönüştürücüler ─────────────────────────────────────

    private static Map<String, Object> summaryOf(TestExecution e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", e.getId());
        out.put("name", e.getName());
        out.put("status", e.getStatus());
        out.put("trigger", e.getTrigger());
        out.put("planId", e.getPlanId());
        out.put("planName", e.getPlanName());
        out.put("suiteId", e.getSuiteId());
        out.put("suiteName", e.getSuiteName());
        out.put("sourceExecutionId", e.getSourceExecutionId());
        out.put("totalCases", e.getTotalCases());
        out.put("passedCases", e.getPassedCases());
        out.put("failedCases", e.getFailedCases());
        out.put("passRate", Math.round(e.getPassRate() * 10) / 10.0);
        out.put("startedAt", e.getStartedAt());
        out.put("finishedAt", e.getFinishedAt());
        out.put("durationMs", e.getDurationMs());
        out.put("createdAt", e.getCreatedAt());
        return out;
    }

    private static Map<String, Object> resultOf(TestExecutionResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("testCaseId", r.getTestCaseId());
        out.put("testName", r.getTestName());
        out.put("framework", r.getFramework());
        out.put("runStatus", r.getRunStatus());
        out.put("totalScenarios", r.getTotalScenarios());
        out.put("passedScenarios", r.getPassedScenarios());
        out.put("failedScenarios", r.getFailedScenarios());
        out.put("executionTimeMs", r.getExecutionTimeMs());
        out.put("runOutput", r.getRunOutput());
        out.put("recordedAt", r.getRecordedAt());
        return out;
    }
}
