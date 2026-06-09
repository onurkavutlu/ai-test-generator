package com.testgen.scheduler;

import com.testgen.model.*;
import com.testgen.report.ReportOrchestrator;
import com.testgen.repository.GeneratedTestCaseRepository;
import com.testgen.repository.TestGenerationRequestRepository;
import com.testgen.runner.GeneratedJavaTestProjectService;
import com.testgen.runner.KarateRunner;
import com.testgen.runner.TestRunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class DailySchedulerServiceTest {

    @Mock
    private TestGenerationRequestRepository requestRepository;

    @Mock
    private GeneratedTestCaseRepository testCaseRepository;

    @Mock
    private KarateRunner karateRunner;

    @Mock
    private GeneratedJavaTestProjectService javaTestProjectService;

    @Mock
    private FailureAnalysisService failureAnalysisService;

    @Mock
    private ReportOrchestrator reportOrchestrator;

    @InjectMocks
    private DailySchedulerService dailySchedulerService;

    @Test
    public void testDailyRunEmpty() {
        when(requestRepository.findAllScheduled()).thenReturn(List.of());

        dailySchedulerService.dailyRun();

        verify(requestRepository, times(1)).findAllScheduled();
        verifyNoInteractions(testCaseRepository, karateRunner, failureAnalysisService, reportOrchestrator);
    }

    @Test
    public void testDailyRunOrchestration() {
        TestGenerationRequest request = TestGenerationRequest.builder()
                .id("req-123")
                .testType(TestType.BACKEND_API)
                .framework(TestFramework.KARATE)
                .scheduledRun(true)
                .autoGenerateOnFailure(true)
                .status(RequestStatus.GENERATED)
                .build();

        GeneratedTestCase tc = GeneratedTestCase.builder()
                .id("case-1")
                .testName("GetPetByIdTest")
                .framework(TestFramework.KARATE)
                .build();

        GeneratedTestCase tcFixed = GeneratedTestCase.builder()
                .id("case-fixed")
                .testName("GetPetByIdTest_Fixed_v1")
                .framework(TestFramework.KARATE)
                .build();

        when(requestRepository.findAllScheduled()).thenReturn(List.of(request));
        when(testCaseRepository.findByRequestId("req-123")).thenReturn(List.of(tc));
        
        // Mock the first case failure
        when(karateRunner.run(tc)).thenReturn(TestRunResult.ofMaven(false, "Failed to connect", 1, 0));
        when(testCaseRepository.findFailedByRequestId("req-123")).thenReturn(List.of(tc));
        
        // Mock failure analysis returning fixed test
        when(failureAnalysisService.analyzeAndGenerateNew(List.of(tc), request)).thenReturn(List.of(tcFixed));
        
        // Mock the fixed case passing
        when(karateRunner.run(tcFixed)).thenReturn(TestRunResult.ofMaven(true, "All scenarios passed", 2, 0));

        dailySchedulerService.dailyRun();

        // Verify state updates & persistence
        verify(testCaseRepository, atLeastOnce()).save(tc);
        verify(testCaseRepository, atLeastOnce()).save(tcFixed);
        verify(testCaseRepository, times(1)).saveAll(List.of(tcFixed));
        verify(requestRepository, atLeastOnce()).save(request);
        
        // Verify orchestrator reports
        verify(reportOrchestrator, times(1)).generateAndSend(eq(request), anyList());
        
        // Verify stats update
        assertEquals(1, request.getScheduledRunCount());
        assertEquals(1, request.getTotalFailureCount());
    }

    @Test
    public void testTriggerManually() {
        TestGenerationRequest request = TestGenerationRequest.builder()
                .id("req-123")
                .testType(TestType.BACKEND_API)
                .framework(TestFramework.KARATE)
                .build();

        GeneratedTestCase tc = GeneratedTestCase.builder()
                .id("case-1")
                .testName("GetPetByIdTest")
                .framework(TestFramework.KARATE)
                .build();

        when(requestRepository.findById("req-123")).thenReturn(Optional.of(request));
        when(testCaseRepository.findByRequestId("req-123")).thenReturn(List.of(tc));
        when(karateRunner.run(tc)).thenReturn(TestRunResult.ofMaven(true, "Success", 1, 0));

        SchedulerRunSummary summary = dailySchedulerService.triggerManually("req-123");

        assertNotNull(summary);
        assertEquals("req-123", summary.requestId());
        assertEquals(1, summary.totalExisting());
        assertEquals(1, summary.passed());
        assertEquals(0, summary.failed());
        assertEquals(0, summary.newGenerated());

        verify(reportOrchestrator, times(1)).generateAndSend(eq(request), anyList());
    }
}
