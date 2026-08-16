package com.testgen.orchestration;

import com.testgen.agent.AgentRouting;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestType;

import java.util.Objects;
import java.util.UUID;

/**
 * Entity yerine taşınabilir, değişmez orkestrasyon girdisi.
 *
 * <p>Bu kayıt yalnız ilk planlama için gereken request anlık görüntüsünü taşır;
 * JPA entity veya JVM içi oturum referansı tutmaz. Gelecekte kalıcı iş durumu
 * eklendiğinde aynı kimlikler farklı podlar arasında kullanılabilir.</p>
 */
public record OrchestrationRequest(
        String orchestrationId,
        String requestId,
        String correlationId,
        TestType testType,
        TestFramework framework,
        boolean agentsEnabled,
        AgentRouting.Mode agentMode,
        boolean userStoryProvided
) {
    public OrchestrationRequest {
        orchestrationId = requireText(orchestrationId, "orchestrationId");
        correlationId = requireText(correlationId, "correlationId");
        testType = Objects.requireNonNull(testType, "testType");
        framework = Objects.requireNonNull(framework, "framework");
        agentMode = agentMode == null ? AgentRouting.Mode.LEAN : agentMode;
    }

    /** Mevcut üretim isteğinden side-effect içermeyen bir planlama anlık görüntüsü üretir. */
    public static OrchestrationRequest from(TestGenerationRequest request) {
        Objects.requireNonNull(request, "request");
        String id = UUID.randomUUID().toString();
        return new OrchestrationRequest(
                id,
                request.getId(),
                request.getId() == null || request.getId().isBlank() ? id : request.getId(),
                request.getTestType(),
                request.getFramework(),
                request.isAgentsEnabled(),
                request.getAgentMode(),
                request.getUserStory() != null && !request.getUserStory().isBlank());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " zorunludur.");
        }
        return value;
    }
}
