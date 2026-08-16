package com.testgen.controller;

import com.testgen.llm.LlmCallReport;
import com.testgen.llm.LlmReportStore;
import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestGenerationRequest;
import com.testgen.service.TestGenerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM rapor controller'ı.
 * /tests/{id}/llm-report  → HTML view (Thymeleaf)
 * /api/v1/llm/calls        → JSON: tüm LLM çağrı geçmişi
 * /api/v1/llm/summary      → JSON: özet istatistikler
 */
@Tag(name = "5. LLM Üretim Raporu", description = "LLM çağrı geçmişi, üretilen içerikler ve istatistikler")
@RequiredArgsConstructor
@Controller
@RequestMapping
public class LlmReportController {

    private final TestGenerationService testGenerationService;
    private final LlmReportStore        llmReportStore;

    // ── HTML Rapor ────────────────────────────────────────────
    @GetMapping("/tests/{requestId}/llm-report")
    public String getLlmReport(@PathVariable String requestId, Model model) {
        TestGenerationRequest request = testGenerationService.getRequest(requestId);
        List<GeneratedTestCase> testCases = testGenerationService.getTestCasesByRequestId(requestId);
        List<GeneratedTestCaseResponseDto> testCaseDtos = testCases.stream()
                .map(GeneratedTestCaseResponseDto::from)
                .toList();

        List<AgentAnalysis> analyses = parseAgentAnalyses(request.getAdditionalContext());

        model.addAttribute("request", request);
        model.addAttribute("testCases", testCaseDtos);
        model.addAttribute("analyses", analyses);
        model.addAttribute("formattedDate",
                request.getCreatedAt().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")));
        model.addAttribute("llmSummary", llmReportStore.summary());

        return "llm-report";
    }

    // ── JSON API: tüm LLM çağrıları ──────────────────────────
    @Operation(summary = "LLM Çağrı Geçmişini Getir",
               description = "Bu oturumdaki tüm LLM çağrılarını model, tip, süre ve token tahminiyle listeler.")
    @GetMapping("/api/v1/llm/calls")
    @org.springframework.web.bind.annotation.ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getLlmCalls(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String requestId) {

        // Locale.ROOT şart: Türkçe locale'de "generic".toUpperCase() → "GENERİC" olur ve
        // hiçbir kayda eşleşmez; filtre sessizce boş liste döner.
        List<LlmCallReport> reports;
        if (requestId != null && !requestId.isBlank()) {
            reports = llmReportStore.byRequest(requestId);
        } else if (type != null) {
            reports = llmReportStore.byType(type.toUpperCase(java.util.Locale.ROOT));
        } else {
            reports = llmReportStore.all();
        }

        List<Map<String, Object>> result = reports.stream().map(r -> {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("calledAt",              r.calledAt());
            m.put("requestId",             r.requestId());
            m.put("phase",                 r.phase());
            m.put("model",                 r.model());
            m.put("callType",              r.callType());
            m.put("success",               r.success());
            m.put("durationMs",            r.durationMs());
            m.put("promptChars",           r.promptChars());
            m.put("responseChars",         r.responseChars());
            m.put("estimatedPromptTokens", r.estimatedPromptTokens());
            m.put("estimatedResponseTokens", r.estimatedResponseTokens());
            m.put("promptSummary",         r.promptSummary());
            m.put("errorMessage",          r.errorMessage());
            return m;
        }).toList();

        return ResponseEntity.ok(result);
    }

    // ── JSON API: özet istatistikler ─────────────────────────
    @Operation(summary = "LLM Özet İstatistiklerini Getir",
               description = "Toplam çağrı sayısı, başarı oranı, ortalama süre ve tahmini token kullanımı.")
    @GetMapping("/api/v1/llm/summary")
    @org.springframework.web.bind.annotation.ResponseBody
    public ResponseEntity<LlmReportStore.LlmCallSummary> getLlmSummary(
            @RequestParam(required = false) String requestId) {
        // requestId verilirse yalnızca o üretimin maliyeti döner: kaç çağrı, kaç ms,
        // kaç token. Toplam sayılar bir isteğin maliyetini göstermez.
        return ResponseEntity.ok(requestId == null || requestId.isBlank()
                ? llmReportStore.summary()
                : llmReportStore.summaryFor(requestId));
    }

    // ── Yardımcılar ───────────────────────────────────────────
    private List<AgentAnalysis> parseAgentAnalyses(String additionalContext) {
        List<AgentAnalysis> list = new ArrayList<>();
        if (additionalContext == null || !additionalContext.contains("## AI AGENT ANALYSIS")) {
            return list;
        }
        String section = additionalContext.substring(
                additionalContext.indexOf("## AI AGENT ANALYSIS") + "## AI AGENT ANALYSIS".length()).trim();
        for (String part : section.split("### ")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            int nl = part.indexOf('\n');
            if (nl > 0) {
                list.add(new AgentAnalysis(
                        part.substring(0, nl).trim(),
                        part.substring(0, nl).trim().replace(" Agent", ""),
                        part.substring(nl).trim()));
            } else {
                list.add(new AgentAnalysis(part, part.replace(" Agent", ""), ""));
            }
        }
        return list;
    }

    @Data
    public static class AgentAnalysis {
        private final String title;
        private final String role;
        private final String analysis;
    }
}
