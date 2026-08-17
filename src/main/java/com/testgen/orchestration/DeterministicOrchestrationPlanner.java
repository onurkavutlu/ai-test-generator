package com.testgen.orchestration;

import com.testgen.agent.AgentRouting;
import com.testgen.agent.AiAgentRole;
import com.testgen.model.TestGenerationRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM kararı veya framework sınıfı bilmeden ilk orkestrasyon planını üretir.
 *
 * <p>Agent sırası mevcut {@link AgentRouting} tek doğruluk kaynağından gelir.
 * Artifact üretim ve doğrulama adımları framework adıyla değil, ortak
 * {@link OrchestrationStepType} ile temsil edilir.</p>
 */
@Component
public class DeterministicOrchestrationPlanner {

    public OrchestrationPlan plan(OrchestrationRequest request) {
        TestGenerationRequest routingRequest = TestGenerationRequest.builder()
                .testType(request.testType())
                .framework(request.framework())
                .userStory(request.userStoryProvided() ? "provided" : null)
                .build();

        List<OrchestrationStep> steps = new ArrayList<>();
        int sequence = 1;
        if (request.agentsEnabled()) {
            List<AiAgentRole> mandatory = AgentRouting.mandatory(routingRequest);
            for (AiAgentRole role : AgentRouting.resolve(routingRequest, request.agentMode())) {
                steps.add(OrchestrationStep.agent(stepId(sequence++, role), role, mandatory.contains(role)));
            }
        }
        steps.add(OrchestrationStep.framework(stepId(sequence++, null),
                OrchestrationStepType.GENERATE_TEST_ARTIFACT, request.framework()));
        steps.add(OrchestrationStep.framework(stepId(sequence, null),
                OrchestrationStepType.VALIDATE_ARTIFACT, request.framework()));
        return new OrchestrationPlan(request.orchestrationId(), steps);
    }

    private static String stepId(int sequence, AiAgentRole role) {
        return role == null ? "step-" + sequence : "step-" + sequence + "-" + role.name().toLowerCase();
    }
}
