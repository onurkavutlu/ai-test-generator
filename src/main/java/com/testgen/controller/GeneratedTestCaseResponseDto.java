package com.testgen.controller;

import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.model.TestRunStatus;

import java.time.LocalDateTime;
import java.util.List;

public record GeneratedTestCaseResponseDto(
        String id,
        String requestId,
        String testName,
        String fileName,
        String testContent,
        String testSummary,
        List<String> generationTags,
        String generationSource,
        String generationNarrative,
        String improvementNarrative,
        TestFramework framework,
        TestRunStatus runStatus,
        String runOutput,
        Integer totalScenarios,
        Integer passedScenarios,
        Integer failedScenarios,
        Long executionTimeMs,
        LocalDateTime createdAt,
        LocalDateTime lastRunAt
) {
    public static GeneratedTestCaseResponseDto from(GeneratedTestCase testCase) {
        return new GeneratedTestCaseResponseDto(
                testCase.getId(),
                testCase.getRequest() != null ? testCase.getRequest().getId() : null,
                testCase.getTestName(),
                testCase.getFileName(),
                testCase.getTestContent(),
                testCase.getTestSummary(),
                Insight.fromSummary(testCase.getTestSummary()).tags(),
                Insight.fromSummary(testCase.getTestSummary()).source(),
                Insight.fromSummary(testCase.getTestSummary()).generationNarrative(),
                Insight.fromSummary(testCase.getTestSummary()).improvementNarrative(),
                testCase.getFramework(),
                testCase.getRunStatus(),
                testCase.getRunOutput(),
                testCase.getTotalScenarios(),
                testCase.getPassedScenarios(),
                testCase.getFailedScenarios(),
                testCase.getExecutionTimeMs(),
                testCase.getCreatedAt(),
                testCase.getLastRunAt()
        );
    }

    private record Insight(List<String> tags, String source, String generationNarrative, String improvementNarrative) {
        private static Insight fromSummary(String summary) {
            if (summary == null || summary.isBlank()) {
                return new Insight(List.of(), "UNKNOWN", null, null);
            }

            List<String> tags = List.of(summary.split("\\]"))
                    .stream()
                    .filter(part -> part.startsWith("["))
                    .map(part -> part.substring(1).trim())
                    .filter(tag -> !tag.isBlank())
                    .toList();

            String cleanSummary = summary.replaceAll("\\[[^]]+]", "").trim();
            String source = tags.contains("AUTO-FIX")
                    ? "AUTO_FIX"
                    : tags.contains("LLM-GENERATED") ? "LLM_GENERATED"
                    : tags.contains("LOCAL-SEED") ? "LOCAL_SEED"
                    : "UNKNOWN";
            String improvement = tags.contains("AUTO-FIX") ? cleanSummary : null;
            String generation = tags.contains("AUTO-FIX") ? null : cleanSummary;
            return new Insight(tags, source, generation, improvement);
        }
    }
}
