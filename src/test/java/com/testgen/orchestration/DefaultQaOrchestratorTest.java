package com.testgen.orchestration;

import com.testgen.agent.AgentRouting;
import com.testgen.agent.AiAgentRegistry;
import com.testgen.agent.AiAgentRole;
import com.testgen.generator.FrameworkTestGeneratorRegistry;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultQaOrchestratorTest {

    private final DeterministicOrchestrationPlanner planner = new DeterministicOrchestrationPlanner();

    @Test
    void plansExistingAgentRoutingInDeterministicOrderWithoutConcreteFrameworkClasses() {
        AiAgentRegistry agents = mock(AiAgentRegistry.class);
        FrameworkTestGeneratorRegistry frameworks = mock(FrameworkTestGeneratorRegistry.class);
        when(agents.contains(any())).thenReturn(true);
        when(frameworks.supports(any())).thenReturn(true);

        OrchestrationRequest request = request(TestFramework.KARATE, true, AgentRouting.Mode.FULL, true);
        OrchestrationResult result = orchestrator(agents, frameworks).execute(request);

        assertEquals(OrchestrationStatus.PLANNED, result.status());
        assertEquals(List.of(
                        AiAgentRole.PRODUCT_MANAGER,
                        AiAgentRole.DEVELOPER,
                        AiAgentRole.AI_LLM_TEST_ANALYST,
                        AiAgentRole.TEST_AUTOMATION,
                        AiAgentRole.SECOPS,
                        AiAgentRole.PERFORMANCE,
                        AiAgentRole.DEVOPS,
                        AiAgentRole.REPORT),
                result.plan().steps().stream()
                        .filter(step -> step.type() == OrchestrationStepType.TEST_DESIGN)
                        .map(OrchestrationStep::agentRole)
                        .toList());
        assertEquals(List.of(OrchestrationStepType.GENERATE_TEST_ARTIFACT,
                        OrchestrationStepType.VALIDATE_ARTIFACT),
                result.plan().steps().subList(8, 10).stream().map(OrchestrationStep::type).toList());
        assertTrue(result.plan().steps().stream()
                .filter(step -> step.framework() != null)
                .allMatch(step -> step.framework() == TestFramework.KARATE));
        verify(frameworks, times(2)).supports(TestFramework.KARATE);
    }

    @Test
    void agentsCanBeDisabledWithoutRemovingFrameworkPlanning() {
        AiAgentRegistry agents = mock(AiAgentRegistry.class);
        FrameworkTestGeneratorRegistry frameworks = mock(FrameworkTestGeneratorRegistry.class);
        when(frameworks.supports(TestFramework.REST_ASSURED)).thenReturn(true);

        OrchestrationResult result = orchestrator(agents, frameworks).execute(
                request(TestFramework.REST_ASSURED, false, AgentRouting.Mode.LEAN, false));

        assertEquals(2, result.plan().steps().size());
        assertEquals(List.of(OrchestrationStepType.GENERATE_TEST_ARTIFACT,
                        OrchestrationStepType.VALIDATE_ARTIFACT),
                result.plan().steps().stream().map(OrchestrationStep::type).toList());
        verifyNoInteractions(agents);
    }

    @Test
    void everyRegisteredFrameworkUsesTheSameFrameworkIndependentPlan() {
        AiAgentRegistry agents = mock(AiAgentRegistry.class);
        FrameworkTestGeneratorRegistry frameworks = mock(FrameworkTestGeneratorRegistry.class);
        when(frameworks.supports(any())).thenReturn(true);
        DefaultQaOrchestrator orchestrator = orchestrator(agents, frameworks);

        for (TestFramework framework : TestFramework.values()) {
            OrchestrationResult result = orchestrator.execute(
                    request(framework, false, AgentRouting.Mode.LEAN, false));
            assertEquals(2, result.plan().steps().size());
            assertEquals(framework, result.plan().steps().get(0).framework());
            assertEquals(framework, result.plan().steps().get(1).framework());
        }
    }

    @Test
    void missingMandatoryAgentIsRejectedExplicitly() {
        AiAgentRegistry agents = mock(AiAgentRegistry.class);
        FrameworkTestGeneratorRegistry frameworks = mock(FrameworkTestGeneratorRegistry.class);
        when(agents.contains(AiAgentRole.DEVELOPER)).thenReturn(false);

        OrchestrationPlan plan = new OrchestrationPlan("orchestration-1",
                List.of(OrchestrationStep.agent("agent-developer", AiAgentRole.DEVELOPER, true)));

        AgentUnavailableException error = assertThrows(AgentUnavailableException.class,
                () -> orchestrator(agents, frameworks).validatePlan(plan));

        assertEquals("Zorunlu agent kullanılamıyor: DEVELOPER", error.getMessage());
    }

    @Test
    void unavailableOptionalAgentProducesWarningInsteadOfFailingPlan() {
        AiAgentRegistry agents = mock(AiAgentRegistry.class);
        FrameworkTestGeneratorRegistry frameworks = mock(FrameworkTestGeneratorRegistry.class);
        when(agents.contains(AiAgentRole.PERFORMANCE)).thenReturn(false);

        OrchestrationPlan plan = new OrchestrationPlan("orchestration-1",
                List.of(OrchestrationStep.agent("agent-performance", AiAgentRole.PERFORMANCE, false)));

        List<String> warnings = orchestrator(agents, frameworks).validatePlan(plan);

        assertEquals(List.of("Opsiyonel agent kullanılamıyor ve planlanmadı: PERFORMANCE"), warnings);
    }

    @Test
    void unavailableFrameworkIsRejectedWithoutKnowingItsGeneratorImplementation() {
        AiAgentRegistry agents = mock(AiAgentRegistry.class);
        FrameworkTestGeneratorRegistry frameworks = mock(FrameworkTestGeneratorRegistry.class);
        when(frameworks.supports(TestFramework.SELENIUM)).thenReturn(false);

        OrchestrationPlan plan = new OrchestrationPlan("orchestration-1", List.of(
                OrchestrationStep.framework("generate", OrchestrationStepType.GENERATE_TEST_ARTIFACT,
                        TestFramework.SELENIUM)));

        FrameworkUnavailableException error = assertThrows(FrameworkUnavailableException.class,
                () -> orchestrator(agents, frameworks).validatePlan(plan));

        assertEquals("Framework generator kullanılamıyor: SELENIUM", error.getMessage());
    }

    @Test
    void unregisteredFutureStepCanNeverBeExecutedSilently() {
        OrchestrationPlan plan = new OrchestrationPlan("orchestration-1", List.of(
                new OrchestrationStep("database", OrchestrationStepType.QUERY_DATABASE, true, null, null)));

        UnsupportedOrchestrationStepException error = assertThrows(UnsupportedOrchestrationStepException.class,
                () -> orchestrator(mock(AiAgentRegistry.class), mock(FrameworkTestGeneratorRegistry.class))
                        .validatePlan(plan));

        assertEquals("Desteklenmeyen orkestrasyon adımı: QUERY_DATABASE", error.getMessage());
    }

    @Test
    void duplicateStepIdsAndIncompleteStepsAreRejectedAtPlanBoundary() {
        assertThrows(InvalidOrchestrationPlanException.class, () -> new OrchestrationPlan("orchestration-1", List.of(
                OrchestrationStep.agent("same", AiAgentRole.DEVELOPER, true),
                OrchestrationStep.agent("same", AiAgentRole.SECOPS, true))));
        assertThrows(InvalidOrchestrationPlanException.class, () -> new OrchestrationStep(
                "design", OrchestrationStepType.TEST_DESIGN, true, null, null));
        assertThrows(InvalidOrchestrationPlanException.class, () -> new OrchestrationStep(
                "generate", OrchestrationStepType.GENERATE_TEST_ARTIFACT, true, null, null));
    }

    @Test
    void requestSnapshotUsesExistingGenerationRequestWithoutRetainingTheEntity() {
        TestGenerationRequest existing = TestGenerationRequest.builder()
                .id("request-1")
                .testType(TestType.BACKEND_API)
                .framework(TestFramework.KARATE)
                .userStory("Kullanıcı ödeme yapabilmeli")
                .agentsEnabled(true)
                .agentMode(AgentRouting.Mode.FULL)
                .build();

        OrchestrationRequest snapshot = OrchestrationRequest.from(existing);

        assertNotEquals(existing.getId(), snapshot.orchestrationId());
        assertEquals("request-1", snapshot.requestId());
        assertEquals("request-1", snapshot.correlationId());
        assertTrue(snapshot.userStoryProvided());
        assertEquals(AgentRouting.Mode.FULL, snapshot.agentMode());
    }

    @Test
    void requestAndPlanValueObjectsRejectMissingIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new OrchestrationRequest(
                "", null, "correlation", TestType.BACKEND_API, TestFramework.KARATE,
                true, null, false));
        assertThrows(IllegalArgumentException.class, () -> new OrchestrationRequest(
                "orchestration", null, "", TestType.BACKEND_API, TestFramework.KARATE,
                true, null, false));
        assertThrows(InvalidOrchestrationPlanException.class,
                () -> new OrchestrationPlan("orchestration", List.of()));
    }

    @Test
    void valueObjectsHaveSafeDefaultsAndExposeTheClosedStepVocabulary() {
        OrchestrationRequest request = new OrchestrationRequest("orchestration", null, "correlation",
                TestType.BACKEND_API, TestFramework.KARATE, true, null, false);
        OrchestrationContext context = new OrchestrationContext("orchestration", null, "correlation",
                java.time.Instant.EPOCH, java.time.Instant.EPOCH, null);
        OrchestrationResult result = new OrchestrationResult(OrchestrationStatus.COMPLETED,
                new OrchestrationPlan("orchestration", List.of(
                        OrchestrationStep.framework("validate", OrchestrationStepType.VALIDATE_ARTIFACT,
                                TestFramework.KARATE))), context);

        assertEquals(AgentRouting.Mode.LEAN, request.agentMode());
        assertTrue(context.warnings().isEmpty());
        assertEquals(OrchestrationStatus.COMPLETED, result.status());
        assertEquals(10, OrchestrationStepType.values().length);
        assertEquals(5, OrchestrationStatus.values().length);
    }

    private DefaultQaOrchestrator orchestrator(AiAgentRegistry agents,
                                                FrameworkTestGeneratorRegistry frameworks) {
        return new DefaultQaOrchestrator(planner, agents, frameworks);
    }

    private static OrchestrationRequest request(TestFramework framework, boolean agentsEnabled,
                                                AgentRouting.Mode mode, boolean userStoryProvided) {
        String id = UUID.randomUUID().toString();
        return new OrchestrationRequest(id, "request-" + id, "correlation-" + id,
                TestType.BACKEND_API, framework, agentsEnabled, mode, userStoryProvided);
    }
}
