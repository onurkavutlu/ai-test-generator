package com.testgen.controller;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestCategory;
import com.testgen.model.TestDesignTechnique;
import com.testgen.model.TestFramework;
import com.testgen.model.TestLevel;
import com.testgen.model.TestRunStatus;
import com.testgen.model.ValidationStatus;

import java.time.LocalDateTime;
import java.util.List;

@JacksonXmlRootElement(localName = "generatedTestCase")
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
        LocalDateTime lastRunAt,
        ValidationStatus validationStatus,
        String validationError,
        int validationAttempts,
        // ISTQB sınıflandırması — hangi test sınıfının geçtiğini/düştüğünü ölçmek için
        TestCategory testCategory,
        TestLevel testLevel,
        TestDesignTechnique testDesignTechnique,
        /** İçerik LLM'den değil gözlemden deterministik üretildi mi? */
        boolean deterministic
) {
    public static GeneratedTestCaseResponseDto from(GeneratedTestCase testCase) {
        // Aynı özet metni dört kez ayrıştırılıyordu; 45 case'lik bir listede bu
        // 180 gereksiz ayrıştırma demekti. Bir kez çözülüp yeniden kullanılır.
        Insight insight = Insight.fromSummary(testCase.getTestSummary());
        return new GeneratedTestCaseResponseDto(
                testCase.getId(),
                testCase.getRequest() != null ? testCase.getRequest().getId() : null,
                testCase.getTestName(),
                testCase.getFileName(),
                testCase.getTestContent(),
                testCase.getTestSummary(),
                insight.tags(),
                insight.source(),
                insight.generationNarrative(),
                insight.improvementNarrative(),
                testCase.getFramework(),
                testCase.getRunStatus(),
                testCase.getRunOutput(),
                testCase.getTotalScenarios(),
                testCase.getPassedScenarios(),
                testCase.getFailedScenarios(),
                testCase.getExecutionTimeMs(),
                testCase.getCreatedAt(),
                testCase.getLastRunAt(),
                testCase.getValidationStatus(),
                testCase.getValidationError(),
                testCase.getValidationAttempts(),
                testCase.getTestCategory(),
                testCase.getTestLevel(),
                testCase.getTestDesignTechnique(),
                testCase.isDeterministic()
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
