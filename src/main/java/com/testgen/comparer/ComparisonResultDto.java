package com.testgen.comparer;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Karşılaştırma koşumunun tam sonucu.
 */
public record ComparisonResultDto(
        String baseUrlA,
        String baseUrlB,
        LocalDateTime executedAt,
        long totalDurationMs,
        Summary summary,
        List<RequestComparisonResult> results,
        /**
         * Yanıt gövdesi farklarının ajan değerlendirmesi (kırıcı / uyumlu / gürültü).
         * Fark yoksa, ajan kapalıysa veya LLM erişilemezse null kalır — karşılaştırma
         * sonucu bundan bağımsız olarak her zaman döner.
         */
        String diffAnalysis
) {
    /** Ajan değerlendirmesi olmadan sonuç (geri uyumluluk). */
    public ComparisonResultDto(String baseUrlA, String baseUrlB, LocalDateTime executedAt,
                               long totalDurationMs, Summary summary,
                               List<RequestComparisonResult> results) {
        this(baseUrlA, baseUrlB, executedAt, totalDurationMs, summary, results, null);
    }

    /** Aynı sonucun ajan değerlendirmesi eklenmiş kopyası. */
    public ComparisonResultDto withDiffAnalysis(String analysis) {
        return new ComparisonResultDto(baseUrlA, baseUrlB, executedAt, totalDurationMs,
                summary, results, analysis);
    }

    public record Summary(
            int totalRequests,
            int identicalCount,
            int differentCount,
            int errorCount
    ) {
        public static Summary of(List<RequestComparisonResult> results) {
            int identical = 0, different = 0, errors = 0;
            for (RequestComparisonResult r : results) {
                if (r.hasError()) errors++;
                else if (r.identical()) identical++;
                else different++;
            }
            return new Summary(results.size(), identical, different, errors);
        }
    }
}
