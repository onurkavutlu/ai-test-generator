package com.testgen.llm;

import com.testgen.model.LlmCallLog;
import com.testgen.repository.LlmCallLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * LLM çağrı geçmişi: hızlı erişim için in-memory (son MAX_SIZE),
 * kalıcı izlenebilirlik için llm_call_logs tablosuna da yazar.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmReportStore {

    private static final int MAX_SIZE = 500;

    private final LlmCallLogRepository callLogRepository;
    private final List<LlmCallReport> reports = new CopyOnWriteArrayList<>();

    public void record(LlmCallReport report) {
        if (reports.size() >= MAX_SIZE) {
            reports.remove(0);
        }
        reports.add(report);
        persist(report);

        // Konsola anında görünür özet
        if (report.success()) {
            log.info("┌─ LLM CALL ────────────────────────────────────────");
            log.info("│  Model    : {}", report.model());
            log.info("│  Tip      : {}", report.callType());
            log.info("│  Süre     : {} ms", report.durationMs());
            log.info("│  Prompt   : ~{} token ({} char)", report.estimatedPromptTokens(), report.promptChars());
            log.info("│  Yanıt    : ~{} token ({} char)", report.estimatedResponseTokens(), report.responseChars());
            log.info("│  Özet     : {}", report.promptSummary());
            log.info("└───────────────────────────────────────────────────");
        } else {
            log.warn("┌─ LLM CALL FAILED ─────────────────────────────────");
            log.warn("│  Model  : {}", report.model());
            log.warn("│  Tip    : {}", report.callType());
            log.warn("│  Hata   : {}", report.errorMessage());
            log.warn("└───────────────────────────────────────────────────");
        }
    }

    /** DB yazımı best-effort: log kaybı LLM akışını asla durdurmamalı. */
    private void persist(LlmCallReport report) {
        try {
            callLogRepository.save(LlmCallLog.builder()
                    .model(report.model())
                    .callType(report.callType())
                    .promptSummary(report.promptSummary())
                    .promptChars(report.promptChars())
                    .responseChars(report.responseChars())
                    .durationMs(report.durationMs())
                    .success(report.success())
                    .errorMessage(report.errorMessage())
                    .calledAt(report.calledAt())
                    .build());
        } catch (Exception e) {
            log.warn("LLM çağrı kaydı DB'ye yazılamadı: {}", e.getMessage());
        }
    }

    public List<LlmCallReport> all() {
        return Collections.unmodifiableList(reports);
    }

    public List<LlmCallReport> byType(String callType) {
        return reports.stream()
                .filter(r -> r.callType().equalsIgnoreCase(callType))
                .toList();
    }

    public LlmCallSummary summary() {
        long total   = reports.size();
        long success = reports.stream().filter(LlmCallReport::success).count();
        long totalMs = reports.stream().mapToLong(LlmCallReport::durationMs).sum();
        long avgMs   = total > 0 ? totalMs / total : 0;
        int  totalPromptTokens   = reports.stream().mapToInt(LlmCallReport::estimatedPromptTokens).sum();
        int  totalResponseTokens = reports.stream().mapToInt(LlmCallReport::estimatedResponseTokens).sum();

        return new LlmCallSummary(total, success, total - success, avgMs,
                totalPromptTokens, totalResponseTokens);
    }

    public record LlmCallSummary(
            long totalCalls, long successCalls, long failedCalls,
            long avgDurationMs, int totalPromptTokens, int totalResponseTokens) {}
}
