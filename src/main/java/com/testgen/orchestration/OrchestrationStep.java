package com.testgen.orchestration;

import com.testgen.agent.AiAgentRole;
import com.testgen.model.TestFramework;

/** Tek, sınırları belirlenmiş orkestrasyon adımı. */
public record OrchestrationStep(
        String stepId,
        OrchestrationStepType type,
        boolean mandatory,
        AiAgentRole agentRole,
        TestFramework framework
) {
    public OrchestrationStep {
        if (stepId == null || stepId.isBlank()) {
            throw new InvalidOrchestrationPlanException("Orkestrasyon stepId zorunludur.");
        }
        if (type == null) {
            throw new InvalidOrchestrationPlanException("Orkestrasyon step type zorunludur.");
        }
        if (type == OrchestrationStepType.TEST_DESIGN && agentRole == null) {
            throw new InvalidOrchestrationPlanException("TEST_DESIGN adımı agentRole içermelidir.");
        }
        if ((type == OrchestrationStepType.GENERATE_TEST_ARTIFACT
                || type == OrchestrationStepType.VALIDATE_ARTIFACT)
                && framework == null) {
            throw new InvalidOrchestrationPlanException(type + " adımı framework içermelidir.");
        }
    }

    public static OrchestrationStep agent(String id, AiAgentRole role, boolean mandatory) {
        return new OrchestrationStep(id, OrchestrationStepType.TEST_DESIGN, mandatory, role, null);
    }

    public static OrchestrationStep framework(String id, OrchestrationStepType type,
                                              TestFramework framework) {
        return new OrchestrationStep(id, type, true, null, framework);
    }
}
