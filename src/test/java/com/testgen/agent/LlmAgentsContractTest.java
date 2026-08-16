package com.testgen.agent;

import com.testgen.llm.LlmService;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestType;
import com.testgen.service.TestGenerationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmAgentsContractTest {

    @ParameterizedTest
    @EnumSource(AiAgentRole.class)
    void everyAgentUsesItsRoleCallTypeAndRealRequestData(AiAgentRole role) {
        LlmService llmService = mock(LlmService.class);
        when(llmService.generateTestCase(anyString(), eq("AGENT_" + role.name())))
                .thenReturn("Gerçek girdiye dayalı çıktı");
        AiAgent agent = agent(role, llmService);
        AiAgentContext context = new AiAgentContext(TestGenerationRequest.builder()
                .testType(TestType.BACKEND_API)
                .framework(TestFramework.REST_ASSURED)
                .userStory("Sipariş kimliğiyle getirilebilmeli")
                .swaggerUrl("https://api.example.test/openapi.yaml")
                .additionalContext("## OBSERVED FACTS\nstatus: 200")
                .build());

        AiAgentResult result = agent.analyze(context);

        assertEquals(role, result.role());
        assertEquals("Gerçek girdiye dayalı çıktı", result.output());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llmService).generateTestCase(prompt.capture(), eq("AGENT_" + role.name()));
        assertTrue(prompt.getValue().contains("Sipariş kimliğiyle getirilebilmeli"));
        assertTrue(prompt.getValue().contains("https://api.example.test/openapi.yaml"));
        assertTrue(prompt.getValue().contains("status: 200"));
        assertTrue(prompt.getValue().contains("varsayım yapma"));
        assertFalse(prompt.getValue().contains("varsayım gerekiyorsa"));
    }

    @Test
    void fencedLlmResponseIsCleanedWithoutInventingReplacementContent() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.generateTestCase(anyString(), eq("AGENT_DEVELOPER")))
                .thenReturn("```markdown\n- Gözlenen status: 204\n```");

        AiAgentResult result = new DeveloperAgent(llmService).analyze(context());

        assertEquals("- Gözlenen status: 204", result.output());
    }

    @Test
    void blankLlmResponseStopsAgentInsteadOfReturningFallbackText() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.generateTestCase(anyString(), eq("AGENT_DEVELOPER"))).thenReturn("  ");

        TestGenerationException error = assertThrows(TestGenerationException.class,
                () -> new DeveloperAgent(llmService).analyze(context()));

        assertTrue(error.getMessage().contains("boş LLM çıktısı"));
    }

    @Test
    void llmFailureStopsAgentAndPreservesCause() {
        LlmService llmService = mock(LlmService.class);
        IllegalStateException cause = new IllegalStateException("ölçülen bağlantı hatası");
        when(llmService.generateTestCase(anyString(), eq("AGENT_DEVELOPER"))).thenThrow(cause);

        TestGenerationException error = assertThrows(TestGenerationException.class,
                () -> new DeveloperAgent(llmService).analyze(context()));

        assertEquals(cause, error.getCause());
        assertTrue(error.getMessage().contains("ölçülen bağlantı hatası"));
    }

    private static AiAgentContext context() {
        return new AiAgentContext(TestGenerationRequest.builder()
                .testType(TestType.BACKEND_API)
                .framework(TestFramework.KARATE)
                .build());
    }

    private static AiAgent agent(AiAgentRole role, LlmService llmService) {
        return switch (role) {
            case PRODUCT_MANAGER -> new ProductManagerAgent(llmService);
            case DEVELOPER -> new DeveloperAgent(llmService);
            case AI_LLM_TEST_ANALYST -> new AiLlmTestAnalystAgent(llmService);
            case TEST_AUTOMATION -> new TestAutomationAgent(llmService);
            case PERFORMANCE -> new PerformanceAgent(llmService);
            case DEVOPS -> new DevOpsAgent(llmService);
            case SECOPS -> new SecOpsAgent(llmService);
            case REPORT -> new ReportAgent(llmService);
        };
    }
}
