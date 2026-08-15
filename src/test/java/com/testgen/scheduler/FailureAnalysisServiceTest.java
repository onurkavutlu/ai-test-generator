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

    @Mock
    private com.testgen.generator.TestContentGate testContentGate;

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
        // Tasarım kararı: heal edilen test orijinal ad/dosyayı korur (diskte çift dosya ve
        // Java class-adı uyuşmazlığı önlenir); soyağacı parentCaseId + superseded ile izlenir
        assertEquals("GetPetByIdTest", fixedCase.getTestName());
        assertEquals("GetPetByIdTest.feature", fixedCase.getFileName());
        assertEquals(failedCase.getId(), fixedCase.getParentCaseId());
        assertTrue(failedCase.isSuperseded());
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

    @Test
    public void healedCaseGoesThroughTheValidationGate() {
        // CANLIDA OLCULDU: self-healing ciktisi hic dogrulanmadan kaydediliyordu.
        // Karate case'i icin LLM Java kodu + aciklama metni dondurmustu ve kosumda
        // "missing FEATURE at <EOF>" ile patlamisti. Kapi bu yolda da calismali.
        org.springframework.test.util.ReflectionTestUtils.setField(failureAnalysisService, "maxHealAttempts", 3);

        TestGenerationRequest request = TestGenerationRequest.builder()
                .id("req-1").testType(TestType.BACKEND_API).build();

        GeneratedTestCase failedCase = GeneratedTestCase.builder()
                .testName("HealthTest").fileName("HealthTest.feature")
                .testContent("Feature: health")
                .runStatus(TestRunStatus.FAILED).runOutput("bosluk")
                .framework(TestFramework.KARATE).request(request).build();

        when(llmService.generateTestCase(anyString(), anyString()))
                .thenReturn("Feature: duzeltilmis\n  Scenario: s\n    Then status 200");

        List<GeneratedTestCase> healed = failureAnalysisService.analyzeAndGenerateNew(List.of(failedCase), request);

        assertEquals(1, healed.size());
        verify(testContentGate, times(1)).apply(healed.get(0));
    }
}
