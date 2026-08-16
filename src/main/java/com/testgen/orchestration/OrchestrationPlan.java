package com.testgen.orchestration;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Değişmez ve deterministik sıralı orkestrasyon planı. */
public record OrchestrationPlan(String orchestrationId, List<OrchestrationStep> steps) {
    public OrchestrationPlan {
        if (orchestrationId == null || orchestrationId.isBlank()) {
            throw new InvalidOrchestrationPlanException("Plan orchestrationId zorunludur.");
        }
        if (steps == null || steps.isEmpty()) {
            throw new InvalidOrchestrationPlanException("Orkestrasyon planı en az bir adım içermelidir.");
        }
        steps = List.copyOf(steps);
        Set<String> ids = new HashSet<>();
        for (OrchestrationStep step : steps) {
            if (step == null) {
                throw new InvalidOrchestrationPlanException("Orkestrasyon planı null adım içeremez.");
            }
            if (!ids.add(step.stepId())) {
                throw new InvalidOrchestrationPlanException("Tekrarlanan orkestrasyon adımı: " + step.stepId());
            }
        }
    }
}
