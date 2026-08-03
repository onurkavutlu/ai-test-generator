package com.testgen.runner;

import com.testgen.model.*;
import com.testgen.report.ReportOrchestrator;
import com.testgen.repository.GeneratedTestCaseRepository;
import com.testgen.repository.TestGenerationRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TestRunnerServiceTest {

    @Mock
    private GeneratedTestCaseRepository testCaseRepository;

    @Mock
    private TestGenerationRequestRepository requestRepository;

    @Mock
    private KarateRunner karateRunner;

    @Mock
    private ReportOrchestrator reportOrchestrator;

    @Mock
    private GeneratedJavaTestProjectService javaTestProjectService;


    @Mock
    private com.testgen.service.AgentLearningService agentLearningService;

    @Mock
    private com.testgen.service.TestSuiteService testSuiteService;
    @InjectMocks
    private TestRunnerService testRunnerService;

    @Test
    public void testRunTestKarateSuccess() throws Exception {
        TestGenerationRequest request = TestGenerationRequest.builder().id("req-123").build();
        GeneratedTestCase tc = GeneratedTestCase.builder()
                .id("case-123")
                .testName("GetPet")
                .fileName("GetPet.feature")
                .testContent("Feature: test")
                .framework(TestFramework.KARATE)
                .request(request)
                .build();

        when(testCaseRepository.findById("case-123")).thenReturn(Optional.of(tc));
        when(karateRunner.run(any(GeneratedTestCase.class))).thenReturn(TestRunResult.ofMaven(true, "Karate results summary", 1, 0));
        when(testCaseRepository.findByRequestId("req-123")).thenReturn(List.of(tc));

        CompletableFuture<GeneratedTestCase> future = testRunnerService.runTest("case-123");
        GeneratedTestCase result = future.get();

        assertNotNull(result);
        assertEquals(TestRunStatus.PASSED, result.getRunStatus());

        verify(karateRunner, times(1)).run(any(GeneratedTestCase.class));
        verify(reportOrchestrator, times(1)).generateAndSend(eq(request), anyList());
        verify(testCaseRepository, atLeastOnce()).save(tc);
    }

    @Test
    public void testRunAllForRequest() throws Exception {
        TestGenerationRequest request = TestGenerationRequest.builder().id("req-123").build();
        GeneratedTestCase tc = GeneratedTestCase.builder()
                .id("case-123")
                .testName("GetPet")
                .fileName("GetPet.feature")
                .testContent("Feature: test")
                .framework(TestFramework.KARATE)
                .request(request)
                .build();

        when(requestRepository.findById("req-123")).thenReturn(Optional.of(request));
        when(testCaseRepository.findByRequestId("req-123")).thenReturn(List.of(tc));
        when(karateRunner.run(any(GeneratedTestCase.class))).thenReturn(TestRunResult.ofMaven(true, "Passed", 1, 0));

        CompletableFuture<Void> future = testRunnerService.runAllForRequest("req-123", null);
        future.get();

        verify(karateRunner, times(1)).run(any(GeneratedTestCase.class));
        verify(reportOrchestrator, times(1)).generateAndSend(eq(request), anyList(), eq(null));
    }
}
