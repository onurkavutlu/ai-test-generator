package com.testgen.generator;

import com.testgen.llm.LlmService;
import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.runner.GeneratedJavaTestProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Selenium WebDriver test üreticisi (Frontend/Web).
 * Page Object Model pattern ile Java + JUnit 5 test sınıfları üretir.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeleniumTestGenerator {

    private final LlmService llmService;
    private final GeneratedJavaTestProjectService javaTestProjectService;

    public List<GeneratedTestCase> generate(TestGenerationRequest request) {
        List<GeneratedTestCase> results = new ArrayList<>();

        String pageUrl = request.getApplicationUrl() != null
                ? request.getApplicationUrl() : "http://localhost:3000";

        String userStory = request.getUserStory() != null
                ? request.getUserStory() : "Web uygulama testi";

        String htmlHint = request.getAdditionalContext() != null
                ? request.getAdditionalContext() : "";

        log.info("Selenium test üretiliyor - URL: {}", pageUrl);

        // LLM'den hem Page Object hem de Test sınıfını üret
        String generatedContent = llmService.generateSeleniumTest(pageUrl, userStory, htmlHint);

        // İki sınıfı birbirinden ayır
        List<JavaClassContent> classes = CodeCleaner.splitJavaClasses(generatedContent);

        if (classes.isEmpty()) {
            // Split başarısız olduysa tek dosya olarak kaydet
            String className = "GeneratedSeleniumTest";
            GeneratedTestCase tc = buildTestCase(className, className + ".java",
                    generatedContent, "[AI-DATA][LLM-GENERATED] AI tarafindan uretilen web test datasina gore Selenium WebDriver testi olusturdu.");
            saveToFile(tc.getFileName(), generatedContent);
            results.add(tc);
        } else {
            classes.forEach(cls -> {
                String clean = CodeCleaner.cleanJavaContent(cls.content());
                String fileName = cls.className() + ".java";
                saveToFile(fileName, clean);
                if (isRunnableTestClass(cls.className())) {
                    GeneratedTestCase tc = buildTestCase(
                            cls.className(), fileName, clean,
                            "[AI-DATA][LLM-GENERATED] AI web test datasini kullanarak Selenium test sinifi olusturdu: " + cls.className());
                    results.add(tc);
                }
            });
        }

        if (results.isEmpty()) {
            log.warn("LLM Selenium Page Object uretmis olabilir ama calistirilabilir test sinifi bulunamadi.");
        }

        return results;
    }

    private GeneratedTestCase buildTestCase(String name, String fileName,
                                             String content, String summary) {
        return GeneratedTestCase.builder()
                .testName(name)
                .fileName(fileName)
                .testContent(content)
                .testSummary(summary)
                .framework(TestFramework.SELENIUM)
                .build();
    }

    private void saveToFile(String fileName, String content) {
        javaTestProjectService.writeTestSource(TestFramework.SELENIUM, fileName, content);
    }

    private boolean isRunnableTestClass(String className) {
        return className.endsWith("Test") || className.endsWith("Tests");
    }
}
