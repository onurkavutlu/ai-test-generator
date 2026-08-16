package com.testgen.orchestration;

import java.time.Instant;
import java.util.List;

/** Planlama/koşum telemetrisi için process-local olmayan bağlam özeti. */
public record OrchestrationContext(
        String orchestrationId,
        String requestId,
        String correlationId,
        Instant startedAt,
        Instant completedAt,
        List<String> warnings
) {
    public OrchestrationContext {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
