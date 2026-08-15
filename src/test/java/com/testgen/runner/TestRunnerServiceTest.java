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

    @Mock
    private com.testgen.metrics.TestGenMetrics metrics;

    @Mock
    private com.testgen.scheduler.FailureAnalysisService failureAnalysisService;
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
        // Rapor adımı LAZY proxy yerine satırı yeniden okur
        when(requestRepository.findById("req-123")).thenReturn(Optional.of(request));
        when(testCaseRepository.findByRequestIdAndSupersededFalse("req-123")).thenReturn(List.of(tc));

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
        when(testCaseRepository.findByRequestIdAndSupersededFalse("req-123")).thenReturn(List.of(tc));
        when(karateRunner.run(any(GeneratedTestCase.class))).thenReturn(TestRunResult.ofMaven(true, "Passed", 1, 0));

        CompletableFuture<Void> future = testRunnerService.runAllForRequest("req-123", null);
        future.get();

        verify(karateRunner, times(1)).run(any(GeneratedTestCase.class));
        verify(reportOrchestrator, times(1)).generateAndSend(eq(request), anyList(), eq(null));
    }

    @Test
    public void supersededCasesAreNotRun() throws Exception {
        TestGenerationRequest request = TestGenerationRequest.builder().id("req-123").build();

        // Supersede edilmiş case repository sorgusuyla zaten elenir; koşum listesi boş kalır.
        when(requestRepository.findById("req-123")).thenReturn(Optional.of(request));
        when(testCaseRepository.findByRequestIdAndSupersededFalse("req-123")).thenReturn(List.of());

        testRunnerService.runAllForRequest("req-123", null).get();

        verify(karateRunner, never()).run(any(GeneratedTestCase.class));
        verify(reportOrchestrator, times(1)).generateAndSend(eq(request), anyList(), eq(null));
    }

    @Test
    public void mavenOutputPartialFailureIsReportedWithRealCounts() {
        // Surefire özeti: 10 testten 4'ü düştü → "hepsi geçti/hiçbiri geçmedi" değil, 6/4 olmalı
        String output = "[INFO] Tests run: 10, Failures: 4, Errors: 0, Skipped: 0";
        TestRunResult result = TestRunResult.fromSurefireOutput(false, output, 1234);

        assertEquals(10, result.total());
        assertEquals(6, result.passedCount());
        assertEquals(4, result.failedCount());
        assertEquals(1234, result.durationMs());
        assertEquals(false, result.passed());
    }

    // ─────────────────────────────────────────────────────────
    // Self-healing artık OTOMATİK tetiklenmez (kullanıcı başlatır)
    // ─────────────────────────────────────────────────────────

    private GeneratedTestCase failedCase(TestGenerationRequest request) {
        return GeneratedTestCase.builder()
                .id("case-failed")
                .testName("GetPet")
                .fileName("GetPet.feature")
                .testContent("Feature: test")
                .framework(TestFramework.KARATE)
                .runStatus(TestRunStatus.FAILED)
                .request(request)
                .build();
    }

    @Test
    public void koşumSonrasiSelfHealingOtomatikCalismaz() throws Exception {
        TestGenerationRequest request = TestGenerationRequest.builder().id("req-123").build();
        GeneratedTestCase tc = failedCase(request);

        when(requestRepository.findById("req-123")).thenReturn(Optional.of(request));
        when(testCaseRepository.findByRequestIdAndSupersededFalse("req-123")).thenReturn(List.of(tc));
        when(karateRunner.run(any(GeneratedTestCase.class)))
                .thenReturn(TestRunResult.ofMaven(false, "Failed", 0, 1));

        testRunnerService.runAllForRequest("req-123", null).get();

        verify(failureAnalysisService, never()).analyzeAndGenerateNew(anyList(), any());
    }

    @Test
    public void kullaniciTetikleyinceSelfHealingCalisir() throws Exception {
        TestGenerationRequest request = TestGenerationRequest.builder().id("req-123").build();
        GeneratedTestCase tc = failedCase(request);

        when(requestRepository.findById("req-123")).thenReturn(Optional.of(request));
        when(testCaseRepository.findByRequestIdAndSupersededFalse("req-123")).thenReturn(List.of(tc));
        when(failureAnalysisService.analyzeAndGenerateNew(anyList(), any())).thenReturn(List.of());

        testRunnerService.selfHealForRequest("req-123").get();

        verify(failureAnalysisService, times(1)).analyzeAndGenerateNew(anyList(), eq(request));
    }
}
