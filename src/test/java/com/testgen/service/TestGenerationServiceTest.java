package com.testgen.service;

import com.testgen.generator.KarateTestGenerator;
import com.testgen.generator.SeleniumTestGenerator;
import com.testgen.model.*;
import com.testgen.repository.GeneratedTestCaseRepository;
import com.testgen.repository.TestGenerationRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TestGenerationServiceTest {

    @Mock
    private KarateTestGenerator karateTestGenerator;

    @Mock
    private SeleniumTestGenerator seleniumTestGenerator;

    @Mock
    private com.testgen.generator.RestAssuredTestGenerator restAssuredTestGenerator;

    @Mock
    private TestGenerationRequestRepository requestRepository;

    @Mock
    private GeneratedTestCaseRepository testCaseRepository;

    @Mock
    private AiAgentOrchestratorService aiAgentOrchestratorService;

    @Mock
    private AiTestDataGenerationService aiTestDataGenerationService;

    @Mock
    private AgentLearningService agentLearningService;

    @Mock
    private ObservationService observationService;

    @InjectMocks
    private TestGenerationService testGenerationService;

    @Test
    public void testCreateRequest() {
        TestGenerationRequest request = TestGenerationRequest.builder()
                .testType(TestType.BACKEND_API)
                .framework(TestFramework.KARATE)
                .build();

        when(requestRepository.save(any(TestGenerationRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TestGenerationRequest result = testGenerationService.createRequest(request);

        assertEquals(RequestStatus.PENDING, result.getStatus());
        verify(requestRepository, times(1)).save(request);
    }

    @Test
    public void testGenerateTestsKarateSuccess() throws Exception {
        TestGenerationRequest request = TestGenerationRequest.builder()
                .id("req-123")
                .testType(TestType.BACKEND_API)
                .framework(TestFramework.KARATE)
                .status(RequestStatus.PENDING)
                .build();

        GeneratedTestCase testCase = GeneratedTestCase.builder()
                .testName("GetPet")
                .framework(TestFramework.KARATE)
                .build();

        when(requestRepository.findById("req-123")).thenReturn(Optional.of(request));
        when(aiAgentOrchestratorService.enrichAdditionalContext(request))
                .thenReturn("API context\n\n## AI AGENT ANALYSIS\n### Product Manager Agent\nacceptance criteria");
        when(aiTestDataGenerationService.enrichAdditionalContext(request))
                .thenReturn("API context\n\n## AI-GENERATED TEST DATA\n[]");
        when(karateTestGenerator.generate(request)).thenReturn(List.of(testCase));
        when(requestRepository.save(any(TestGenerationRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CompletableFuture<List<GeneratedTestCase>> future = testGenerationService.generateTests("req-123");
        List<GeneratedTestCase> result = future.get();

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(request, result.get(0).getRequest());
        assertEquals(RequestStatus.GENERATED, request.getStatus());
        assertTrue(request.getAdditionalContext().contains("AI-GENERATED TEST DATA"));

        verify(aiAgentOrchestratorService, times(1)).enrichAdditionalContext(request);
        verify(aiTestDataGenerationService, times(1)).enrichAdditionalContext(request);
        verify(testCaseRepository, times(1)).saveAll(anyList());
        verify(requestRepository, atLeastOnce()).save(request);
    }

    @Test
    public void testGenerateTestsNoCasesGeneratedThrows() {
        TestGenerationRequest request = TestGenerationRequest.builder()
                .id("req-123")
                .testType(TestType.BACKEND_API)
                .framework(TestFramework.KARATE)
                .status(RequestStatus.PENDING)
                .build();

        when(requestRepository.findById("req-123")).thenReturn(Optional.of(request));
        when(aiAgentOrchestratorService.enrichAdditionalContext(request)).thenReturn("## AI AGENT ANALYSIS\n### Product Manager Agent\nacceptance criteria");
        when(aiTestDataGenerationService.enrichAdditionalContext(request)).thenReturn("## AI-GENERATED TEST DATA\n[]");
        when(karateTestGenerator.generate(request)).thenReturn(new ArrayList<>());

        assertThrows(TestGenerationException.class, () -> {
            try {
                testGenerationService.generateTests("req-123").get();
            } catch (Exception e) {
                throw e.getCause();
            }
        });

        assertEquals(RequestStatus.FAILED, request.getStatus());
        verify(requestRepository, atLeastOnce()).save(request);
    }

    @Test
    public void testGetRequestNotFoundThrows() {
        when(requestRepository.findById("req-not-found")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> {
            testGenerationService.getRequest("req-not-found");
        });
    }

    @Test
    public void testAddManualTestCaseWhenRequestFailed() {
        TestGenerationRequest request = TestGenerationRequest.builder()
                .id("req-123")
                .testType(TestType.BACKEND_API)
                .framework(TestFramework.KARATE)
                .status(RequestStatus.FAILED)
                .build();

        GeneratedTestCase manual = GeneratedTestCase.builder()
                .testName("ManualPaymentAuthorizationTest")
                .testContent("Feature: manual")
                .build();

        when(requestRepository.findById("req-123")).thenReturn(Optional.of(request));
        when(testCaseRepository.save(any(GeneratedTestCase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GeneratedTestCase result = testGenerationService.addManualTestCase("req-123", manual);

        assertEquals(request, result.getRequest());
        assertEquals(TestFramework.KARATE, result.getFramework());
        assertEquals("ManualPaymentAuthorizationTest.feature", result.getFileName());
        assertEquals(TestRunStatus.NOT_RUN, result.getRunStatus());
        assertTrue(result.getTestSummary().contains("[MANUAL]"));
        assertEquals(RequestStatus.GENERATED, request.getStatus());

        verify(testCaseRepository, times(1)).save(manual);
        verify(requestRepository, times(1)).save(request);
    }
}
