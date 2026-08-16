package com.testgen.generator;

import com.testgen.llm.LlmService;
import com.testgen.llm.PromptTemplates;
import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.parser.ApiCollectionParser;
import com.testgen.parser.CurlParser;
import com.testgen.parser.GraphQLParser;
import com.testgen.parser.HarFileParser;
import com.testgen.parser.ParsedRequestDto;
import com.testgen.parser.SoapXmlParser;
import com.testgen.runner.GeneratedJavaTestProjectService;
import com.testgen.service.TestGenerationException;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.parser.core.models.ParseOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST Assured (JUnit 5) test üreticisi.
 * Swagger'dan endpoint başına, yoksa user story'den tek sınıf üretir.
 * Üretilen kod bağımsız Maven projesinde (rest-assured + hamcrest) koşar.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestAssuredTestGenerator implements FrameworkTestGenerator {

    private final LlmService llmService;
    private final GeneratedJavaTestProjectService javaTestProjectService;
    private final GenerationLimit generationLimit;
    private final CurlParser curlParser;
    private final ApiCollectionParser apiCollectionParser;
    private final HarFileParser harFileParser;
    private final GraphQLParser graphQLParser;
    private final SoapXmlParser soapXmlParser;

    @Override
    public TestFramework framework() {
        return TestFramework.REST_ASSURED;
    }

    @Override
    public List<GeneratedTestCase> generate(TestGenerationRequest request) {
        List<GeneratedTestCase> results = new ArrayList<>();

        if (request.getRawPayload() != null && !request.getRawPayload().isBlank()) {
            results.addAll(generateFromRawPayload(request));
            ObservedApiTestBuilder.buildRestAssuredCase(request.getAdditionalContext())
                    .ifPresent(tc -> {
                        saveToFile(tc.getFileName(), tc.getTestContent());
                        results.add(tc);
                    });
        } else if (request.getSwaggerUrl() != null && !request.getSwaggerUrl().isBlank()) {
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

    /**
     * cURL/CAPTURED, Postman Collection ve HAR girdisini gerçek yapılandırılmış
     * alanlarıyla REST Assured'a taşır.
     * Ayrıştırılamayan raw içerik user-story testine düşmez; o davranış gerçek endpoint,
     * metot ve gövdeyi kaybederek ilgisiz bir test üretiyordu.
     */
    private List<GeneratedTestCase> generateFromRawPayload(TestGenerationRequest request) {
        String payloadType = request.getPayloadType();
        String normalizedType = payloadType == null || payloadType.isBlank()
                ? "CURL"
                : payloadType.strip().toUpperCase(java.util.Locale.ROOT);

        List<ParsedRequestDto> parsedRequests;
        String sourceLabel;
        switch (normalizedType) {
            case "CURL", "CAPTURED" -> {
                ParsedRequestDto parsed = curlParser.parse(request.getRawPayload());
                parsedRequests = parsed == null ? List.of() : List.of(parsed);
                sourceLabel = "cURL";
            }
            case "API_COLLECTION" -> {
                parsedRequests = apiCollectionParser.parse(request.getRawPayload());
                sourceLabel = "Postman Collection";
            }
            case "HAR" -> {
                parsedRequests = harFileParser.parse(request.getRawPayload());
                sourceLabel = "HAR";
            }
            case "GRAPHQL" -> {
                String endpoint = ExplicitEndpointValidator.requireHttpUrl(request, "GraphQL");
                parsedRequests = applyEndpoint(graphQLParser.parse(request.getRawPayload()), endpoint);
                sourceLabel = "GraphQL";
            }
            case "SOAP" -> {
                String endpoint = ExplicitEndpointValidator.requireHttpUrl(request, "SOAP");
                parsedRequests = applyEndpoint(soapXmlParser.parse(request.getRawPayload()), endpoint);
                sourceLabel = "SOAP";
            }
            default -> throw new TestGenerationException("REST Assured raw payload tipi desteklenmiyor: "
                    + payloadType
                    + ". Desteklenen tipler: CURL, CAPTURED, API_COLLECTION, HAR, GRAPHQL, SOAP.");
        }

        if (parsedRequests.isEmpty()) {
            throw new TestGenerationException("REST Assured " + sourceLabel
                    + " girdisinden test edilebilir HTTP isteği ayrıştırılamadı.");
        }

        int limit = Math.min(generationLimit.resolve(request, parsedRequests.size()), parsedRequests.size());
        List<GeneratedTestCase> cases = new ArrayList<>(limit);
        Set<String> usedClassNames = new HashSet<>();
        for (int index = 0; index < limit; index++) {
            ParsedRequestDto parsed = parsedRequests.get(index);
            validateParsedRequest(parsed, sourceLabel, index);
            cases.add(generateFromParsedRequest(parsed, request, sourceLabel, index, usedClassNames));
        }
        return cases;
    }

    private List<ParsedRequestDto> applyEndpoint(List<ParsedRequestDto> parsedRequests, String endpoint) {
        if (parsedRequests == null) {
            return List.of();
        }
        return parsedRequests.stream()
                .map(parsed -> new ParsedRequestDto(
                        parsed.name(),
                        parsed.method(),
                        endpoint,
                        parsed.payloadDetails(),
                        parsed.headers(),
                        parsed.body()))
                .toList();
    }

    private GeneratedTestCase generateFromParsedRequest(
            ParsedRequestDto parsed,
            TestGenerationRequest request,
            String sourceLabel,
            int index,
            Set<String> usedClassNames) {
        String context = request.getAdditionalContext() != null ? request.getAdditionalContext() : "";
        String prompt = PromptTemplates.buildRestAssuredRawPayloadPrompt(parsed, context);
        String generatedContent = llmService.generateTestCase(prompt, "REST_ASSURED");

        String path;
        try {
            path = java.net.URI.create(parsed.url()).getPath();
        } catch (IllegalArgumentException e) {
            path = parsed.url();
        }
        String className = buildClassName(path == null || path.isBlank() ? "/" : path, parsed.method());
        if (!usedClassNames.add(className)) {
            String baseName = className.endsWith("Test")
                    ? className.substring(0, className.length() - "Test".length())
                    : className;
            int suffix = index + 1;
            className = baseName + "Item" + suffix + "Test";
            while (!usedClassNames.add(className)) {
                suffix++;
                className = baseName + "Item" + suffix + "Test";
            }
        }
        String cleanContent = CodeCleaner.normalizeRestAssuredTest(
                CodeCleaner.cleanJavaContent(generatedContent), className);

        GeneratedTestCase testCase = GeneratedTestCase.builder()
                .testName(className)
                .fileName(className + ".java")
                .testContent(cleanContent)
                .testSummary("[AI-DATA][LLM-GENERATED] Ayrıştırılmış gerçek " + sourceLabel
                        + " isteğinden REST Assured testi oluşturuldu.")
                .framework(TestFramework.REST_ASSURED)
                .build();
        saveToFile(testCase.getFileName(), testCase.getTestContent());
        return testCase;
    }

    private void validateParsedRequest(ParsedRequestDto parsed, String sourceLabel, int index) {
        if (parsed == null || parsed.method() == null || parsed.method().isBlank()
                || parsed.url() == null || parsed.url().isBlank()) {
            throw new TestGenerationException("REST Assured " + sourceLabel + " girdisindeki "
                    + (index + 1) + ". istek metot veya URL içermiyor.");
        }
        try {
            java.net.URI uri = java.net.URI.create(parsed.url());
            String scheme = uri.getScheme();
            if (uri.getHost() == null || scheme == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("absolute HTTP(S) URL required");
            }
        } catch (IllegalArgumentException error) {
            throw new TestGenerationException("REST Assured " + sourceLabel + " girdisindeki "
                    + (index + 1) + ". istek geçerli mutlak HTTP(S) URL içermiyor: " + parsed.url());
        }
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

            // Limit path sayısına değil operasyon sayısına uygulanır. Aynı path altında
            // GET + POST varsa ikisi ayrı test case'tir; path sayısını kullanmak ikinci
            // operasyonu istek limiti verilmemişken bile sessizce eliyordu.
            int operationCount = openAPI.getPaths().values().stream()
                    .mapToInt(pathItem -> pathItem.readOperationsMap().size())
                    .sum();
            int limit = generationLimit.resolve(request, operationCount);
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
