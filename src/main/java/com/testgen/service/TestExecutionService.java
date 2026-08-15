package com.testgen.service;

import com.testgen.model.*;
import com.testgen.repository.GeneratedTestCaseRepository;
import com.testgen.repository.TestExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Test Execution yaşam döngüsü.
 *
 * Koşumun kendisi {@code TestRunnerService} tarafından yürütülür; bu servis yalnızca
 * kaydı yönetir: koşum açılır (PENDING→RUNNING), her case sonucu anlık görüntü olarak
 * yazılır, sonunda özet kapatılır. Her adım KISA ve kendi transaction'ında çalışır ki
 * uzun süren koşum boyunca ilerleme dashboard'dan izlenebilsin.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TestExecutionService {

    private static final int MAX_OUTPUT_CHARS = 4_000;

    private final TestExecutionRepository executionRepository;
    private final GeneratedTestCaseRepository testCaseRepository;

    // ─────────────────────────────────────────────────────────
    // Yaşam döngüsü
    // ─────────────────────────────────────────────────────────

    @Transactional
    public TestExecution open(String name, ExecutionTrigger trigger,
                              String planId, String planName,
                              String suiteId, String suiteName,
                              String sourceExecutionId, int totalCases) {
        TestExecution execution = TestExecution.builder()
                .name(name)
                .trigger(trigger)
                .planId(planId)
                .planName(planName)
                .suiteId(suiteId)
                .suiteName(suiteName)
                .sourceExecutionId(sourceExecutionId)
                .status(ExecutionStatus.PENDING)
                .totalCases(totalCases)
                .build();
        TestExecution saved = executionRepository.save(execution);
        log.info("Koşum kaydı açıldı: '{}' ({}) — {} case, tetikleyici: {}",
                saved.getName(), saved.getId(), totalCases, trigger);
        return saved;
    }

    @Transactional
    public void markRunning(String executionId) {
        executionRepository.findById(executionId).ifPresent(execution -> {
            execution.setStatus(ExecutionStatus.RUNNING);
            execution.setStartedAt(LocalDateTime.now());
            executionRepository.save(execution);
        });
    }

    /** Tek bir case'in sonucunu koşum kaydına anlık görüntü olarak yazar. */
    @Transactional
    public void recordResult(String executionId, GeneratedTestCase testCase) {
        executionRepository.findById(executionId).ifPresent(execution -> {
            TestExecutionResult result = TestExecutionResult.builder()
                    .execution(execution)
                    .testCaseId(testCase.getId())
                    .testName(testCase.getTestName())
                    .framework(testCase.getFramework())
                    .runStatus(testCase.getRunStatus())
                    .totalScenarios(testCase.getTotalScenarios())
                    .passedScenarios(testCase.getPassedScenarios())
                    .failedScenarios(testCase.getFailedScenarios())
                    .executionTimeMs(testCase.getExecutionTimeMs())
                    .runOutput(truncate(testCase.getRunOutput()))
                    .build();
            execution.getResults().add(result);
            executionRepository.save(execution);
        });
    }

    @Transactional
    public TestExecution close(String executionId) {
        TestExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException("Koşum bulunamadı: " + executionId));

        int passed = (int) execution.getResults().stream()
                .filter(r -> r.getRunStatus() == TestRunStatus.PASSED).count();
        int failed = (int) execution.getResults().stream()
                .filter(r -> r.getRunStatus() == TestRunStatus.FAILED).count();

        // Planlanan kapsam korunur; ancak kaydedilen sonuç sayısı daha büyükse
        // (örn. kapsama sonradan case eklendiyse) geçme oranı %100'ü aşmasın
        execution.setTotalCases(Math.max(execution.getTotalCases(), execution.getResults().size()));
        execution.setPassedCases(passed);
        execution.setFailedCases(failed);
        execution.setStatus(failed == 0 && passed > 0 ? ExecutionStatus.PASSED
                : passed == 0 && failed == 0 ? ExecutionStatus.ABORTED
                : ExecutionStatus.FAILED);
        execution.setFinishedAt(LocalDateTime.now());
        if (execution.getStartedAt() != null) {
            execution.setDurationMs(Duration.between(
                    execution.getStartedAt(), execution.getFinishedAt()).toMillis());
        }
        TestExecution saved = executionRepository.save(execution);
        log.info("Koşum tamamlandı: '{}' — {} geçti / {} kaldı ({})",
                saved.getName(), passed, failed, saved.getStatus());
        return saved;
    }

    @Transactional
    public void abort(String executionId, String reason) {
        executionRepository.findById(executionId).ifPresent(execution -> {
            execution.setStatus(ExecutionStatus.ABORTED);
            execution.setFinishedAt(LocalDateTime.now());
            executionRepository.save(execution);
            log.warn("Koşum sonlandırıldı: {} — {}", executionId, reason);
        });
    }

    // ─────────────────────────────────────────────────────────
    // Sorgular
    // ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<TestExecution> list(String planId, String suiteId) {
        if (planId != null && !planId.isBlank()) {
            return executionRepository.findByPlanIdOrderByCreatedAtDesc(planId);
        }
        if (suiteId != null && !suiteId.isBlank()) {
            return executionRepository.findBySuiteIdOrderByCreatedAtDesc(suiteId);
        }
        return executionRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public TestExecution get(String executionId) {
        TestExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException("Koşum bulunamadı: " + executionId));
        execution.getResults().size(); // lazy koleksiyon tx içinde açılır
        return execution;
    }

    /**
     * Geçmiş bir koşumun kapsamını yeniden çözer.
     * Silinmiş case'ler atlanır — kapsam daralabilir, uydurulmaz.
     */
    @Transactional(readOnly = true)
    public List<GeneratedTestCase> resolveCasesForRerun(String executionId) {
        TestExecution execution = get(executionId);
        List<GeneratedTestCase> cases = new ArrayList<>();
        for (TestExecutionResult result : execution.getResults()) {
            testCaseRepository.findById(result.getTestCaseId()).ifPresent(cases::add);
        }
        if (cases.size() < execution.getResults().size()) {
            log.warn("Yeniden koşum kapsamı daraldı: {} case'ten {} tanesi hâlâ mevcut",
                    execution.getResults().size(), cases.size());
        }
        return cases;
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > MAX_OUTPUT_CHARS ? text.substring(0, MAX_OUTPUT_CHARS) + "…[kısaltıldı]" : text;
    }
}
