package com.testgen.comparer;

import java.util.List;

/**
 * Tek bir isteğin iki endpoint'teki sonuçlarının karşılaştırması.
 */
public record RequestComparisonResult(
        String name,
        String method,
        String path,
        Integer statusA,
        Integer statusB,
        boolean statusMatch,
        long latencyAMs,
        long latencyBMs,
        boolean bodyMatch,
        List<FieldDifference> differences,
        List<FieldDifference> headerDifferences,
        String bodyA,
        String bodyB,
        String errorA,
        String errorB
) {
    public boolean identical() {
        return errorA == null && errorB == null && statusMatch && bodyMatch
                && (headerDifferences == null || headerDifferences.isEmpty());
    }

    public boolean hasError() {
        return errorA != null || errorB != null;
    }
}
