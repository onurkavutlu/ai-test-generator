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
import com.testgen.parser.GraphQLParser;
import com.testgen.parser.HarFileParser;
import com.testgen.parser.ParsedRequestDto;
import com.testgen.parser.SoapXmlParser;
import com.testgen.parser.ApiCollectionParser;
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
    private final ApiCollectionParser apiCollectionParser;
    private final HarFileParser harParser;
    private final GraphQLParser graphqlParser;
    private final SoapXmlParser soapXmlParser;

    @Value("${test-generator.output.karate-path}")
    private String outputPath;

    public List<GeneratedTestCase> generate(TestGenerationRequest request) {
        List<GeneratedTestCase> results = new ArrayList<>();

        if (request.getRawPayload() != null && !request.getRawPayload().isBlank()) {
            if ("API_COLLECTION".equalsIgnoreCase(request.getPayloadType())) {
                results.addAll(generateFromApiCollection(request));
            } else if ("HAR".equalsIgnoreCase(request.getPayloadType())) {
                results.addAll(generateFromHar(request));
            } else if ("GRAPHQL".equalsIgnoreCase(request.getPayloadType())) {
                results.addAll(generateFromGraphQL(request));
            } else if ("SOAP".equalsIgnoreCase(request.getPayloadType())) {
                results.addAll(generateFromSoap(request));
            } else {
                results.add(generateFromRawPayload(request));
            }
        } else if (request.getSwaggerUrl() != null && !request.getSwaggerUrl().isBlank()) {
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
                            ? request.getAdditionalContext()
                            : "";

                    String generatedContent = llmService.generateFromSwagger(
                            swaggerSnippet, path, httpMethod.toString(), context);

                    String cleanContent = CodeCleaner.cleanFeatureContent(generatedContent);
                    String featureName = buildFeatureName(path, httpMethod.toString());

                    GeneratedTestCase tc = GeneratedTestCase.builder()
                            .testName(featureName)
                            .fileName(featureName + ".feature")
                            .testContent(cleanContent)
                            .testSummary(String.format(
                                    "[AI-DATA][LLM-GENERATED] %s %s endpoint'i icin AI tarafindan uretilen test datasina gore Karate testi olusturdu.",
                                    httpMethod, path))
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

    private List<GeneratedTestCase> generateFromApiCollection(TestGenerationRequest request) {
        List<GeneratedTestCase> cases = new ArrayList<>();
        List<ParsedRequestDto> parsedRequests = apiCollectionParser.parse(request.getRawPayload());
        for (int i = 0; i < parsedRequests.size(); i++) {
            cases.add(generateSingleParsedRequest(parsedRequests.get(i), request, "CollectionItem" + i));
        }
        return cases;
    }

    private List<GeneratedTestCase> generateFromHar(TestGenerationRequest request) {
        List<GeneratedTestCase> cases = new ArrayList<>();
        List<ParsedRequestDto> parsedRequests = harParser.parse(request.getRawPayload());
        for (int i = 0; i < parsedRequests.size(); i++) {
            cases.add(generateSingleParsedRequest(parsedRequests.get(i), request, "HarItem" + i));
        }
        return cases;
    }

    private GeneratedTestCase generateSingleParsedRequest(ParsedRequestDto parsed, TestGenerationRequest request,
            String prefix) {
        String context = request.getAdditionalContext() != null ? request.getAdditionalContext() : "";
        String payloadType = request.getPayloadType() != null ? request.getPayloadType() : "JSON";

        String generatedContent = llmService.generateFromRawPayload(parsed.payloadDetails(), payloadType, context);
        String cleanContent = CodeCleaner.cleanFeatureContent(generatedContent);

        String featureName = buildFeatureName(parsed.url() != null ? parsed.url() : prefix, parsed.method());

        GeneratedTestCase tc = GeneratedTestCase.builder()
                .testName(featureName)
                .fileName(featureName + ".feature")
                .testContent(cleanContent)
                .testSummary(String.format("[AI-DATA] %s analiz edilerek %s %s için Karate testi oluşturuldu.",
                        payloadType, parsed.method(), parsed.url()))
                .framework(TestFramework.KARATE)
                .build();

        saveToFile(tc.getFileName(), cleanContent);
        return tc;
    }

    private GeneratedTestCase generateFromRawPayload(TestGenerationRequest request) {
        String context = request.getAdditionalContext() != null ? request.getAdditionalContext() : "";
        String generatedContent = llmService.generateFromRawPayload(
                request.getRawPayload(),
                request.getPayloadType() != null ? request.getPayloadType() : "CURL",
                context);

        String cleanContent = CodeCleaner.cleanFeatureContent(generatedContent);
        String featureName = "RawPayloadTest";

        GeneratedTestCase tc = GeneratedTestCase.builder()
                .testName(featureName)
                .fileName(featureName + ".feature")
                .testContent(cleanContent)
                .testSummary(
                        "[AI-DATA][LLM-GENERATED] Raw payload (cURL/JSON) analiz edilerek gerçek adrese hit eden Karate testi oluşturuldu.")
                .framework(TestFramework.KARATE)
                .build();

        saveToFile(tc.getFileName(), cleanContent);
        return tc;
    }

    private List<GeneratedTestCase> generateFromGraphQL(TestGenerationRequest request) {
        List<GeneratedTestCase> cases = new ArrayList<>();
        List<ParsedRequestDto> parsedRequests = graphqlParser.parse(request.getRawPayload());
        for (int i = 0; i < parsedRequests.size(); i++) {
            cases.add(generateSingleGraphQLRequest(parsedRequests.get(i), request, "GraphQLItem" + i));
        }
        return cases;
    }

    private List<GeneratedTestCase> generateFromSoap(TestGenerationRequest request) {
        List<GeneratedTestCase> cases = new ArrayList<>();
        List<ParsedRequestDto> parsedRequests = soapXmlParser.parse(request.getRawPayload());
        for (int i = 0; i < parsedRequests.size(); i++) {
            cases.add(generateSingleSoapRequest(parsedRequests.get(i), request, "SoapItem" + i));
        }
        return cases;
    }

    private GeneratedTestCase generateSingleGraphQLRequest(ParsedRequestDto parsed, TestGenerationRequest request,
            String prefix) {
        String context = request.getAdditionalContext() != null ? request.getAdditionalContext() : "";
        String generatedContent = llmService.generateFromGraphQL(parsed.payloadDetails(), context);
        String cleanContent = CodeCleaner.cleanFeatureContent(generatedContent);
        String featureName = buildFeatureName(parsed.name() != null ? parsed.name() : prefix, parsed.method());

        GeneratedTestCase tc = GeneratedTestCase.builder()
                .testName(featureName)
                .fileName(featureName + ".feature")
                .testContent(cleanContent)
                .testSummary("[AI-DATA] GraphQL Query/Mutation analiz edilerek Karate testi oluşturuldu.")
                .framework(TestFramework.KARATE)
                .build();
        saveToFile(tc.getFileName(), cleanContent);
        return tc;
    }

    private GeneratedTestCase generateSingleSoapRequest(ParsedRequestDto parsed, TestGenerationRequest request,
            String prefix) {
        String context = request.getAdditionalContext() != null ? request.getAdditionalContext() : "";
        String generatedContent = llmService.generateFromSoap(parsed.payloadDetails(), context);
        String cleanContent = CodeCleaner.cleanFeatureContent(generatedContent);
        String featureName = buildFeatureName(parsed.name() != null ? parsed.name() : prefix, parsed.method());

        GeneratedTestCase tc = GeneratedTestCase.builder()
                .testName(featureName)
                .fileName(featureName + ".feature")
                .testContent(cleanContent)
                .testSummary("[AI-DATA] SOAP XML Envelope analiz edilerek Karate testi oluşturuldu.")
                .framework(TestFramework.KARATE)
                .build();
        saveToFile(tc.getFileName(), cleanContent);
        return tc;
    }

    private GeneratedTestCase generateFromUserStory(TestGenerationRequest request) {
        String prompt = PromptTemplates.buildUserStoryPrompt(
                request.getUserStory() != null ? request.getUserStory() : "API endpoint test",
                "Karate DSL",
                request.getAdditionalContext() != null ? request.getAdditionalContext() : "");

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
        if (op.getSummary() != null)
            sb.append("      summary: ").append(op.getSummary()).append("\n");
        if (op.getDescription() != null)
            sb.append("      description: ").append(op.getDescription()).append("\n");
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
