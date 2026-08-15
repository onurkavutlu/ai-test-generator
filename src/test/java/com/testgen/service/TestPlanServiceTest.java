package com.testgen.service;

import com.testgen.config.BadRequestException;
import com.testgen.model.*;
import com.testgen.repository.TestPlanRepository;
import com.testgen.repository.TestSuiteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TestPlanServiceTest {

    @Mock private TestPlanRepository planRepository;
    @Mock private TestSuiteRepository suiteRepository;
    @InjectMocks private TestPlanService planService;

    private GeneratedTestCase testCase(String id, boolean superseded) {
        return GeneratedTestCase.builder()
                .id(id).testName("T" + id).framework(TestFramework.KARATE)
                .superseded(superseded).build();
    }

    private TestSuite suite(String id, GeneratedTestCase... cases) {
        return TestSuite.builder().id(id).name("Suite " + id)
                .testCases(new java.util.ArrayList<>(List.of(cases))).build();
    }

    @Test
    public void createRequiresName() {
        assertThrows(BadRequestException.class, () -> planService.create("  ", "d", "v"));
        assertThrows(BadRequestException.class, () -> planService.create(null, "d", "v"));
    }

    @Test
    public void createTrimsNameAndVersion() {
        when(planRepository.save(any(TestPlan.class))).thenAnswer(i -> i.getArgument(0));

        TestPlan plan = planService.create("  Regresyon  ", "aciklama", "  R2025.4 ");

        assertEquals("Regresyon", plan.getName());
        assertEquals("R2025.4", plan.getVersion());
    }

    @Test
    public void addSuiteIsIdempotent() {
        TestPlan plan = TestPlan.builder().id("p1").name("P").suites(new java.util.ArrayList<>()).build();
        TestSuite s = suite("s1");
        when(planRepository.findById("p1")).thenReturn(Optional.of(plan));
        when(suiteRepository.findById("s1")).thenReturn(Optional.of(s));
        when(planRepository.save(any(TestPlan.class))).thenAnswer(i -> i.getArgument(0));

        planService.addSuite("p1", "s1");
        planService.addSuite("p1", "s1");

        assertEquals(1, plan.getSuites().size());
        verify(planRepository, times(1)).save(plan); // ikinci cagri kaydetmez
    }

    @Test
    public void resolveCasesDeduplicatesAndSkipsSuperseded() {
        GeneratedTestCase shared = testCase("c1", false);
        GeneratedTestCase only   = testCase("c2", false);
        GeneratedTestCase old    = testCase("c3", true);

        TestPlan plan = TestPlan.builder().id("p1").name("P")
                .suites(new java.util.ArrayList<>(List.of(
                        suite("s1", shared, old),
                        suite("s2", shared, only))))
                .build();
        when(planRepository.findById("p1")).thenReturn(Optional.of(plan));

        List<GeneratedTestCase> cases = planService.resolveCases("p1");

        // c1 iki suite'te de var → tek kez; c3 supersede → kapsam disi
        assertEquals(2, cases.size());
        assertEquals(List.of("c1", "c2"), cases.stream().map(GeneratedTestCase::getId).toList());
    }

    @Test
    public void getUnknownPlanThrows() {
        when(planRepository.findById("yok")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> planService.get("yok"));
    }

    @Test
    public void removeSuiteDropsMembership() {
        TestPlan plan = TestPlan.builder().id("p1").name("P")
                .suites(new java.util.ArrayList<>(List.of(suite("s1"), suite("s2")))).build();
        when(planRepository.findById("p1")).thenReturn(Optional.of(plan));
        when(planRepository.save(any(TestPlan.class))).thenAnswer(i -> i.getArgument(0));

        planService.removeSuite("p1", "s1");

        assertEquals(1, plan.getSuites().size());
        assertEquals("s2", plan.getSuites().get(0).getId());
    }

    @Test
    public void recordExecutionSummaryUpdatesPlan() {
        TestPlan plan = TestPlan.builder().id("p1").name("P").build();
        when(planRepository.findById("p1")).thenReturn(Optional.of(plan));
        when(planRepository.save(any(TestPlan.class))).thenAnswer(i -> i.getArgument(0));

        planService.recordExecutionSummary("p1", ExecutionStatus.FAILED, 3, 2);

        assertEquals(ExecutionStatus.FAILED, plan.getLastExecutionStatus());
        assertEquals(3, plan.getLastExecutionPassed());
        assertEquals(2, plan.getLastExecutionFailed());
        assertNotNull(plan.getLastExecutedAt());
    }
}
