package com.testgen.generator;

import com.testgen.llm.LlmService;
import com.testgen.llm.PromptTemplates;
import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.parser.core.models.ParseOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Karate DSL test üreticisi.
 * Swagger/OpenAPI spec'i parse ederek her endpoint için feature dosyası üretir.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KarateTestGenerator {

    private final LlmService llmService;

    @Value("${test-generator.output.karate-path}")
    private String outputPath;

    public List<GeneratedTestCase> generate(TestGenerationRequest request) {
        List<GeneratedTestCase> results = new ArrayList<>();

        if (request.getSwaggerUrl() != null && !request.getSwaggerUrl().isBlank()) {
            // Swagger'dan endpoint'leri çıkar
            results.addAll(generateFromSwagger(request));
        } else {
            // User story'den generic Karate test üret
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
                log.warn("OpenAPI parse edilemedi: {}", request.getSwaggerUrl());
                cases.add(generateFromUserStory(request));
                return cases;
            }

            // Her path için test üret
            for (Map.Entry<String, PathItem> entry : openAPI.getPaths().entrySet()) {
                String path = entry.getKey();
                PathItem pathItem = entry.getValue();

                pathItem.readOperationsMap().forEach((httpMethod, operation) -> {
                    log.info("Karate test üretiliyor: {} {}", httpMethod, path);

                    String swaggerSnippet = extractOperationYaml(path, httpMethod.toString(), operation);
                    String context = request.getAdditionalContext() != null
                            ? request.getAdditionalContext() : "";

                    String generatedContent = llmService.generateFromSwagger(
                            swaggerSnippet, path, httpMethod.toString(), context);

                    String cleanContent = CodeCleaner.cleanFeatureContent(generatedContent);
                    String featureName = buildFeatureName(path, httpMethod.toString());

                    GeneratedTestCase tc = GeneratedTestCase.builder()
                            .testName(featureName)
                            .fileName(featureName + ".feature")
                            .testContent(cleanContent)
                            .testSummary(String.format("[AI-DATA][LLM-GENERATED] %s %s endpoint'i icin AI tarafindan uretilen test datasina gore Karate testi olusturdu.", httpMethod, path))
                            .framework(TestFramework.KARATE)
                            .build();

                    saveToFile(tc.getFileName(), cleanContent);
                    cases.add(tc);
                });
            }

        } catch (Exception e) {
            log.error("Swagger'dan Karate test üretimi başarısız", e);
            cases.add(generateFromUserStory(request));
        }

        return cases;
    }

    private GeneratedTestCase generateFromUserStory(TestGenerationRequest request) {
        String prompt = PromptTemplates.buildUserStoryPrompt(
                request.getUserStory() != null ? request.getUserStory() : "API endpoint test",
                "Karate DSL",
                request.getAdditionalContext() != null ? request.getAdditionalContext() : ""
        );

        String content = llmService.generateTestCase(prompt);
        String cleanContent = CodeCleaner.cleanFeatureContent(content);
        String featureName = "GeneratedApiTest";

        GeneratedTestCase tc = GeneratedTestCase.builder()
                .testName(featureName)
                .fileName(featureName + ".feature")
                .testContent(cleanContent)
                .testSummary("[AI-DATA][LLM-GENERATED] User story ve AI test datasina gore Karate API testi olusturdu.")
                .framework(TestFramework.KARATE)
                .build();

        saveToFile(tc.getFileName(), cleanContent);
        return tc;
    }

    private String buildFeatureName(String path, String method) {
        return method.substring(0, 1).toUpperCase() + method.substring(1).toLowerCase()
                + path.replaceAll("[/{}]", "_").replaceAll("_+", "_")
                     .replaceAll("^_|_$", "")
                + "Test";
    }

    private String extractOperationYaml(String path, String method, io.swagger.v3.oas.models.Operation op) {
        // Operasyonun özet YAML temsilini oluştur
        StringBuilder sb = new StringBuilder();
        sb.append("paths:\n  ").append(path).append(":\n    ").append(method.toLowerCase()).append(":\n");
        if (op.getSummary() != null) sb.append("      summary: ").append(op.getSummary()).append("\n");
        if (op.getDescription() != null) sb.append("      description: ").append(op.getDescription()).append("\n");
        if (op.getParameters() != null) {
            sb.append("      parameters:\n");
            op.getParameters().forEach(p -> sb.append("        - name: ").append(p.getName())
                    .append(", in: ").append(p.getIn())
                    .append(", required: ").append(p.getRequired()).append("\n"));
        }
        return sb.toString();
    }

    private void saveToFile(String fileName, String content) {
        try {
            Path dir = Path.of(outputPath);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(fileName), content);
            log.debug("Dosya kaydedildi: {}/{}", outputPath, fileName);
        } catch (IOException e) {
            log.warn("Dosya kaydedilemedi: {}", fileName, e);
        }
    }
}
