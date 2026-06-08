package com.testgen.generator;

import com.testgen.llm.LlmService;
import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.runner.GeneratedJavaTestProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Appium (Mobile) test üreticisi.
 * Android (UiAutomator2) ve iOS (XCUITest) destekler.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppiumTestGenerator {

    private final LlmService llmService;
    private final GeneratedJavaTestProjectService javaTestProjectService;

    @Value("${test-generator.appium.platform}")
    private String defaultPlatform;

    public List<GeneratedTestCase> generate(TestGenerationRequest request) {
        List<GeneratedTestCase> results = new ArrayList<>();

        String appPackage = request.getAppPackage() != null
                ? request.getAppPackage() : "com.example.app";

        String userStory = request.getUserStory() != null
                ? request.getUserStory() : "Mobile uygulama testi";

        String platform = request.getAdditionalContext() != null
                && request.getAdditionalContext().contains("iOS") ? "iOS" : defaultPlatform;

        String additionalCtx = request.getAdditionalContext() != null
                ? request.getAdditionalContext() : "";

        log.info("Appium test üretiliyor - platform: {}, package: {}", platform, appPackage);

        String generatedContent = llmService.generateAppiumTest(appPackage, userStory, platform, additionalCtx);

        List<JavaClassContent> classes = CodeCleaner.splitJavaClasses(generatedContent);

        if (classes.isEmpty()) {
            String className = "GeneratedAppiumTest";
            GeneratedTestCase tc = buildTestCase(className, className + ".java",
                    generatedContent, "[AI-DATA][LLM-GENERATED] AI tarafindan uretilen mobil test datasina gore Appium " + platform + " testi olusturdu.");
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
                            "[AI-DATA][LLM-GENERATED] AI mobil test datasini kullanarak Appium " + platform + " test sinifi olusturdu: " + cls.className());
                    results.add(tc);
                }
            });
        }

        if (results.isEmpty()) {
            log.warn("LLM Appium Screen Object uretmis olabilir ama calistirilabilir test sinifi bulunamadi.");
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
                .framework(TestFramework.APPIUM)
                .build();
    }

    private void saveToFile(String fileName, String content) {
        javaTestProjectService.writeTestSource(TestFramework.APPIUM, fileName, content);
    }

    private boolean isRunnableTestClass(String className) {
        return className.endsWith("Test") || className.endsWith("Tests");
    }
}
