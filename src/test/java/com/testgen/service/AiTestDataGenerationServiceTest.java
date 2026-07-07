package com.testgen.service;

import com.testgen.llm.LlmService;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AiTestDataGenerationServiceTest {

    @Test
    public void testEnrichAdditionalContextWithLlmData() {
        LlmService llmService = mock(LlmService.class);
        AiTestDataGenerationService service = new AiTestDataGenerationService(llmService);

        TestGenerationRequest request = TestGenerationRequest.builder()
                .testType(TestType.FRONTEND_WEB)
                .framework(TestFramework.SELENIUM)
                .userStory("Kullanici checkout yapabilmeli")
                .additionalContext("checkout selectors")
                .build();

        when(llmService.generateTestCase(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("[{ name: 'valid checkout', coupon: 'YAZ20' }]");

        String enriched = service.enrichAdditionalContext(request);

        assertTrue(enriched.contains("checkout selectors"));
        assertTrue(enriched.contains("AI-GENERATED TEST DATA"));
        assertTrue(enriched.contains("valid checkout"));
    }

    @Test
    public void testThrowsWhenLlmFails() {
        LlmService llmService = mock(LlmService.class);
        AiTestDataGenerationService service = new AiTestDataGenerationService(llmService);

        TestGenerationRequest request = TestGenerationRequest.builder()
                .testType(TestType.BACKEND_API)
                .framework(TestFramework.KARATE)
                .build();

        when(llmService.generateTestCase(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("llm down"));

        assertThrows(TestGenerationException.class, () -> service.enrichAdditionalContext(request));
    }
}
