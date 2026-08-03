package com.testgen.generator;

import com.testgen.llm.LlmService;
import com.testgen.llm.PromptTemplates;
import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.runner.GeneratedJavaTestProjectService;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.parser.core.models.ParseOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST Assured (JUnit 5) test üreticisi.
 * Swagger'dan endpoint başına, yoksa user story'den tek sınıf üretir.
 * Üretilen kod bağımsız Maven projesinde (rest-assured + hamcrest) koşar.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestAssuredTestGenerator {

    private final LlmService llmService;
    private final GeneratedJavaTestProjectService javaTestProjectService;

    public List<GeneratedTestCase> generate(TestGenerationRequest request) {
        List<GeneratedTestCase> results = new ArrayList<>();

        if (request.getSwaggerUrl() != null && !request.getSwaggerUrl().isBlank()) {
            results.addAll(generateFromSwagger(request));
        } else {
            results.add(generateFromUserStory(request));
        }

        return results;
    }

    private List<GeneratedTestCase> generateFromSwagger(TestGenerationRequest request) {
        List<GeneratedTestCase> cases = new ArrayList<>();
        try {
            var parseResult = new OpenAPIParser().readLocation(
                    request.getSwaggerUrl(), null, new ParseOptions());
            OpenAPI openAPI = parseResult.getOpenAPI();

            if (openAPI == null) {
                cases.add(generateFromUserStory(request));
                return cases;
            }

            for (Map.Entry<String, PathItem> entry : openAPI.getPaths().entrySet()) {
                String path = entry.getKey();
                PathItem pathItem = entry.getValue();

                pathItem.readOperationsMap().forEach((httpMethod, operation) -> {
                    String swaggerSnippet = extractOperationYaml(path, httpMethod.toString(), operation);
                    String context = request.getAdditionalContext() != null ? request.getAdditionalContext() : "";

                    String prompt = PromptTemplates.buildRestAssuredPrompt(swaggerSnippet, path, httpMethod.toString(), context);
                    String generatedContent = llmService.generateTestCase(prompt, "REST_ASSURED");

                    String className = buildClassName(path, httpMethod.toString());
                    // package/JUnit5/sınıf-adı garantisi — LLM prompt'a uymazsa derleme kırılmasın
                    String cleanContent = CodeCleaner.normalizeGeneratedJavaTest(
                            CodeCleaner.cleanJavaContent(generatedContent), className);

                    GeneratedTestCase tc = GeneratedTestCase.builder()
                            .testName(className)
                            .fileName(className + ".java")
                            .testContent(cleanContent)
                            .testSummary(String.format("[AI-DATA] %s %s için Rest-Assured testi oluşturuldu.", httpMethod, path))
                            .framework(TestFramework.REST_ASSURED)
                            .build();

                    saveToFile(tc.getFileName(), cleanContent);
                    cases.add(tc);
                });
            }
        } catch (Exception e) {
            log.error("Swagger'dan Rest-Assured test üretimi başarısız", e);
            cases.add(generateFromUserStory(request));
        }
        return cases;
    }

    private GeneratedTestCase generateFromUserStory(TestGenerationRequest request) {
        String prompt = PromptTemplates.buildUserStoryPrompt(
                request.getUserStory() != null ? request.getUserStory() : "API endpoint test",
                "REST Assured (Java)",
                request.getAdditionalContext() != null ? request.getAdditionalContext() : "");

        String content = llmService.generateTestCase(prompt, "REST_ASSURED");
        String cleanContent = CodeCleaner.normalizeGeneratedJavaTest(
                CodeCleaner.cleanJavaContent(content), null);
        // Dosya adını LLM'in verdiği class adından türet; anlamlı ad yoksa fallback'e zorla
        String extracted = CodeCleaner.publicClassName(cleanContent);
        String className = extracted.startsWith("GeneratedTest_") ? "GeneratedApiTest" : extracted;
        cleanContent = CodeCleaner.normalizeGeneratedJavaTest(cleanContent, className);

        GeneratedTestCase tc = GeneratedTestCase.builder()
                .testName(className)
                .fileName(className + ".java")
                .testContent(cleanContent)
                .testSummary("[AI-DATA][LLM-GENERATED] User story'den Rest-Assured API testi oluşturuldu.")
                .framework(TestFramework.REST_ASSURED)
                .build();

        saveToFile(tc.getFileName(), cleanContent);
        return tc;
    }

    private String buildClassName(String path, String method) {
        return method.substring(0, 1).toUpperCase() + method.substring(1).toLowerCase()
                + path.replaceAll("[/{}]", "_").replaceAll("_+", "_")
                .replaceAll("^_|_$", "")
                + "Test";
    }

    private String extractOperationYaml(String path, String method, io.swagger.v3.oas.models.Operation op) {
        StringBuilder sb = new StringBuilder();
        sb.append("paths:\n  ").append(path).append(":\n    ").append(method.toLowerCase()).append(":\n");
        if (op.getSummary() != null) sb.append("      summary: ").append(op.getSummary()).append("\n");
        return sb.toString();
    }

    private void saveToFile(String fileName, String content) {
        javaTestProjectService.writeTestSource(TestFramework.REST_ASSURED, fileName, content);
    }
}
