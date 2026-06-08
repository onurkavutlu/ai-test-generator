package com.testgen.controller;

import com.testgen.model.RequestStatus;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestType;

import java.time.LocalDateTime;

public record TestGenerationRequestResponseDto(
        String id,
        TestType testType,
        TestFramework framework,
        String userStory,
        String swaggerUrl,
        String applicationUrl,
        String appPackage,
        String additionalContext,
        RequestStatus status,
        boolean scheduledRun,
        LocalDateTime lastScheduledRunAt,
        int scheduledRunCount,
        int totalFailureCount,
        boolean autoGenerateOnFailure,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static TestGenerationRequestResponseDto from(TestGenerationRequest request) {
        return new TestGenerationRequestResponseDto(
                request.getId(),
                request.getTestType(),
                request.getFramework(),
                request.getUserStory(),
                request.getSwaggerUrl(),
                request.getApplicationUrl(),
                request.getAppPackage(),
                request.getAdditionalContext(),
                request.getStatus(),
                request.isScheduledRun(),
                request.getLastScheduledRunAt(),
                request.getScheduledRunCount(),
                request.getTotalFailureCount(),
                request.isAutoGenerateOnFailure(),
                request.getCreatedAt(),
                request.getUpdatedAt()
        );
    }
}
