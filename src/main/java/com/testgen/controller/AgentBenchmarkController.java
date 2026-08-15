package com.testgen.controller;

import com.testgen.model.*;
import com.testgen.service.AgentBenchmarkService;
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
 * Ajan ölçüm koşumu — çok-ajanlı analizin katkısını ölçer.
 *
 * POST /api/v1/benchmarks/agents        → koşum başlat (async)
 * GET  /api/v1/benchmarks/agents        → koşum listesi
 * GET  /api/v1/benchmarks/agents/{id}   → karşılaştırma raporu
 */
@Tag(name = "9. Ajan Ölçümü", description = "Çok-ajanlı analizin üretim kalitesine ve maliyetine etkisini ölçen A/B koşumu")
@RestController
@RequestMapping("/api/v1/benchmarks/agents")
@RequiredArgsConstructor
public class AgentBenchmarkController {

    private final AgentBenchmarkService benchmarkService;

    public record BenchmarkRequestDto(
            String name,
            TestType testType,
            TestFramework framework,
            String userStory,
            String additionalContext,
            String swaggerUrl,
            String applicationUrl,
            Integer repetitions,
            Boolean runTests,
            BenchmarkComparison comparison
    ) {}

    @Operation(summary = "Ölçüm Koşumu Başlat",
            description = "Aynı girdiyi ajanlar AÇIK ve KAPALI olmak üzere iki kolda üretir; "
                    + "üretim kalitesini (makine doğrulaması) ve LLM maliyetini karşılaştırır. "
                    + "Kollar sırayla koşar, koşum asenkrondur.")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> start(@RequestBody BenchmarkRequestDto dto) {
        AgentBenchmarkRun run = benchmarkService.create(AgentBenchmarkRun.builder()
                .name(dto.name())
                .testType(dto.testType())
                .framework(dto.framework())
                .userStory(dto.userStory())
                .additionalContext(dto.additionalContext())
                .swaggerUrl(dto.swaggerUrl())
                .applicationUrl(dto.applicationUrl())
                .repetitions(dto.repetitions() == null ? 1 : dto.repetitions())
                .runTests(Boolean.TRUE.equals(dto.runTests()))
                .comparison(dto.comparison() == null ? BenchmarkComparison.AGENTS_ON_OFF : dto.comparison())
                .build());

        benchmarkService.execute(run.getId());

        return ResponseEntity.accepted().body(Map.of(
                "id", run.getId(),
                "name", run.getName(),
                "repetitions", run.getRepetitions(),
                "totalGenerations", run.getRepetitions() * 2,
                "message", "Ölçüm koşumu başlatıldı. Sonucu GET /api/v1/benchmarks/agents/{id} ile izleyebilirsiniz."
        ));
    }

    @Operation(summary = "Ölçüm Koşumu Listesi")
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(benchmarkService.list().stream()
                .map(AgentBenchmarkController::summaryOf)
                .toList());
    }

    @Operation(summary = "Karşılaştırma Raporu",
            description = "İki kolun ölçülen ortalamalarını ve aradaki farkı döner.")
    @GetMapping("/{runId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String runId) {
        AgentBenchmarkRun run = benchmarkService.get(runId);
        Map<String, Object> out = summaryOf(run);

        List<BenchmarkArm> arms = run.getComparison().arms();
        Map<String, Object> armA = aggregate(run.getResults(), arms.get(0));
        Map<String, Object> armB = aggregate(run.getResults(), arms.get(1));
        out.put("comparison", run.getComparison());
        out.put(arms.get(0).name(), armA);
        out.put(arms.get(1).name(), armB);
        // Geriye donuk uyumluluk: on/off ekseninde eski alan adlari da doldurulur
        if (run.getComparison() == BenchmarkComparison.AGENTS_ON_OFF) {
            out.put("withAgents", armA);
            out.put("withoutAgents", armB);
        }
        out.put("delta", delta(armA, armB));
        out.put("results", run.getResults().stream().map(AgentBenchmarkController::resultOf).toList());
        return ResponseEntity.ok(out);
    }

    // ── İç dönüştürücüler ─────────────────────────────────────

    private static Map<String, Object> summaryOf(AgentBenchmarkRun run) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", run.getId());
        out.put("name", run.getName());
        out.put("status", run.getStatus());
        out.put("testType", run.getTestType());
        out.put("framework", run.getFramework());
        out.put("repetitions", run.getRepetitions());
        out.put("runTests", run.isRunTests());
        out.put("comparison", run.getComparison());
        out.put("startedAt", run.getStartedAt());
        out.put("finishedAt", run.getFinishedAt());
        out.put("errorMessage", run.getErrorMessage());
        out.put("createdAt", run.getCreatedAt());
        return out;
    }

    /** Bir kolun ölçümlerinin ortalaması. Ölçüm yoksa alanlar null kalır — sıfır uydurulmaz. */
    private static Map<String, Object> aggregate(List<AgentBenchmarkResult> all, BenchmarkArm arm) {
        List<AgentBenchmarkResult> arms = all.stream().filter(r -> r.getArm() == arm).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("arm", arm);
        out.put("samples", arms.size());
        if (arms.isEmpty()) {
            return out;
        }
        out.put("avgCaseCount", avg(arms.stream().mapToDouble(AgentBenchmarkResult::getCaseCount)));
        out.put("avgValidCases", avg(arms.stream().mapToDouble(AgentBenchmarkResult::getValidCases)));
        out.put("avgInvalidCases", avg(arms.stream().mapToDouble(AgentBenchmarkResult::getInvalidCases)));
        out.put("avgValidRatePct", avg(arms.stream().mapToDouble(AgentBenchmarkResult::getValidRate)));
        out.put("avgValidationRetries", avg(arms.stream().mapToDouble(AgentBenchmarkResult::getValidationRetries)));
        out.put("avgLlmCalls", avg(arms.stream().mapToDouble(AgentBenchmarkResult::getLlmCalls)));
        out.put("avgLlmDurationMs", avg(arms.stream().mapToDouble(AgentBenchmarkResult::getLlmDurationMs)));
        out.put("avgLlmPromptChars", avg(arms.stream().mapToDouble(AgentBenchmarkResult::getLlmPromptChars)));
        out.put("avgGenerationMs", avg(arms.stream().mapToDouble(AgentBenchmarkResult::getGenerationDurationMs)));

        List<AgentBenchmarkResult> executed = arms.stream()
                .filter(r -> r.getTotalScenarios() != null).toList();
        if (!executed.isEmpty()) {
            double total = executed.stream().mapToInt(AgentBenchmarkResult::getTotalScenarios).sum();
            double passed = executed.stream().mapToInt(AgentBenchmarkResult::getPassedScenarios).sum();
            out.put("scenariosTotal", (int) total);
            out.put("scenariosPassed", (int) passed);
            out.put("scenarioPassRatePct", total == 0 ? null : round(passed * 100.0 / total));
        }
        return out;
    }

    /** İki kol arasındaki fark (ajanlı − ajansız). Kollardan biri ölçülmediyse null döner. */
    private static Map<String, Object> delta(Map<String, Object> withAgents, Map<String, Object> without) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("avgValidRatePct", "avgValidCases", "avgInvalidCases",
                "avgLlmCalls", "avgLlmDurationMs", "avgLlmPromptChars", "scenarioPassRatePct")) {
            Object a = withAgents.get(key);
            Object b = without.get(key);
            out.put(key, (a instanceof Number na && b instanceof Number nb)
                    ? round(na.doubleValue() - nb.doubleValue())
                    : null);
        }
        return out;
    }

    private static Map<String, Object> resultOf(AgentBenchmarkResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("arm", r.getArm());
        out.put("iteration", r.getIteration());
        out.put("requestId", r.getRequestId());
        out.put("caseCount", r.getCaseCount());
        out.put("validCases", r.getValidCases());
        out.put("invalidCases", r.getInvalidCases());
        out.put("skippedCases", r.getSkippedCases());
        out.put("validRatePct", round(r.getValidRate()));
        out.put("validationRetries", r.getValidationRetries());
        out.put("llmCalls", r.getLlmCalls());
        out.put("llmDurationMs", r.getLlmDurationMs());
        out.put("llmPromptChars", r.getLlmPromptChars());
        out.put("generationDurationMs", r.getGenerationDurationMs());
        out.put("totalScenarios", r.getTotalScenarios());
        out.put("passedScenarios", r.getPassedScenarios());
        out.put("errorMessage", r.getErrorMessage());
        return out;
    }

    private static Double avg(java.util.stream.DoubleStream values) {
        return values.average().stream().boxed().findFirst().map(AgentBenchmarkController::round).orElse(null);
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
