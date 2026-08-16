package com.testgen.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testgen.llm.LlmService;
import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestType;
import com.testgen.parser.ApiCollectionParser;
import com.testgen.parser.CurlParser;
import com.testgen.parser.GraphQLParser;
import com.testgen.parser.HarFileParser;
import com.testgen.parser.SoapXmlParser;
import com.testgen.runner.GeneratedJavaTestProjectService;
import com.testgen.service.TestGenerationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import org.mockito.ArgumentCaptor;

class RestAssuredTestGeneratorTest {

    private static final String LLM_JAVA = """
            import org.junit.jupiter.api.Test;

            public class LlmApiTest {
                @Test
                void endpointResponds() {
                }
            }
            """;

    @TempDir
    Path tempDir;

    private LlmService llmService;
    private GeneratedJavaTestProjectService projectService;
    private RestAssuredTestGenerator generator;

    @BeforeEach
    void setUp() {
        llmService = mock(LlmService.class);
        projectService = mock(GeneratedJavaTestProjectService.class);
        when(llmService.generateTestCase(anyString(), eq("REST_ASSURED"))).thenReturn(LLM_JAVA);
        ObjectMapper objectMapper = new ObjectMapper();
        generator = new RestAssuredTestGenerator(
                llmService,
                projectService,
                new GenerationLimit(),
                new CurlParser(),
                new ApiCollectionParser(objectMapper),
                new HarFileParser(objectMapper),
                new GraphQLParser(objectMapper),
                new SoapXmlParser());
    }

    @Nested
    @DisplayName("Raw cURL üretimi")
    class RawCurlGeneration {

        @Test
        void carriesParsedMethodUrlHeadersAndBodyIntoRestAssuredPrompt() {
            List<GeneratedTestCase> cases = generator.generate(request()
                    .rawPayload("curl -X POST 'https://api.example.test/v1/pets' "
                            + "-H 'Content-Type: application/json' -H 'X-Trace: measured-42' "
                            + "--data '{\"name\":\"Mavi\"}'")
                    .payloadType("CURL")
                    .additionalContext("İstek : POST https://api.example.test/v1/pets\n"
                            + "## OBSERVED FACTS\nstatus: 201")
                    .build());

            assertEquals(2, cases.size(), "LLM case ve gözlemden deterministik case üretilmeli");
            assertTrue(cases.stream().anyMatch(GeneratedTestCase::isDeterministic));
            GeneratedTestCase llmCase = cases.stream().filter(tc -> !tc.isDeterministic()).findFirst().orElseThrow();
            assertEquals(CodeCleaner.buildTestName("/v1/pets", "POST") + ".java", llmCase.getFileName());

            ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
            verify(llmService).generateTestCase(prompt.capture(), eq("REST_ASSURED"));
            assertTrue(prompt.getValue().contains("Method: POST"));
            assertTrue(prompt.getValue().contains("URL: https://api.example.test/v1/pets"));
            assertTrue(prompt.getValue().contains("Content-Type=application/json"));
            assertTrue(prompt.getValue().contains("X-Trace=measured-42"));
            assertTrue(prompt.getValue().contains("{\"name\":\"Mavi\"}"));
            assertTrue(prompt.getValue().contains("status: 201"));
        }

        @Test
        void malformedRawPayloadIsRejectedInsteadOfFallingBackToGenericStory() {
            TestGenerationException error = org.junit.jupiter.api.Assertions.assertThrows(
                    TestGenerationException.class,
                    () -> generator.generate(request()
                            .rawPayload("{\"name\":\"URL yok\"}")
                            .payloadType("CURL")
                            .build()));

            assertTrue(error.getMessage().contains("test edilebilir HTTP isteği ayrıştırılamadı"));
            verify(llmService, never()).generateTestCase(anyString(), eq("REST_ASSURED"));
            verify(projectService, never()).writeTestSource(
                    eq(TestFramework.REST_ASSURED), anyString(), anyString());
        }

        @Test
        void unsupportedStructuredPayloadIsRejectedBeforeEmbeddedUrlsCanBeMisparsedAsCurl() {
            TestGenerationException error = org.junit.jupiter.api.Assertions.assertThrows(
                    TestGenerationException.class,
                    () -> generator.generate(request()
                            .rawPayload("{\"item\":[{\"url\":\"https://api.example.test/pets\"}]}")
                            .payloadType("GRPC")
                            .build()));

            assertTrue(error.getMessage().contains("desteklenmiyor: GRPC"));
            verify(llmService, never()).generateTestCase(anyString(), eq("REST_ASSURED"));
        }
    }

    @Nested
    @DisplayName("Postman Collection ve HAR üretimi")
    class StructuredPayloadGeneration {

        @Test
        void rendersCollectionRequestsFromParsedFieldsAndHonorsMaxCases() {
            String collection = """
                    {
                      "item": [
                        {"name":"Create pet","request":{
                          "method":"POST",
                          "url":{"raw":"https://api.example.test/v1/pets"},
                          "header":[{"key":"Content-Type","value":"application/json"},
                                    {"key":"X-Trace","value":"collection-42"}],
                          "body":{"mode":"raw","raw":"{\\"name\\":\\"Mavi\\"}"}
                        }},
                        {"name":"List pets","request":{
                          "method":"GET","url":"https://api.example.test/v1/pets"
                        }}
                      ]
                    }
                    """;

            List<GeneratedTestCase> cases = generator.generate(request()
                    .rawPayload(collection)
                    .payloadType("API_COLLECTION")
                    .maxCases(1)
                    .build());

            assertEquals(1, cases.size());
            ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
            verify(llmService).generateTestCase(prompt.capture(), eq("REST_ASSURED"));
            assertTrue(prompt.getValue().contains("Method: POST"));
            assertTrue(prompt.getValue().contains("URL: https://api.example.test/v1/pets"));
            assertTrue(prompt.getValue().contains("X-Trace=collection-42"));
            assertTrue(prompt.getValue().contains("{\"name\":\"Mavi\"}"));
            assertFalse(prompt.getValue().contains("List pets"));
        }

        @Test
        void carriesRecordedHarResponseFactsAsSourceEvidence() {
            String har = """
                    {"log":{"entries":[{
                      "request":{"method":"POST","url":"https://api.example.test/v1/pets",
                        "headers":[{"name":"Content-Type","value":"application/json"}],
                        "postData":{"text":"{\\"name\\":\\"Mavi\\"}"}},
                      "response":{"status":201,"content":{"mimeType":"application/json",
                        "text":"{\\"id\\":73,\\"name\\":\\"Mavi\\"}"}}
                    }]}}
                    """;

            List<GeneratedTestCase> cases = generator.generate(request()
                    .rawPayload(har)
                    .payloadType("HAR")
                    .build());

            assertEquals(1, cases.size());
            ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
            verify(llmService).generateTestCase(prompt.capture(), eq("REST_ASSURED"));
            assertTrue(prompt.getValue().contains("gözlenen status: 201"));
            assertTrue(prompt.getValue().contains("gözlenen yanıt:"));
            assertTrue(prompt.getValue().contains("\"id\":73"));
        }

        @Test
        void rejectsMalformedCollectionWithoutCallingLlmOrWritingFiles() {
            TestGenerationException error = org.junit.jupiter.api.Assertions.assertThrows(
                    TestGenerationException.class,
                    () -> generator.generate(request()
                            .rawPayload("{\"item\":[]}")
                            .payloadType("API_COLLECTION")
                            .build()));

            assertTrue(error.getMessage().contains("test edilebilir HTTP isteği ayrıştırılamadı"));
            verify(llmService, never()).generateTestCase(anyString(), eq("REST_ASSURED"));
            verify(projectService, never()).writeTestSource(
                    eq(TestFramework.REST_ASSURED), anyString(), anyString());
        }

        @Test
        void usesDistinctFileNamesWhenStructuredRequestsShareMethodAndPath() {
            String collection = """
                    {"item":[
                      {"request":{"method":"GET","url":"https://one.example.test/pets"}},
                      {"request":{"method":"GET","url":"https://two.example.test/pets"}}
                    ]}
                    """;

            List<GeneratedTestCase> cases = generator.generate(request()
                    .rawPayload(collection)
                    .payloadType("API_COLLECTION")
                    .build());

            assertEquals(2, cases.size());
            assertEquals(2, cases.stream().map(GeneratedTestCase::getFileName).distinct().count());
            verify(llmService, times(2)).generateTestCase(anyString(), eq("REST_ASSURED"));
        }

        @Test
        void rejectsUnresolvedCollectionUrlInsteadOfInventingEnvironmentValues() {
            String collection = """
                    {"item":[{"request":{"method":"GET","url":"{{baseUrl}}/pets"}}]}
                    """;

            TestGenerationException error = org.junit.jupiter.api.Assertions.assertThrows(
                    TestGenerationException.class,
                    () -> generator.generate(request()
                            .rawPayload(collection)
                            .payloadType("API_COLLECTION")
                            .build()));

            assertTrue(error.getMessage().contains("geçerli mutlak HTTP(S) URL içermiyor"));
            verify(llmService, never()).generateTestCase(anyString(), eq("REST_ASSURED"));
        }
    }

    @Nested
    @DisplayName("GraphQL ve SOAP üretimi")
    class ProtocolPayloadGeneration {

        @Test
        void carriesExactGraphQlEndpointAndBodyWithoutInventingHeaders() {
            String graphQl = """
                    {"operationName":"ListPets","query":"query { pets { id name } }","variables":{}}
                    """;

            List<GeneratedTestCase> cases = generator.generate(request()
                    .rawPayload(graphQl)
                    .payloadType("GRAPHQL")
                    .applicationUrl("https://api.example.test/graphql-v2")
                    .build());

            assertEquals(1, cases.size());
            ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
            verify(llmService).generateTestCase(prompt.capture(), eq("REST_ASSURED"));
            assertTrue(prompt.getValue().contains("Method: POST"));
            assertTrue(prompt.getValue().contains("URL: https://api.example.test/graphql-v2"));
            assertTrue(prompt.getValue().contains("\"operationName\":\"ListPets\""));
            assertTrue(prompt.getValue().contains("query { pets { id name } }"));
            assertTrue(prompt.getValue().contains("Headers: {}"));
        }

        @Test
        void carriesExactSoapEndpointAndEnvelope() {
            String envelope = """
                    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
                      <soapenv:Body><pet:GetPet xmlns:pet="urn:pets"><pet:id>73</pet:id></pet:GetPet></soapenv:Body>
                    </soapenv:Envelope>
                    """;

            List<GeneratedTestCase> cases = generator.generate(request()
                    .rawPayload(envelope)
                    .payloadType("SOAP")
                    .applicationUrl("https://api.example.test/services/pets")
                    .build());

            assertEquals(1, cases.size());
            ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
            verify(llmService).generateTestCase(prompt.capture(), eq("REST_ASSURED"));
            assertTrue(prompt.getValue().contains("URL: https://api.example.test/services/pets"));
            assertTrue(prompt.getValue().contains("<pet:GetPet"));
            assertTrue(prompt.getValue().contains("<pet:id>73</pet:id>"));
        }

        @Test
        void graphQlWithoutApplicationUrlIsRejectedBeforeLlmCall() {
            com.testgen.config.BadRequestException error = org.junit.jupiter.api.Assertions.assertThrows(
                    com.testgen.config.BadRequestException.class,
                    () -> generator.generate(request()
                            .rawPayload("query { pets { id } }")
                            .payloadType("GRAPHQL")
                            .build()));

            assertTrue(error.getMessage().contains("gerçek endpoint zorunludur"));
            verify(llmService, never()).generateTestCase(anyString(), eq("REST_ASSURED"));
        }

        @Test
        void soapRejectsNonHttpApplicationUrl() {
            com.testgen.config.BadRequestException error = org.junit.jupiter.api.Assertions.assertThrows(
                    com.testgen.config.BadRequestException.class,
                    () -> generator.generate(request()
                            .rawPayload("<soap:Envelope/>")
                            .payloadType("SOAP")
                            .applicationUrl("ftp://api.example.test/pets")
                            .build()));

            assertTrue(error.getMessage().contains("mutlak HTTP(S) URL"));
            verify(llmService, never()).generateTestCase(anyString(), eq("REST_ASSURED"));
        }
    }

    @Test
    void declaresRestAssuredFramework() {
        assertEquals(TestFramework.REST_ASSURED, generator.framework());
    }

    @Nested
    @DisplayName("User story üretimi")
    class UserStoryGeneration {

        @Test
        void generatesRunnableJavaCaseAndPersistsIt() {
            List<GeneratedTestCase> cases = generator.generate(request()
                    .userStory("GET /pets listesini doğrula")
                    .additionalContext("Kimlik doğrulama gerekmiyor")
                    .build());

            assertEquals(1, cases.size());
            GeneratedTestCase generated = cases.get(0);
            assertEquals(TestFramework.REST_ASSURED, generated.getFramework());
            assertTrue(generated.getFileName().endsWith(".java"));
            assertTrue(generated.getTestContent().contains("package com.testgen.generated;"));
            assertTrue(generated.getTestContent().contains("org.junit.jupiter.api.Test"));
            verify(projectService).writeTestSource(
                    TestFramework.REST_ASSURED, generated.getFileName(), generated.getTestContent());
        }

        @Test
        void usesStableFallbackNameForAnonymousGeneratedClass() {
            when(llmService.generateTestCase(anyString(), eq("REST_ASSURED"))).thenReturn("""
                    public class GeneratedTest_123 {
                        public void endpointResponds() {
                        }
                    }
                    """);

            GeneratedTestCase generated = generator.generate(request().build()).get(0);

            assertEquals("GeneratedApiTest", generated.getTestName());
            assertEquals("GeneratedApiTest.java", generated.getFileName());
            assertTrue(generated.getTestContent().contains("public class GeneratedApiTest"));
        }

        @Test
        void missingUserStoryUsesDefaultPromptWithoutFailure() {
            List<GeneratedTestCase> cases = generator.generate(request()
                    .userStory(null)
                    .additionalContext(null)
                    .build());

            assertEquals(1, cases.size());
            verify(llmService).generateTestCase(anyString(), eq("REST_ASSURED"));
        }
    }

    @Nested
    @DisplayName("Gözlenen API sözleşmesi")
    class ObservedContract {

        @Test
        void addsDeterministicCaseFromCapturedResponse() {
            String observed = """
                    ## OBSERVED RESPONSE (canlı koşumdan yakalandı)
                    İstek        : GET https://api.example.test/v1/pets
                    Gözlenen Status: 200

                    ## OBSERVED FACTS (gerçek yanıttan türetildi)
                    status: 200
                    """;

            List<GeneratedTestCase> cases = generator.generate(request()
                    .additionalContext(observed)
                    .build());

            assertEquals(2, cases.size());
            GeneratedTestCase deterministic = cases.stream()
                    .filter(GeneratedTestCase::isDeterministic)
                    .findFirst()
                    .orElseThrow();
            assertEquals("ObservedApiContractTest", deterministic.getTestName());
            assertTrue(deterministic.getTestContent().contains("https://api.example.test"));
            assertTrue(deterministic.getTestContent().contains(".statusCode(200)"));
            verify(projectService).writeTestSource(TestFramework.REST_ASSURED,
                    deterministic.getFileName(), deterministic.getTestContent());
        }

        @Test
        void doesNotInventDeterministicCaseWithoutObservedFacts() {
            List<GeneratedTestCase> cases = generator.generate(request()
                    .additionalContext("İstek : GET https://api.example.test/v1/pets")
                    .build());

            assertEquals(1, cases.size());
            assertFalse(cases.get(0).isDeterministic());
        }
    }

    @Nested
    @DisplayName("OpenAPI üretimi")
    class OpenApiGeneration {

        @Test
        void generatesOneCasePerOperationFromLocalSpecification() throws IOException {
            Path spec = openApiSpec();

            List<GeneratedTestCase> cases = generator.generate(request()
                    .swaggerUrl(spec.toUri().toString())
                    .build());

            assertEquals(2, cases.size());
            assertTrue(cases.stream().allMatch(tc -> tc.getFramework() == TestFramework.REST_ASSURED));
            assertTrue(cases.stream().allMatch(tc -> tc.getFileName().endsWith(".java")));
            verify(llmService, atLeastOnce()).generateTestCase(anyString(), eq("REST_ASSURED"));
        }

        @Test
        void honorsRequestMaxCasesBeforeRenderingRemainingOperations() throws IOException {
            Path spec = openApiSpec();

            List<GeneratedTestCase> cases = generator.generate(request()
                    .swaggerUrl(spec.toUri().toString())
                    .maxCases(1)
                    .build());

            assertEquals(1, cases.size());
            verify(llmService).generateTestCase(anyString(), eq("REST_ASSURED"));
        }

        @Test
        void invalidSpecificationFallsBackToUserStoryAndPersistsResult() {
            List<GeneratedTestCase> cases = generator.generate(request()
                    .swaggerUrl(tempDir.resolve("missing-openapi.yaml").toUri().toString())
                    .userStory("Fallback sözleşmesi")
                    .build());

            assertEquals(1, cases.size());
            assertEquals(TestFramework.REST_ASSURED, cases.get(0).getFramework());
            verify(projectService).writeTestSource(TestFramework.REST_ASSURED,
                    cases.get(0).getFileName(), cases.get(0).getTestContent());
        }

        @Test
        void openApiWithoutOperationsFallsBackWithoutFabricatingEndpoint() throws IOException {
            Path spec = tempDir.resolve("empty-openapi.yaml");
            Files.writeString(spec, """
                    openapi: 3.0.3
                    info:
                      title: Empty API
                      version: 1.0.0
                    paths: {}
                    """);

            List<GeneratedTestCase> cases = generator.generate(request()
                    .swaggerUrl(spec.toUri().toString())
                    .build());

            assertTrue(cases.isEmpty());
            verify(llmService, never()).generateTestCase(anyString(), eq("REST_ASSURED"));
        }

        private Path openApiSpec() throws IOException {
            Path spec = tempDir.resolve("openapi.yaml");
            Files.writeString(spec, """
                    openapi: 3.0.3
                    info:
                      title: Pets API
                      version: 1.0.0
                    paths:
                      /pets:
                        get:
                          summary: List pets
                          responses:
                            '200':
                              description: Listed
                        post:
                          summary: Create pet
                          responses:
                            '201':
                              description: Created
                    """);
            return spec;
        }
    }

    private static TestGenerationRequest.TestGenerationRequestBuilder request() {
        return TestGenerationRequest.builder()
                .testType(TestType.BACKEND_API)
                .framework(TestFramework.REST_ASSURED);
    }
}
