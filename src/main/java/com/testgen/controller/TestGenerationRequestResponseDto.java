package com.testgen.controller;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import com.testgen.model.RequestStatus;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestType;

import java.time.LocalDateTime;

@JacksonXmlRootElement(localName = "testGenerationRequest")
public record TestGenerationRequestResponseDto(
        String id,
        TestType testType,
        TestFramework framework,
        String userStory,
        String swaggerUrl,
        String applicationUrl,
        String additionalContext,
        String rawPayload,
        String payloadType,
        RequestStatus status,
        boolean scheduledRun,
        LocalDateTime lastScheduledRunAt,
        int scheduledRunCount,
        int totalFailureCount,
        boolean autoGenerateOnFailure,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,

        // ── Gözlem kanıtı: üretilen testlerin neye dayandığı ──
        /** "POST https://host/path" — gözlemlenen isteğin özeti. */
        String observedRequestLine,
        /** Gerçekten dönen durum kodu; gözlem yapılmadıysa null. */
        Integer observedStatus,
        /** Gerçekten ölçülen süre (ms); SLA yalnızca bundan türetilebilir. */
        Long observedDurationMs,
        /** Yanıt gövdesi — ekranda olduğu gibi gösterilir. */
        String observedBody,
        String observedResponseHeaders,
        String observedResponseCookies,
        Long observedResponseSizeBytes,
        String observedHttpVersion,
        /** Gözlem yapılamadıysa nedeni; yapıldıysa null. */
        String observationSkipReason,
        LocalDateTime observedAt
) {
    public static TestGenerationRequestResponseDto from(TestGenerationRequest request) {
        return new TestGenerationRequestResponseDto(
                request.getId(),
                request.getTestType(),
                request.getFramework(),
                request.getUserStory(),
                request.getSwaggerUrl(),
                request.getApplicationUrl(),
                request.getAdditionalContext(),
                request.getRawPayload(),
                request.getPayloadType(),
                request.getStatus(),
                request.isScheduledRun(),
                request.getLastScheduledRunAt(),
                request.getScheduledRunCount(),
                request.getTotalFailureCount(),
                request.isAutoGenerateOnFailure(),
                request.getCreatedAt(),
                request.getUpdatedAt(),
                request.getObservedRequestLine(),
                request.getObservedStatus(),
                request.getObservedDurationMs(),
                request.getObservedBody(),
                request.getObservedResponseHeaders(),
                request.getObservedResponseCookies(),
                request.getObservedResponseSizeBytes(),
                request.getObservedHttpVersion(),
                request.getObservationSkipReason(),
                request.getObservedAt()
        );
    }
}
