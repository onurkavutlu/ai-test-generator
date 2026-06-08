package com.testgen.service;

import com.testgen.generator.KarateTestGenerator;
import com.testgen.generator.SeleniumTestGenerator;
import com.testgen.generator.AppiumTestGenerator;
import com.testgen.model.*;
import com.testgen.repository.TestGenerationRequestRepository;
import com.testgen.repository.GeneratedTestCaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class TestGenerationService {

    private final KarateTestGenerator karateTestGenerator;
    private final SeleniumTestGenerator seleniumTestGenerator;
    private final AppiumTestGenerator appiumTestGenerator;
    private final TestGenerationRequestRepository requestRepository;
    private final GeneratedTestCaseRepository testCaseRepository;
    private final AiAgentOrchestratorService aiAgentOrchestratorService;
    private final AiTestDataGenerationService aiTestDataGenerationService;

    @Transactional
    public TestGenerationRequest createRequest(TestGenerationRequest request) {
        request.setStatus(RequestStatus.PENDING);
        return requestRepository.save(request);
    }

    @Async
    @Transactional(noRollbackFor = TestGenerationException.class)
    public CompletableFuture<List<GeneratedTestCase>> generateTests(String requestId) {
        var request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request bulunamadı: " + requestId));

        log.info("Test üretimi başlıyor - requestId: {}, type: {}, framework: {}",
                requestId, request.getTestType(), request.getFramework());

        try {
            request.setStatus(RequestStatus.GENERATING);
            requestRepository.save(request);

            request.setAdditionalContext(aiAgentOrchestratorService.enrichAdditionalContext(request));
            request.setAdditionalContext(aiTestDataGenerationService.enrichAdditionalContext(request));
            requestRepository.save(request);

            List<GeneratedTestCase> cases;
            cases = switch (request.getFramework()) {
                case KARATE  -> karateTestGenerator.generate(request);
                case SELENIUM -> seleniumTestGenerator.generate(request);
                case APPIUM  -> appiumTestGenerator.generate(request);
            };

            if (cases.isEmpty()) {
                throw new TestGenerationException("Calistirilabilir test case uretilemedi.");
            }

            cases.forEach(tc -> tc.setRequest(request));
            testCaseRepository.saveAll(cases);

            request.setStatus(RequestStatus.GENERATED);
            requestRepository.save(request);

            log.info("{} adet test case üretildi - requestId: {}", cases.size(), requestId);
            return CompletableFuture.completedFuture(cases);

        } catch (Exception e) {
            log.error("Test üretimi başarısız - requestId: {}", requestId, e);
            request.setStatus(RequestStatus.FAILED);
            requestRepository.save(request);
            throw new TestGenerationException("Test üretimi başarısız: " + e.getMessage(), e);
        }
    }

    public List<GeneratedTestCase> getTestCasesByRequestId(String requestId) {
        return testCaseRepository.findByRequestId(requestId);
    }

    @Transactional
    public GeneratedTestCase addManualTestCase(String requestId, GeneratedTestCase testCase) {
        TestGenerationRequest request = getRequest(requestId);
        TestFramework framework = testCase.getFramework() != null ? testCase.getFramework() : request.getFramework();
        if (framework != request.getFramework()) {
            throw new TestGenerationException("Manuel test framework'u request framework'u ile uyumlu olmalı: " + request.getFramework());
        }

        String fileName = testCase.getFileName();
        if (fileName == null || fileName.isBlank()) {
            fileName = defaultFileName(testCase.getTestName(), framework);
        }

        testCase.setRequest(request);
        testCase.setFramework(framework);
        testCase.setFileName(fileName);
        testCase.setTestSummary(testCase.getTestSummary() == null || testCase.getTestSummary().isBlank()
                ? "[MANUAL] Kullanıcı tarafından manuel eklenen test case."
                : "[MANUAL] " + testCase.getTestSummary());
        testCase.setRunStatus(testCase.getRunStatus() == null ? TestRunStatus.NOT_RUN : testCase.getRunStatus());

        GeneratedTestCase saved = testCaseRepository.save(testCase);
        if (request.getStatus() == RequestStatus.FAILED || request.getStatus() == RequestStatus.PENDING) {
            request.setStatus(RequestStatus.GENERATED);
            requestRepository.save(request);
        }
        return saved;
    }

    public TestGenerationRequest getRequest(String requestId) {
        return requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request bulunamadı: " + requestId));
    }

    public List<TestGenerationRequest> getAllRequests() {
        return requestRepository.findAll();
    }

    private String defaultFileName(String testName, TestFramework framework) {
        String extension = framework == TestFramework.KARATE ? ".feature" : ".java";
        return testName.endsWith(extension) ? testName : testName + extension;
    }
}
