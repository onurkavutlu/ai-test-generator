package com.testgen.service;

import com.testgen.config.BadRequestException;
import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestSuite;
import com.testgen.repository.GeneratedTestCaseRepository;
import com.testgen.repository.TestSuiteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Kullanıcı tanımlı test suite yönetimi.
 *
 * Farklı isteklerden üretilen (veya manuel) test case'ler suite'lere bağlanır;
 * suite istenildiği zaman TestRunnerService ile yeniden koşulur.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TestSuiteService {

    private final TestSuiteRepository suiteRepository;
    private final GeneratedTestCaseRepository testCaseRepository;

    @Transactional
    public TestSuite create(String name, String description) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("Suite adı zorunludur.");
        }
        return suiteRepository.save(TestSuite.builder()
                .name(name.trim())
                .description(description)
                .build());
    }

    public List<TestSuite> list() {
        return suiteRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public TestSuite get(String suiteId) {
        TestSuite suite = suiteRepository.findById(suiteId)
                .orElseThrow(() -> new IllegalArgumentException("Suite bulunamadı: " + suiteId));
        // Dashboard case'in kaynak requestId'si ve gözlem kanıt türünü de gösterir.
        // open-in-view kapalıyken bu ilişkiler transaction dışında okunursa UI sessizce
        // eksik veri gösterir veya LazyInitializationException alır.
        suite.getTestCases().forEach(this::initializeEvidence);
        return suite;
    }

    @Transactional
    public TestSuite addCase(String suiteId, String caseId) {
        TestSuite suite = get(suiteId);
        GeneratedTestCase tc = testCaseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Test case bulunamadı: " + caseId));
        boolean already = suite.getTestCases().stream().anyMatch(c -> c.getId().equals(tc.getId()));
        if (!already) {
            suite.getTestCases().add(tc);
            suiteRepository.save(suite);
            log.info("Suite '{}' ← case '{}' eklendi", suite.getName(), tc.getTestName());
        }
        return suite;
    }

    @Transactional
    public TestSuite removeCase(String suiteId, String caseId) {
        TestSuite suite = get(suiteId);
        suite.getTestCases().removeIf(c -> c.getId().equals(caseId));
        return suiteRepository.save(suite);
    }

    @Transactional
    public void delete(String suiteId) {
        suiteRepository.delete(get(suiteId));
    }

    /** Bir üretim isteğinin tüm case'lerini suite'e bağlar (yanıt-temelli üretim sonrası otomatik). */
    @Transactional
    public void attachRequestCases(String suiteId, String requestId) {
        try {
            TestSuite suite = get(suiteId);
            List<GeneratedTestCase> cases = testCaseRepository.findByRequestId(requestId);
            for (GeneratedTestCase tc : cases) {
                if (suite.getTestCases().stream().noneMatch(c -> c.getId().equals(tc.getId()))) {
                    suite.getTestCases().add(tc);
                }
            }
            suiteRepository.save(suite);
            log.info("Suite '{}' ← istek {} case'leri bağlandı ({} adet)",
                    suite.getName(), requestId, cases.size());
        } catch (Exception e) {
            // Suite bağlama hatası üretimi geçersiz kılmaz
            log.warn("Suite'e case bağlanamadı (suite: {}, request: {}): {}", suiteId, requestId, e.getMessage());
        }
    }

    /**
     * Self-healing yeni versiyon ürettiğinde, eski FAILED case'i içeren tüm suite'lerde
     * üyeliği yeni versiyonla değiştirir — suite her zaman güncel testi koşar.
     */
    @Transactional
    public void replaceCaseInSuites(String oldCaseId, GeneratedTestCase newCase) {
        if (oldCaseId == null || newCase == null) {
            return;
        }
        try {
            for (TestSuite suite : suiteRepository.findAll()) {
                boolean removed = suite.getTestCases().removeIf(c -> c.getId().equals(oldCaseId));
                if (removed) {
                    suite.getTestCases().add(newCase);
                    suiteRepository.save(suite);
                    log.info("Suite '{}' — heal edilen versiyon devraldı: {}", suite.getName(), newCase.getTestName());
                }
            }
        } catch (Exception e) {
            log.warn("Suite üyeliği heal versiyonuyla değiştirilemedi: {}", e.getMessage());
        }
    }

    /** Suite koşum özetini günceller (TestRunnerService koşum sonrası çağırır). */
    @Transactional
    public void recordRunSummary(String suiteId, int passed, int failed) {
        suiteRepository.findById(suiteId).ifPresent(suite -> {
            suite.setLastRunAt(java.time.LocalDateTime.now());
            suite.setLastRunPassed(passed);
            suite.setLastRunFailed(failed);
            suiteRepository.save(suite);
        });
    }

    private void initializeEvidence(GeneratedTestCase testCase) {
        if (testCase.getRequest() != null) {
            testCase.getRequest().getId();
            testCase.getRequest().getAdditionalContext();
        }
    }
}
