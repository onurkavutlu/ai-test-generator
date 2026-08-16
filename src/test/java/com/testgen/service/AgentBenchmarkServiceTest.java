package com.testgen.service;

import com.testgen.config.BadRequestException;
import com.testgen.model.AgentBenchmarkResult;
import com.testgen.model.AgentBenchmarkRun;
import com.testgen.model.BenchmarkArm;
import com.testgen.model.BenchmarkComparison;
import com.testgen.model.BenchmarkStatus;
import com.testgen.model.GeneratedTestCase;
import com.testgen.model.LlmCallLog;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestType;
import com.testgen.model.ValidationStatus;
import com.testgen.repository.AgentBenchmarkResultRepository;
import com.testgen.repository.AgentBenchmarkRunRepository;
import com.testgen.repository.GeneratedTestCaseRepository;
import com.testgen.repository.LlmCallLogRepository;
import com.testgen.runner.TestRunnerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentBenchmarkServiceTest {

    private AgentBenchmarkRunRepository runRepository;
    private AgentBenchmarkResultRepository resultRepository;
    private GeneratedTestCaseRepository testCaseRepository;
    private LlmCallLogRepository llmCallLogRepository;
    private TestGenerationService generationService;
    private TestRunnerService runnerService;
    private AgentBenchmarkService service;

    @BeforeEach
    void setUp() {
        runRepository = mock(AgentBenchmarkRunRepository.class);
        resultRepository = mock(AgentBenchmarkResultRepository.class);
        testCaseRepository = mock(GeneratedTestCaseRepository.class);
        llmCallLogRepository = mock(LlmCallLogRepository.class);
        generationService = mock(TestGenerationService.class);
        runnerService = mock(TestRunnerService.class);
        service = new AgentBenchmarkService(runRepository, resultRepository, testCaseRepository,
                llmCallLogRepository, generationService, runnerService);
    }

    @Test
    void createRejectsMissingMeasuredInput() {
        assertThrows(BadRequestException.class,
                () -> service.create(AgentBenchmarkRun.builder().name(" ").build()));
        assertThrows(BadRequestException.class,
                () -> service.create(AgentBenchmarkRun.builder().name("ölçüm").build()));
        assertThrows(BadRequestException.class,
                () -> service.create(validRun().repetitions(6).build()));
    }

    @Test
    void createAppliesMeasuredDefaultsAndPendingState() {
        AgentBenchmarkRun request = validRun().comparison(null).build();
        when(runRepository.save(request)).thenReturn(request);

        AgentBenchmarkRun saved = service.create(request);

        assertEquals(BenchmarkComparison.AGENTS_ON_OFF, saved.getComparison());
        assertEquals(BenchmarkStatus.PENDING, saved.getStatus());
        verify(runRepository).save(request);
    }

    @Test
    void executeAttributesLlmCostByRequestIdInsteadOfTimeWindow() {
        AgentBenchmarkRun run = validRun().id("bench-1").build();
        when(runRepository.findById("bench-1")).thenReturn(Optional.of(run));
        when(runRepository.save(any(AgentBenchmarkRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(generationService.createRequest(any(TestGenerationRequest.class))).thenAnswer(inv -> {
            TestGenerationRequest request = inv.getArgument(0);
            request.setId(request.isAgentsEnabled() ? "req-agents" : "req-control");
            return request;
        });
        when(generationService.generateTests(any(String.class)))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        when(testCaseRepository.findByRequestId(any(String.class))).thenReturn(List.of(
                GeneratedTestCase.builder()
                        .validationStatus(ValidationStatus.VALID)
                        .validationAttempts(1)
                        .build()));
        when(llmCallLogRepository.findByRequestIdOrderByCalledAtAsc("req-agents"))
                .thenReturn(List.of(call(120, 800), call(80, 400)));
        when(llmCallLogRepository.findByRequestIdOrderByCalledAtAsc("req-control"))
                .thenReturn(List.of(call(50, 200)));

        service.execute("bench-1");

        ArgumentCaptor<AgentBenchmarkResult> saved = ArgumentCaptor.forClass(AgentBenchmarkResult.class);
        verify(resultRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        AgentBenchmarkResult withAgents = result(saved.getAllValues(), BenchmarkArm.WITH_AGENTS);
        AgentBenchmarkResult withoutAgents = result(saved.getAllValues(), BenchmarkArm.WITHOUT_AGENTS);

        assertEquals("req-agents", withAgents.getRequestId());
        assertEquals(2, withAgents.getLlmCalls());
        assertEquals(200, withAgents.getLlmDurationMs());
        assertEquals(1_200, withAgents.getLlmPromptChars());
        assertEquals("req-control", withoutAgents.getRequestId());
        assertEquals(1, withoutAgents.getLlmCalls());
        assertEquals(50, withoutAgents.getLlmDurationMs());
        assertEquals(200, withoutAgents.getLlmPromptChars());
        assertEquals(BenchmarkStatus.COMPLETED, run.getStatus());
        verify(llmCallLogRepository, never()).findByCalledAtBetween(any(), any());
    }

    @Test
    void failedGenerationIsPersistedAsMeasuredResult() {
        AgentBenchmarkRun run = validRun().id("bench-failed").build();
        when(runRepository.findById("bench-failed")).thenReturn(Optional.of(run));
        when(runRepository.save(any(AgentBenchmarkRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(generationService.createRequest(any(TestGenerationRequest.class))).thenAnswer(inv -> {
            TestGenerationRequest request = inv.getArgument(0);
            request.setId(request.isAgentsEnabled() ? "failed-agents" : "failed-control");
            return request;
        });
        CompletableFuture<List<GeneratedTestCase>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("ölçülen üretim hatası"));
        when(generationService.generateTests(any(String.class))).thenReturn(failed);
        when(testCaseRepository.findByRequestId(any(String.class))).thenReturn(List.of());
        when(llmCallLogRepository.findByRequestIdOrderByCalledAtAsc(any(String.class)))
                .thenReturn(List.of());

        service.execute("bench-failed");

        ArgumentCaptor<AgentBenchmarkResult> saved = ArgumentCaptor.forClass(AgentBenchmarkResult.class);
        verify(resultRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertTrue(saved.getAllValues().stream()
                .allMatch(result -> "ölçülen üretim hatası".equals(result.getErrorMessage())));
        assertEquals(BenchmarkStatus.COMPLETED, run.getStatus(),
                "ölçülen kol hatası benchmark altyapı hatası değildir");
    }

    private static AgentBenchmarkRun.AgentBenchmarkRunBuilder validRun() {
        return AgentBenchmarkRun.builder()
                .name("Agent A/B")
                .testType(TestType.BACKEND_API)
                .framework(TestFramework.REST_ASSURED)
                .repetitions(1)
                .comparison(BenchmarkComparison.AGENTS_ON_OFF);
    }

    private static LlmCallLog call(long durationMs, int promptChars) {
        return LlmCallLog.builder()
                .model("test-model")
                .callType("AGENT_DEVELOPER")
                .durationMs(durationMs)
                .promptChars(promptChars)
                .success(true)
                .build();
    }

    private static AgentBenchmarkResult result(List<AgentBenchmarkResult> results, BenchmarkArm arm) {
        return results.stream().filter(result -> result.getArm() == arm).findFirst().orElseThrow();
    }
}
