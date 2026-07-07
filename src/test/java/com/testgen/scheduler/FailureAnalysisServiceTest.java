package com.testgen.scheduler;

import com.testgen.llm.LlmService;
import com.testgen.model.*;
import com.testgen.repository.GeneratedTestCaseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class FailureAnalysisServiceTest {

    @Mock
    private LlmService llmService;

    @Mock
    private GeneratedTestCaseRepository testCaseRepository;

    @Mock
    private com.testgen.service.AgentLearningService agentLearningService;

    @InjectMocks
    private FailureAnalysisService failureAnalysisService;

    @Test
    public void testAnalyzeAndGenerateNewEmpty() {
        List<GeneratedTestCase> result = failureAnalysisService.analyzeAndGenerateNew(List.of(), new TestGenerationRequest());
        assertTrue(result.isEmpty());
        verifyNoInteractions(llmService);
    }

    @Test
    public void testAnalyzeAndGenerateNewKarate() {
        org.springframework.test.util.ReflectionTestUtils.setField(failureAnalysisService, "maxHealAttempts", 3);

        TestGenerationRequest request = TestGenerationRequest.builder()
                .id("req-123")
                .testType(TestType.BACKEND_API)
                .scheduledRunCount(2)
                .build();

        GeneratedTestCase failedCase = GeneratedTestCase.builder()
                .testName("GetPetByIdTest")
                .fileName("GetPetByIdTest.feature")
                .testContent("Feature: Get pet by id")
                .runStatus(TestRunStatus.FAILED)
                .runOutput("Assertion failed: id == 10 but was null")
                .framework(TestFramework.KARATE)
                .request(request)
                .healAttempts(2)
                .build();

        String mockLlmResponse = "```\nFeature: Get pet by id fixed\n```";
        when(llmService.generateTestCase(anyString(), anyString())).thenReturn(mockLlmResponse);

        List<GeneratedTestCase> result = failureAnalysisService.analyzeAndGenerateNew(List.of(failedCase), request);

        assertNotNull(result);
        assertEquals(1, result.size());
        
        GeneratedTestCase fixedCase = result.get(0);
        assertEquals("GetPetByIdTest_Fixed_v3", fixedCase.getTestName());
        assertEquals("GetPetByIdTest_Fixed_v3.feature", fixedCase.getFileName());
        // CodeCleaner AI üretimi feature'lara @testCaseLLM tag'i enjekte eder
        assertTrue(fixedCase.getTestContent().contains("Feature: Get pet by id fixed"));
        assertTrue(fixedCase.getTestContent().contains("@testCaseLLM"));
        assertEquals(TestFramework.KARATE, fixedCase.getFramework());
        assertEquals(TestRunStatus.NOT_RUN, fixedCase.getRunStatus());
        assertTrue(fixedCase.getTestSummary().contains("[AUTO-FIX]") && fixedCase.getTestSummary().contains("GetPetByIdTest"));
        // Yeni case parent'ın deneme sayısını devralmalı; sıfırlanırsa heal zinciri limiti aşar
        assertEquals(3, fixedCase.getHealAttempts());

        verify(llmService, times(1)).generateTestCase(anyString(), anyString());
    }

    @Test
    public void testMaxHealAttemptsReachedSkipsCase() {
        org.springframework.test.util.ReflectionTestUtils.setField(failureAnalysisService, "maxHealAttempts", 3);

        TestGenerationRequest request = TestGenerationRequest.builder().id("req-456").build();

        GeneratedTestCase exhaustedCase = GeneratedTestCase.builder()
                .testName("GetPetByIdTest_Fixed_v3")
                .fileName("GetPetByIdTest_Fixed_v3.feature")
                .runStatus(TestRunStatus.FAILED)
                .framework(TestFramework.KARATE)
                .request(request)
                .healAttempts(3)
                .build();

        List<GeneratedTestCase> result = failureAnalysisService.analyzeAndGenerateNew(List.of(exhaustedCase), request);

        assertTrue(result.isEmpty());
        verifyNoInteractions(llmService);
    }
}
