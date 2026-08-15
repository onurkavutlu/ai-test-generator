package com.testgen.service;

import com.testgen.model.*;
import com.testgen.repository.GeneratedTestCaseRepository;
import com.testgen.repository.TestExecutionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TestExecutionServiceTest {

    @Mock private TestExecutionRepository executionRepository;
    @Mock private GeneratedTestCaseRepository testCaseRepository;
    @InjectMocks private TestExecutionService executionService;

    private TestExecution execution() {
        return TestExecution.builder()
                .id("e1").name("Koşum").trigger(ExecutionTrigger.SUITE)
                .status(ExecutionStatus.PENDING).results(new ArrayList<>()).build();
    }

    private GeneratedTestCase result(String id, TestRunStatus status) {
        return GeneratedTestCase.builder()
                .id(id).testName("T" + id).framework(TestFramework.KARATE)
                .runStatus(status).totalScenarios(2).passedScenarios(status == TestRunStatus.PASSED ? 2 : 0)
                .failedScenarios(status == TestRunStatus.PASSED ? 0 : 2).executionTimeMs(120L)
                .build();
    }

    @Test
    public void openCreatesPendingExecution() {
        when(executionRepository.save(any(TestExecution.class))).thenAnswer(i -> i.getArgument(0));

        TestExecution e = executionService.open("Plan koşumu", ExecutionTrigger.PLAN,
                "p1", "Plan", null, null, null, 5);

        assertEquals(ExecutionStatus.PENDING, e.getStatus());
        assertEquals(ExecutionTrigger.PLAN, e.getTrigger());
        assertEquals("p1", e.getPlanId());
        assertEquals(5, e.getTotalCases());
    }

    @Test
    public void recordResultStoresSnapshot() {
        TestExecution e = execution();
        when(executionRepository.findById("e1")).thenReturn(Optional.of(e));
        when(executionRepository.save(any(TestExecution.class))).thenAnswer(i -> i.getArgument(0));

        executionService.recordResult("e1", result("c1", TestRunStatus.PASSED));

        assertEquals(1, e.getResults().size());
        TestExecutionResult r = e.getResults().get(0);
        assertEquals("c1", r.getTestCaseId());
        assertEquals(TestRunStatus.PASSED, r.getRunStatus());
        assertEquals(2, r.getPassedScenarios());
        assertSame(e, r.getExecution());
    }

    @Test
    public void closeComputesCountersAndStatus() {
        TestExecution e = execution();
        e.setStartedAt(java.time.LocalDateTime.now().minusSeconds(2));
        when(executionRepository.findById("e1")).thenReturn(Optional.of(e));
        when(executionRepository.save(any(TestExecution.class))).thenAnswer(i -> i.getArgument(0));

        executionService.recordResult("e1", result("c1", TestRunStatus.PASSED));
        executionService.recordResult("e1", result("c2", TestRunStatus.FAILED));
        TestExecution closed = executionService.close("e1");

        assertEquals(1, closed.getPassedCases());
        assertEquals(1, closed.getFailedCases());
        assertEquals(ExecutionStatus.FAILED, closed.getStatus());
        assertNotNull(closed.getFinishedAt());
        assertNotNull(closed.getDurationMs());
        assertTrue(closed.isFinished());
    }

    @Test
    public void closeWithAllPassedMarksPassed() {
        TestExecution e = execution();
        when(executionRepository.findById("e1")).thenReturn(Optional.of(e));
        when(executionRepository.save(any(TestExecution.class))).thenAnswer(i -> i.getArgument(0));

        executionService.recordResult("e1", result("c1", TestRunStatus.PASSED));
        TestExecution closed = executionService.close("e1");

        assertEquals(ExecutionStatus.PASSED, closed.getStatus());
        assertEquals(100.0, closed.getPassRate());
    }

    @Test
    public void closeWithNoResultsMarksAborted() {
        TestExecution e = execution();
        when(executionRepository.findById("e1")).thenReturn(Optional.of(e));
        when(executionRepository.save(any(TestExecution.class))).thenAnswer(i -> i.getArgument(0));

        assertEquals(ExecutionStatus.ABORTED, executionService.close("e1").getStatus());
    }

    @Test
    public void rerunSkipsDeletedCases() {
        TestExecution e = execution();
        e.getResults().add(TestExecutionResult.builder().testCaseId("c1").testName("T1").build());
        e.getResults().add(TestExecutionResult.builder().testCaseId("silinmis").testName("T2").build());
        when(executionRepository.findById("e1")).thenReturn(Optional.of(e));
        when(testCaseRepository.findById("c1")).thenReturn(Optional.of(result("c1", TestRunStatus.PASSED)));
        when(testCaseRepository.findById("silinmis")).thenReturn(Optional.empty());

        List<GeneratedTestCase> cases = executionService.resolveCasesForRerun("e1");

        // Silinmis case uydurulmaz, kapsam daralir
        assertEquals(1, cases.size());
        assertEquals("c1", cases.get(0).getId());
    }

    @Test
    public void listFiltersByPlanThenSuite() {
        executionService.list("p1", null);
        verify(executionRepository).findByPlanIdOrderByCreatedAtDesc("p1");

        executionService.list(null, "s1");
        verify(executionRepository).findBySuiteIdOrderByCreatedAtDesc("s1");

        executionService.list(null, null);
        verify(executionRepository).findAllByOrderByCreatedAtDesc();
    }

    @Test
    public void getUnknownExecutionThrows() {
        when(executionRepository.findById("yok")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> executionService.get("yok"));
    }
}
