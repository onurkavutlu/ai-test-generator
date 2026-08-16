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
public class KarateTestGenerator implements FrameworkTestGenerator {

    private final LlmService llmService;
    private final ApiCollectionParser apiCollectionParser;
    private final HarFileParser harParser;
    private final GraphQLParser graphqlParser;
    private final SoapXmlParser soapXmlParser;
    private final GenerationLimit generationLimit;

    @Value("${test-generator.output.karate-path}")
    private String outputPath;

    @Override
    public TestFramework framework() {
        return TestFramework.KARATE;
    }

    @Override
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
                String payloadType = request.getPayloadType();
                if (payloadType != null && !payloadType.isBlank()
                        && !"CURL".equalsIgnoreCase(payloadType)
                        && !"CAPTURED".equalsIgnoreCase(payloadType)) {
                    throw new com.testgen.service.TestGenerationException(
                            "Karate raw payload tipi henüz desteklenmiyor: " + payloadType
                                    + ". Desteklenen tipler: CURL, CAPTURED, API_COLLECTION, HAR, GRAPHQL, SOAP.");
                }
                results.add(generateFromRawPayload(request));
                // Gözlenen yanıttan türetilmiş doğrulamalar varsa, LLM çıktısından BAĞIMSIZ
                // olarak deterministik bir case daha üretilir. Öncesinde bu yalnızca LLM
                // çıktısı bozuksa devreye giren bir yedekti; ölçümde LLM çıktısı çoğu zaman
                // koştuğu ama DÜŞTÜĞÜ için yedek hiç tetiklenmiyor, geçen test kalmıyordu.
                observedContractCase(request).ifPresent(results::add);
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

            // Limit path sayısına değil operasyon sayısına uygulanır. Aynı path altında
            // birden fazla HTTP metodu varsa her biri ayrı test case'tir.
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
                    log.info("Karate test üretiliyor: {} {}", httpMethod, path);

                    String swaggerSnippet = extractOperationYaml(path, httpMethod.toString(), operation);
                    String context = request.getAdditionalContext() != null
                            ? request.getAdditionalContext()
                            : "";

                    String generatedContent = llmService.generateFromSwagger(
                            swaggerSnippet, path, httpMethod.toString(), context);

                    // cleanFeatureContent artık bilinen LLM sözdizimi hatalarını onarıyor
                    // ("* baseUrl = ..." → "* def baseUrl = ...", çok satırlı JS bloğu tek satıra).
                    String cleanContent = CodeCleaner.cleanFeatureContent(generatedContent);
                    if (!CodeCleaner.looksRunnableFeature(cleanContent)) {
                        // Onarımdan sonra bile koşulamayacak içerik: sessizce DB'ye yazıp
                        // koşumda "0/0 FAILED" olarak görmek yerine üretim anında uyar.
                        log.warn("Üretilen feature koşulabilir görünmüyor ({} {}) — içerik yine de kaydediliyor, "
                                + "koşum sonucunu ve LLM modelini gözden geçirin.", httpMethod, path);
                    }
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
                }
            }

        } catch (Exception e) {
            log.error("Swagger'dan Karate test üretimi başarısız", e);
            cases.add(generateFromUserStory(request));
        }

        // Deterministik güvenlik ağı (Selenium'daki ObservedSmokeTest'in API karşılığı):
        // LLM çıktısı ne olursa olsun, canlı problanmış endpoint'lerden hiç tahmin
        // içermeyen bir kontrat testi eklenir. Geçen bir test self-healing'i de
        // tetiklemez — ölçümde LLM zamanının yarısını yiyen döngü budur.
        ObservedApiTestBuilder.buildKarateCase(request.getAdditionalContext())
                .ifPresent(tc -> {
                    saveToFile(tc.getFileName(), tc.getTestContent());
                    cases.add(tc);
                });

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
        String payloadType = request.getPayloadType() != null ? request.getPayloadType() : "CURL";
        String generatedContent = llmService.generateFromRawPayload(
                request.getRawPayload(), payloadType, context);

        String cleanContent = CodeCleaner.cleanFeatureContent(generatedContent);

        // Gözlem-temelli akışta (CAPTURED payload VEYA bağlamda OBSERVED RESPONSE) geçerli
        // feature garantisi: LLM çıktısı sözdizimi doğrulamasından geçemezse gözlenen
        // veriden deterministik feature üretilir. Prompt seçimiyle aynı koşul — tutarlı.
        boolean observedFlow = "CAPTURED".equalsIgnoreCase(payloadType)
                || context.contains("## OBSERVED RESPONSE");
        if (observedFlow && !looksLikeValidCapturedFeature(cleanContent)) {
            log.warn("LLM geçersiz/şüpheli Karate içeriği döndürdü — deterministik gözlem feature'ına düşülüyor.");
            cleanContent = buildDeterministicCapturedFeature(context);
        }
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
        String endpoint = ExplicitEndpointValidator.requireHttpUrl(request, "GraphQL");
        List<GeneratedTestCase> cases = new ArrayList<>();
        List<ParsedRequestDto> parsedRequests = graphqlParser.parse(request.getRawPayload());
        for (int i = 0; i < parsedRequests.size(); i++) {
            cases.add(generateSingleGraphQLRequest(parsedRequests.get(i), request, endpoint, "GraphQLItem" + i));
        }
        return cases;
    }

    private List<GeneratedTestCase> generateFromSoap(TestGenerationRequest request) {
        String endpoint = ExplicitEndpointValidator.requireHttpUrl(request, "SOAP");
        List<GeneratedTestCase> cases = new ArrayList<>();
        List<ParsedRequestDto> parsedRequests = soapXmlParser.parse(request.getRawPayload());
        for (int i = 0; i < parsedRequests.size(); i++) {
            cases.add(generateSingleSoapRequest(parsedRequests.get(i), request, endpoint, "SoapItem" + i));
        }
        return cases;
    }

    private GeneratedTestCase generateSingleGraphQLRequest(ParsedRequestDto parsed, TestGenerationRequest request,
            String endpoint, String prefix) {
        String context = request.getAdditionalContext() != null ? request.getAdditionalContext() : "";
        String requestDetails = "Kullanıcının verdiği endpoint: " + endpoint + "\n" + parsed.payloadDetails();
        String generatedContent = llmService.generateFromGraphQL(requestDetails, context);
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
            String endpoint, String prefix) {
        String context = request.getAdditionalContext() != null ? request.getAdditionalContext() : "";
        String requestDetails = "Kullanıcının verdiği endpoint: " + endpoint + "\n" + parsed.payloadDetails();
        String generatedContent = llmService.generateFromSoap(requestDetails, context);
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

    /**
     * CAPTURED akışı için hızlı sözdizimi doğrulaması.
     * llama sınıfı modellerin bilinen hataları ("* url = '...'", "* def url")
     * bu kontrolden geçemez ve deterministik fallback devreye girer.
     */
    static boolean looksLikeValidCapturedFeature(String content) {
        if (content == null || !content.contains("Feature:")) {
            return false;
        }
        boolean hasValidUrl = java.util.regex.Pattern
                .compile("(?m)^\\s*\\*\\s*url\\s+'[^']+'").matcher(content).find();
        boolean hasStatusAssertion = content.contains("Then status");
        return hasValidUrl && hasStatusAssertion;
    }

    /**
     * OBSERVED bağlamından deterministik feature üretir — her değer GÖZLEMDEN gelir.
     *
     * <p><b>Hiçbir değer uydurulmaz.</b> Önceki hâli, gözlem bulunamadığında
     * {@code status 200}, {@code method GET}, {@code url http://localhost:8080} ve
     * gözlenmemiş bir {@code responseTime < 10000} SLA'sı varsayıyordu. Sonuç:
     * kullanıcının hiç bahsetmediği bir adrese, hiç görülmemiş bir durum kodunu
     * doğrulayan ve <b>garanti geçen</b> bir test. Yeşil yanar, hiçbir şey kanıtlamaz —
     * aracın tüm güvenilirliğini bitirir.
     *
     * <p>Artık gözlem eksikse istisna fırlatılır ve çağıran case üretmez.
     *
     * @throws IllegalArgumentException bağlamda istek satırı veya türetilmiş gerçek yoksa
     */
    static String buildDeterministicCapturedFeature(String context) {
        String ctx = context == null ? "" : context;

        java.util.regex.Matcher mReq = java.util.regex.Pattern
                .compile("İstek\\s*:\\s*(\\w+)\\s+(\\S+)").matcher(ctx);
        if (!mReq.find()) {
            throw new IllegalArgumentException(
                    "Gözlem bağlamında istek satırı (\"İstek: <METHOD> <URL>\") yok — "
                            + "deterministik case üretilemez. Değer uydurulmaz.");
        }
        String method = mReq.group(1).toUpperCase(java.util.Locale.ROOT);
        String url = mReq.group(2);

        // Doğrulamalar YALNIZCA gözlenmiş değerlerden kurulur. İki gözlem biçimi geçerli:
        //   1) "## OBSERVED FACTS" — yanıttan türetilmiş tam gerçek listesi (tercih edilen)
        //   2) "Gözlenen Status: N" — türetme yoksa bile CANLI ÖLÇÜLMÜŞ durum kodu
        // İkisi de yoksa doğrulanacak gözlem yoktur ve case üretilmez.
        //
        // Kaldırılan: status için sabit 200 varsayımı ve hiç ölçülmemiş
        // "responseTime < 10000" SLA'sı. İkisi de gözlem değil, uydurmaydı.
        var derived = com.testgen.runner.AssertionCompiler.fromPromptFacts(ctx);
        List<String> steps = com.testgen.runner.AssertionCompiler.toKarateSteps(derived);

        if (steps.isEmpty()) {
            java.util.regex.Matcher mStat = java.util.regex.Pattern
                    .compile("Gözlenen Status:\\s*(\\d+)").matcher(ctx);
            if (!mStat.find()) {
                throw new IllegalArgumentException(
                        "Gözlem bağlamında ne türetilmiş gerçek (\"## OBSERVED FACTS\") ne de "
                                + "\"Gözlenen Status\" var — doğrulanacak gözlem yok, case üretilmez.");
            }
            steps = List.of("Then status " + mStat.group(1));
        }

        StringBuilder body = new StringBuilder();
        for (String step : steps) {
            body.append("    ").append(step).append('\n');
        }

        return """
                @testCaseLLM
                Feature: Yakalanan yanit dogrulamasi (deterministik)

                  Background:
                    * url '%s'

                  @smoke @testCaseLLM
                  Scenario: [SMOKE][P0_BLOCKER][EP] Gozlenen yanit dogrulanir
                    When method %s
                %s""".formatted(url, method, body);
    }

    /**
     * Gözlenen yanıttan türetilmiş doğrulamalarla, LLM'den bağımsız deterministik case.
     *
     * Yakalama anında geçmesi garantidir: her assertion gerçek yanıttan okundu.
     * Gözlem gerçeği yoksa (eski akış, erişilemeyen hedef) case üretilmez.
     */
    private java.util.Optional<GeneratedTestCase> observedContractCase(TestGenerationRequest request) {
        String ctx = request.getAdditionalContext();
        if (com.testgen.runner.AssertionCompiler.fromPromptFacts(ctx).isEmpty()) {
            return java.util.Optional.empty();
        }
        String content;
        try {
            content = buildDeterministicCapturedFeature(ctx);
        } catch (IllegalArgumentException e) {
            // Gözlem eksik: case ÜRETİLMEZ. Eksik veriyi varsayılanla doldurup "geçen"
            // bir test üretmek, hiç test üretmemekten kötüdür — yeşil bir yalan bırakır.
            log.warn("Deterministik gözlem case'i atlandı: {}", e.getMessage());
            return java.util.Optional.empty();
        }
        GeneratedTestCase tc = GeneratedTestCase.builder()
                .testName("ObservedContractTest")
                .fileName("ObservedContractTest.feature")
                .testContent(content)
                .testSummary("[OBSERVED] Gözlenen yanıttan deterministik üretildi — LLM kullanılmadı, "
                        + "tüm beklenen değerler gerçek yanıttan okundu.")
                .framework(TestFramework.KARATE)
                .deterministic(true)
                .build();
        saveToFile(tc.getFileName(), content);
        return java.util.Optional.of(tc);
    }

    private String buildFeatureName(String path, String method) {
        return CodeCleaner.buildTestName(path, method);
    }

    private String extractOperationYaml(String path, String method, io.swagger.v3.oas.models.Operation op) {
        // Operasyonun özet YAML temsilini oluştur
        StringBuilder sb = new StringBuilder();
        sb.append("paths:\n  ").append(path).append(":\n    ")
                .append(method.toLowerCase(java.util.Locale.ROOT)).append(":\n");
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
        sb.append(SwaggerSnippets.declaredResponses(op));
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
