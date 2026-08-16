package com.testgen.orchestration;

import com.testgen.agent.AiAgentRegistry;
import com.testgen.generator.FrameworkTestGeneratorRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * İlk güvenli orkestrasyon girişi.
 *
 * <p>Bu sürüm planı üretir ve mevcut registry'lerle doğrular; mevcut üretim,
 * koşum ve controller yollarını devralmaz. Böylece yeni sınır OCP'de kalıcı iş
 * durumuna taşınmaya hazır olurken, eksik bir workflow mevcut davranışı bozmaz.</p>
 */
@Service
@RequiredArgsConstructor
public class DefaultQaOrchestrator implements QaOrchestrator {

    private static final Set<OrchestrationStepType> INITIAL_SUPPORTED_STEPS =
            EnumSet.of(OrchestrationStepType.TEST_DESIGN,
                    OrchestrationStepType.GENERATE_TEST_ARTIFACT,
                    OrchestrationStepType.VALIDATE_ARTIFACT);

    private final DeterministicOrchestrationPlanner planner;
    private final AiAgentRegistry agentRegistry;
    private final FrameworkTestGeneratorRegistry frameworkGenerators;

    @Override
    public OrchestrationResult execute(OrchestrationRequest request) {
        Objects.requireNonNull(request, "request");
        Instant startedAt = Instant.now();
        OrchestrationPlan plan = planner.plan(request);
        List<String> warnings = validatePlan(plan);
        Instant completedAt = Instant.now();

        return new OrchestrationResult(OrchestrationStatus.PLANNED, plan,
                new OrchestrationContext(request.orchestrationId(), request.requestId(),
                        request.correlationId(), startedAt, completedAt, warnings));
    }

    /** Paket görünürlüğü, contract testlerinin LLM'siz kapalı adım kontrolünü sağlar. */
    List<String> validatePlan(OrchestrationPlan plan) {
        Objects.requireNonNull(plan, "plan");
        List<String> warnings = new ArrayList<>();
        for (OrchestrationStep step : plan.steps()) {
            if (!INITIAL_SUPPORTED_STEPS.contains(step.type())) {
                throw new UnsupportedOrchestrationStepException(step.type());
            }
            if (step.type() == OrchestrationStepType.TEST_DESIGN
                    && !agentRegistry.contains(step.agentRole())) {
                if (step.mandatory()) {
                    throw new AgentUnavailableException(step.agentRole());
                }
                warnings.add("Opsiyonel agent kullanılamıyor ve planlanmadı: " + step.agentRole());
            }
            if ((step.type() == OrchestrationStepType.GENERATE_TEST_ARTIFACT
                    || step.type() == OrchestrationStepType.VALIDATE_ARTIFACT)
                    && !frameworkGenerators.supports(step.framework())) {
                throw new FrameworkUnavailableException(step.framework());
            }
        }
        return List.copyOf(warnings);
    }
}
