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
    private final GenerationLimit generationLimit;

    public List<GeneratedTestCase> generate(TestGenerationRequest request) {
        List<GeneratedTestCase> results = new ArrayList<>();

        if (request.getSwaggerUrl() != null && !request.getSwaggerUrl().isBlank()) {
            results.addAll(generateFromSwagger(request));
        } else {
            results.add(generateFromUserStory(request));
            // generate-from-response akışı Swagger vermez; gözlem bağlamı "## OBSERVED FACTS"
            // biçimindedir. Bu dal deterministik case üretmediği için REST Assured tarafında
            // hiç geçen test kalmıyordu (canlı doğrulamada yakalandı).
            ObservedApiTestBuilder.buildRestAssuredCase(request.getAdditionalContext())
                    .ifPresent(tc -> {
                        saveToFile(tc.getFileName(), tc.getTestContent());
                        results.add(tc);
                    });
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

            // Karate ile aynı kural: maxCases verildiyse o sayıda endpoint'te dur
            int limit = generationLimit.resolve(request, openAPI.getPaths().size());
            outer:
            for (Map.Entry<String, PathItem> entry : openAPI.getPaths().entrySet()) {
                String path = entry.getKey();
                PathItem pathItem = entry.getValue();

                for (Map.Entry<PathItem.HttpMethod, io.swagger.v3.oas.models.Operation> op
                        : pathItem.readOperationsMap().entrySet()) {
                    if (cases.size() >= limit) {
                        log.info("maxCases sınırına ulaşıldı ({}), kalan endpoint'ler atlanıyor", limit);
                        break outer;
                    }
                    var httpMethod = op.getKey();
                    var operation = op.getValue();
                    String swaggerSnippet = extractOperationYaml(path, httpMethod.toString(), operation);
                    String context = request.getAdditionalContext() != null ? request.getAdditionalContext() : "";

                    String prompt = PromptTemplates.buildRestAssuredPrompt(swaggerSnippet, path, httpMethod.toString(), context);
                    String generatedContent = llmService.generateTestCase(prompt, "REST_ASSURED");

                    String className = buildClassName(path, httpMethod.toString());
                    // package/JUnit5/sınıf-adı garantisi — LLM prompt'a uymazsa derleme kırılmasın
                    String cleanContent = CodeCleaner.normalizeRestAssuredTest(
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
                }
            }
        } catch (Exception e) {
            log.error("Swagger'dan Rest-Assured test üretimi başarısız", e);
            cases.add(generateFromUserStory(request));
        }

        // Karate ile aynı deterministik güvenlik ağı: canlı problanmış endpoint'lerden
        // hiç tahmin içermeyen kontrat testi. Bkz. ObservedApiTestBuilder.
        ObservedApiTestBuilder.buildRestAssuredCase(request.getAdditionalContext())
                .ifPresent(tc -> {
                    saveToFile(tc.getFileName(), tc.getTestContent());
                    cases.add(tc);
                });

        return cases;
    }

    private GeneratedTestCase generateFromUserStory(TestGenerationRequest request) {
        String prompt = PromptTemplates.buildUserStoryPrompt(
                request.getUserStory() != null ? request.getUserStory() : "API endpoint test",
                "REST Assured (Java)",
                request.getAdditionalContext() != null ? request.getAdditionalContext() : "");

        String content = llmService.generateTestCase(prompt, "REST_ASSURED");
        String cleanContent = CodeCleaner.normalizeRestAssuredTest(
                CodeCleaner.cleanJavaContent(content), null);
        // Dosya adını LLM'in verdiği class adından türet; anlamlı ad yoksa fallback'e zorla
        String extracted = CodeCleaner.publicClassName(cleanContent);
        String className = extracted.startsWith("GeneratedTest_") ? "GeneratedApiTest" : extracted;
        cleanContent = CodeCleaner.normalizeRestAssuredTest(cleanContent, className);

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
        return CodeCleaner.buildTestName(path, method);
    }

    private String extractOperationYaml(String path, String method, io.swagger.v3.oas.models.Operation op) {
        StringBuilder sb = new StringBuilder();
        sb.append("paths:\n  ").append(path).append(":\n    ")
                .append(method.toLowerCase(java.util.Locale.ROOT)).append(":\n");
        if (op.getSummary() != null) sb.append("      summary: ").append(op.getSummary()).append("\n");
        sb.append(SwaggerSnippets.declaredResponses(op));
        return sb.toString();
    }

    private void saveToFile(String fileName, String content) {
        javaTestProjectService.writeTestSource(TestFramework.REST_ASSURED, fileName, content);
    }
}
