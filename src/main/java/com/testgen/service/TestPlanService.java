package com.testgen.service;

import com.testgen.config.BadRequestException;
import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestPlan;
import com.testgen.model.TestSuite;
import com.testgen.repository.TestPlanRepository;
import com.testgen.repository.TestSuiteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test Plan yönetimi — kapsam/sürüm bazlı üst seviye kapsayıcı.
 *
 * Plan yalnızca suite'leri kapsar; test case'lerin kendisi suite üzerinden çözülür.
 * Böylece bir case birden fazla suite'te, bir suite birden fazla planda yer alabilir.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TestPlanService {

    private final TestPlanRepository planRepository;
    private final TestSuiteRepository suiteRepository;

    @Transactional
    public TestPlan create(String name, String description, String version) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("Plan adı zorunludur.");
        }
        TestPlan plan = planRepository.save(TestPlan.builder()
                .name(name.trim())
                .description(description)
                .version(version != null && !version.isBlank() ? version.trim() : null)
                .build());
        log.info("Test plan oluşturuldu: '{}' ({})", plan.getName(), plan.getId());
        return plan;
    }

    @Transactional(readOnly = true)
    public List<TestPlan> list() {
        return planRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public TestPlan get(String planId) {
        TestPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Test plan bulunamadı: " + planId));
        plan.getSuites().forEach(s -> s.getTestCases().size()); // lazy koleksiyonlar tx içinde açılır
        return plan;
    }

    @Transactional
    public TestPlan addSuite(String planId, String suiteId) {
        TestPlan plan = get(planId);
        TestSuite suite = suiteRepository.findById(suiteId)
                .orElseThrow(() -> new IllegalArgumentException("Suite bulunamadı: " + suiteId));

        boolean already = plan.getSuites().stream().anyMatch(s -> s.getId().equals(suite.getId()));
        if (!already) {
            plan.getSuites().add(suite);
            planRepository.save(plan);
            log.info("Plan '{}' ← suite '{}' eklendi", plan.getName(), suite.getName());
        }
        return plan;
    }

    @Transactional
    public TestPlan removeSuite(String planId, String suiteId) {
        TestPlan plan = get(planId);
        plan.getSuites().removeIf(s -> s.getId().equals(suiteId));
        return planRepository.save(plan);
    }

    @Transactional
    public void delete(String planId) {
        planRepository.delete(get(planId));
        log.info("Test plan silindi: {}", planId);
    }

    /**
     * Plan kapsamındaki koşulabilir case'leri döner.
     * Aynı case birden fazla suite'te olabilir — id bazında tekilleştirilir.
     * Supersede edilmiş (self-healing ile yenisi üretilmiş) case'ler kapsam dışıdır.
     */
    @Transactional(readOnly = true)
    public List<GeneratedTestCase> resolveCases(String planId) {
        TestPlan plan = get(planId);
        Map<String, GeneratedTestCase> unique = new LinkedHashMap<>();
        for (TestSuite suite : plan.getSuites()) {
            for (GeneratedTestCase tc : suite.getTestCases()) {
                if (!tc.isSuperseded()) {
                    unique.putIfAbsent(tc.getId(), tc);
                }
            }
        }
        return new ArrayList<>(unique.values());
    }

    /** Plan koşumu bittiğinde özet alanlarını günceller. */
    @Transactional
    public void recordExecutionSummary(String planId, com.testgen.model.ExecutionStatus status,
                                       int passed, int failed) {
        planRepository.findById(planId).ifPresent(plan -> {
            plan.setLastExecutedAt(java.time.LocalDateTime.now());
            plan.setLastExecutionStatus(status);
            plan.setLastExecutionPassed(passed);
            plan.setLastExecutionFailed(failed);
            planRepository.save(plan);
        });
    }
}
