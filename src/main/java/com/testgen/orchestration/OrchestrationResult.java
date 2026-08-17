package com.testgen.orchestration;

/** İlk iterasyonda güvenle oluşturulup doğrulanmış planın sonucu. */
public record OrchestrationResult(
        OrchestrationStatus status,
        OrchestrationPlan plan,
        OrchestrationContext context
) {
}
