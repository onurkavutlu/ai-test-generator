package com.testgen.service;

import com.testgen.config.BadRequestException;
import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.model.TestSuite;
import com.testgen.repository.GeneratedTestCaseRepository;
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
public class TestSuiteServiceTest {

    @Mock
    private TestSuiteRepository suiteRepository;

    @Mock
    private GeneratedTestCaseRepository testCaseRepository;

    @InjectMocks
    private TestSuiteService suiteService;

    private TestSuite suite(String id) {
        return TestSuite.builder().id(id).name("Regresyon Paketi").build();
    }

    private GeneratedTestCase testCase(String id, String name) {
        return GeneratedTestCase.builder().id(id).testName(name)
                .fileName(name + ".feature").framework(TestFramework.KARATE).build();
    }

    @Test
    public void createRequiresName() {
        assertThrows(BadRequestException.class, () -> suiteService.create("  ", null));
        assertThrows(BadRequestException.class, () -> suiteService.create(null, null));
    }

    @Test
    public void createTrimsAndSaves() {
        when(suiteRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        TestSuite created = suiteService.create("  Smoke Paketi  ", "açıklama");
        assertEquals("Smoke Paketi", created.getName());
        assertEquals("açıklama", created.getDescription());
    }

    @Test
    public void addCaseIsIdempotent() {
        TestSuite s = suite("s1");
        GeneratedTestCase tc = testCase("c1", "HealthTest");
        when(suiteRepository.findById("s1")).thenReturn(Optional.of(s));
        when(testCaseRepository.findById("c1")).thenReturn(Optional.of(tc));
        when(suiteRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        suiteService.addCase("s1", "c1");
        suiteService.addCase("s1", "c1"); // ikinci ekleme çoğaltmamalı

        assertEquals(1, s.getTestCases().size());
        verify(suiteRepository, times(1)).save(any()); // yalnız ilk eklemede save
    }

    @Test
    public void removeCaseDetachesWithoutDeletingCase() {
        TestSuite s = suite("s1");
        GeneratedTestCase tc = testCase("c1", "HealthTest");
        s.getTestCases().add(tc);
        when(suiteRepository.findById("s1")).thenReturn(Optional.of(s));
        when(suiteRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TestSuite result = suiteService.removeCase("s1", "c1");

        assertTrue(result.getTestCases().isEmpty());
        verifyNoInteractions(testCaseRepository); // case silinmez, yalnız bağ kopar
    }

    @Test
    public void attachRequestCasesLinksAllWithoutDuplicates() {
        TestSuite s = suite("s1");
        GeneratedTestCase existing = testCase("c1", "Zaten");
        s.getTestCases().add(existing);
        when(suiteRepository.findById("s1")).thenReturn(Optional.of(s));
        when(testCaseRepository.findByRequestId("req-9")).thenReturn(List.of(
                existing, testCase("c2", "Yeni1"), testCase("c3", "Yeni2")));
        when(suiteRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        suiteService.attachRequestCases("s1", "req-9");

        assertEquals(3, s.getTestCases().size());
    }

    @Test
    public void attachSwallowsErrorsBestEffort() {
        when(suiteRepository.findById("yok")).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> suiteService.attachRequestCases("yok", "req-1"));
    }

    @Test
    public void replaceCaseInSuitesSwapsHealedVersion() {
        TestSuite s = suite("s1");
        GeneratedTestCase old = testCase("old-1", "HealthTest");
        GeneratedTestCase healed = testCase("new-1", "HealthTest");
        s.getTestCases().add(old);
        when(suiteRepository.findAll()).thenReturn(List.of(s));
        when(suiteRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        suiteService.replaceCaseInSuites("old-1", healed);

        assertEquals(1, s.getTestCases().size());
        assertEquals("new-1", s.getTestCases().get(0).getId());
    }

    @Test
    public void replaceCaseInSuitesIgnoresUnrelatedSuitesAndNulls() {
        TestSuite s = suite("s1");
        s.getTestCases().add(testCase("other", "Baska"));
        when(suiteRepository.findAll()).thenReturn(List.of(s));

        suiteService.replaceCaseInSuites("olmayan", testCase("new-1", "X"));
        assertEquals("other", s.getTestCases().get(0).getId());
        verify(suiteRepository, never()).save(any());

        assertDoesNotThrow(() -> suiteService.replaceCaseInSuites(null, null));
    }

    @Test
    public void recordRunSummaryUpdatesSuite() {
        TestSuite s = suite("s1");
        when(suiteRepository.findById("s1")).thenReturn(Optional.of(s));
        when(suiteRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        suiteService.recordRunSummary("s1", 5, 2);

        assertEquals(5, s.getLastRunPassed());
        assertEquals(2, s.getLastRunFailed());
        assertNotNull(s.getLastRunAt());
    }
}
