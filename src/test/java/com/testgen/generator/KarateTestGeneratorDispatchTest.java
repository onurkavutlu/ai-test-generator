package com.testgen.generator;

import com.testgen.llm.LlmService;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestType;
import com.testgen.parser.ApiCollectionParser;
import com.testgen.parser.GraphQLParser;
import com.testgen.parser.HarFileParser;
import com.testgen.parser.ParsedRequestDto;
import com.testgen.parser.SoapXmlParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Karate üreticisinin <b>girdi tipine göre yönlendirmesi</b>.
 *
 * <p>{@code generate()} tek giriş noktası ama beş ayrı üretim yolunu besliyor:
 * API collection, HAR, GraphQL, SOAP, ham yük ve (yük yoksa) Swagger / kullanıcı
 * hikayesi. Yanlış dala düşmek sessiz bir hatadır — üretim "başarılı" tamamlanır ama
 * kullanıcının verdiği Postman collection'ı hiç okunmamış olur, tek bir generic test
 * üretilir ve kimse fark etmez.
 *
 * <p>Mevcut {@code KarateTestGeneratorTest} yalnızca deterministik feature üretimini
 * kapsıyordu; burada yönlendirme mantığı kilitleniyor.
 */
class KarateTestGeneratorDispatchTest {

    @TempDir
    Path tempDir;

    private LlmService llmService;
    private ApiCollectionParser apiCollectionParser;
    private HarFileParser harParser;
    private GraphQLParser graphqlParser;
    private SoapXmlParser soapXmlParser;
    private KarateTestGenerator generator;

    @BeforeEach
    void setUp() {
        llmService = mock(LlmService.class);
        apiCollectionParser = mock(ApiCollectionParser.class);
        harParser = mock(HarFileParser.class);
        graphqlParser = mock(GraphQLParser.class);
        soapXmlParser = mock(SoapXmlParser.class);

        when(llmService.generateTestCase(anyString()))
                .thenReturn(FEATURE);
        when(llmService.generateTestCase(anyString(), anyString()))
                .thenReturn(FEATURE);
        when(llmService.generateFromRawPayload(anyString(), anyString(), anyString()))
                .thenReturn(FEATURE);
        when(llmService.generateFromGraphQL(anyString(), anyString())).thenReturn(FEATURE);
        when(llmService.generateFromSoap(anyString(), anyString())).thenReturn(FEATURE);

        generator = new KarateTestGenerator(llmService, apiCollectionParser, harParser,
                graphqlParser, soapXmlParser, new GenerationLimit());
        ReflectionTestUtils.setField(generator, "outputPath", tempDir.toString());
    }

    private static final String FEATURE = """
            Feature: Uretilen test
              Background:
                * url 'http://localhost:8080'
              Scenario: [SMOKE][P0_BLOCKER][EP] Gecerli istek
                Given path '/api/pets'
                When method GET
                Then status 200
            """;

    private ParsedRequestDto parsed(String name, String method, String url) {
        return new ParsedRequestDto(name, method, url,
                method + " " + url + "\nHeaders: (yok)", Map.of(), null);
    }

    private TestGenerationRequest.TestGenerationRequestBuilder request() {
        return TestGenerationRequest.builder()
                .testType(TestType.BACKEND_API)
                .framework(TestFramework.KARATE)
                .userStory("Kullanıcı evcil hayvan bilgisini görebilmeli");
    }

    @Nested
    @DisplayName("Girdi tipine göre yönlendirme")
    class InputDispatch {

        @Test
        @DisplayName("API_COLLECTION yükü collection ayrıştırıcıya gider")
        void apiCollectionGoesToCollectionParser() {
            when(apiCollectionParser.parse(anyString())).thenReturn(List.of(
                    parsed("Pets", "GET", "http://localhost:8080/api/pets")));

            var cases = generator.generate(request()
                    .rawPayload("{\"item\":[]}").payloadType("API_COLLECTION").build());

            verify(apiCollectionParser).parse(anyString());
            verify(harParser, never()).parse(anyString());
            assertFalse(cases.isEmpty(), "Collection'dan hiç case üretilmedi");
        }

        @Test
        @DisplayName("HAR yükü HAR ayrıştırıcıya gider")
        void harGoesToHarParser() {
            when(harParser.parse(anyString())).thenReturn(List.of(
                    parsed("Pets", "GET", "http://localhost:8080/api/pets")));

            generator.generate(request().rawPayload("{\"log\":{}}").payloadType("HAR").build());

            verify(harParser).parse(anyString());
            verify(apiCollectionParser, never()).parse(anyString());
        }

        @Test
        @DisplayName("GRAPHQL yükü GraphQL ayrıştırıcıya gider")
        void graphqlGoesToGraphqlParser() {
            when(graphqlParser.parse(anyString())).thenReturn(List.of(
                    parsed("pets", "POST", "http://localhost:8080/graphql")));

            generator.generate(request().rawPayload("query { pets { id } }")
                    .payloadType("GRAPHQL")
                    .applicationUrl("https://api.example.test/graphql")
                    .build());

            verify(graphqlParser).parse(anyString());
            verify(llmService).generateFromGraphQL(
                    org.mockito.ArgumentMatchers.contains("https://api.example.test/graphql"), anyString());
        }

        @Test
        @DisplayName("SOAP yükü SOAP ayrıştırıcıya gider")
        void soapGoesToSoapParser() {
            when(soapXmlParser.parse(anyString())).thenReturn(List.of(
                    parsed("GetPet", "POST", "http://localhost:8080/soap")));

            generator.generate(request().rawPayload("<soap:Envelope/>")
                    .payloadType("SOAP")
                    .applicationUrl("https://api.example.test/soap")
                    .build());

            verify(soapXmlParser).parse(anyString());
            verify(llmService).generateFromSoap(
                    org.mockito.ArgumentMatchers.contains("https://api.example.test/soap"), anyString());
        }

        @Test
        @DisplayName("GraphQL hedefi verilmediyse /graphql uydurulmaz")
        void graphqlWithoutExplicitEndpointIsRejected() {
            var error = org.junit.jupiter.api.Assertions.assertThrows(
                    com.testgen.config.BadRequestException.class,
                    () -> generator.generate(request()
                            .rawPayload("query { pets { id } }")
                            .payloadType("GRAPHQL")
                            .build()));

            assertTrue(error.getMessage().contains("gerçek endpoint zorunludur"));
            verify(graphqlParser, never()).parse(anyString());
        }

        @Test
        @DisplayName("SOAP hedefi verilmediyse /soap-endpoint uydurulmaz")
        void soapWithoutExplicitEndpointIsRejected() {
            var error = org.junit.jupiter.api.Assertions.assertThrows(
                    com.testgen.config.BadRequestException.class,
                    () -> generator.generate(request()
                            .rawPayload("<soap:Envelope/>")
                            .payloadType("SOAP")
                            .build()));

            assertTrue(error.getMessage().contains("gerçek endpoint zorunludur"));
            verify(soapXmlParser, never()).parse(anyString());
        }

        /**
         * Yük tipi büyük/küçük harf duyarsız eşleşmeli; "har" yazan bir istek generic
         * dala düşerse kullanıcının HAR dosyası hiç okunmaz.
         */
        @Test
        @DisplayName("Yük tipi büyük/küçük harf duyarsızdır")
        void payloadTypeIsCaseInsensitive() {
            when(harParser.parse(anyString())).thenReturn(List.of(
                    parsed("Pets", "GET", "http://x/api")));

            generator.generate(request().rawPayload("{}").payloadType("har").build());

            verify(harParser).parse(anyString());
        }

        @Test
        @DisplayName("Tanınmayan yük tipi ham yük üretimine düşer")
        void unknownPayloadTypeFallsBackToRawPayload() {
            generator.generate(request().rawPayload("curl -X GET 'http://x/api'")
                    .payloadType("CAPTURED").build());

            verify(llmService).generateFromRawPayload(anyString(), anyString(), anyString());
            verify(harParser, never()).parse(anyString());
            verify(apiCollectionParser, never()).parse(anyString());
        }

        @Test
        @DisplayName("Endpoint ve metot taşımayan JSON raw yük generic teste düşmez")
        void jsonBodyWithoutHttpRequestIsRejected() {
            var error = org.junit.jupiter.api.Assertions.assertThrows(
                    com.testgen.service.TestGenerationException.class,
                    () -> generator.generate(request()
                            .rawPayload("{\"name\":\"Mavi\"}")
                            .payloadType("JSON")
                            .build()));

            assertTrue(error.getMessage().contains("henüz desteklenmiyor: JSON"));
            verify(llmService, never()).generateFromRawPayload(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("Yük yoksa ve Swagger yoksa kullanıcı hikayesinden üretilir")
        void noPayloadNoSwaggerUsesUserStory() {
            var cases = generator.generate(request().build());

            assertEquals(1, cases.size());
            assertEquals("GeneratedApiTest", cases.get(0).getTestName());
            verify(llmService).generateTestCase(anyString());
        }

        @Test
        @DisplayName("Boş yük metni yük yokmuş gibi ele alınır")
        void blankPayloadIsTreatedAsAbsent() {
            var cases = generator.generate(request().rawPayload("   ").payloadType("HAR").build());

            verify(harParser, never()).parse(anyString());
            assertEquals(1, cases.size());
        }
    }

    @Nested
    @DisplayName("Üretilen case'in biçimi")
    class GeneratedCaseShape {

        @Test
        @DisplayName("Kullanıcı hikayesi case'i Karate framework'ü ve .feature uzantısı taşır")
        void userStoryCaseHasKarateShape() {
            var testCase = generator.generate(request().build()).get(0);

            assertEquals(TestFramework.KARATE, testCase.getFramework());
            assertTrue(testCase.getFileName().endsWith(".feature"), testCase.getFileName());
            assertTrue(testCase.getTestContent().contains("Feature:"));
        }

        /**
         * Özet metni raporlarda üretim/iyileştirme ayrımını belirliyor; etiketler
         * düşerse TestReportSummary case'i yanlış bölüme koyar.
         */
        @Test
        @DisplayName("Üretim etiketleri özet metnine yazılır")
        void summaryCarriesGenerationTags() {
            var testCase = generator.generate(request().build()).get(0);

            assertTrue(testCase.getTestSummary().contains("[LLM-GENERATED]"),
                    "Üretim etiketi eksik: " + testCase.getTestSummary());
        }

        @Test
        @DisplayName("Üretilen içerik diske yazılır")
        void contentIsPersistedToDisk() {
            var testCase = generator.generate(request().build()).get(0);

            assertTrue(java.nio.file.Files.exists(tempDir.resolve(testCase.getFileName())),
                    "Feature dosyası diske yazılmamış");
        }

        @Test
        @DisplayName("Kullanıcı hikayesi verilmezse varsayılan metin kullanılır")
        void missingUserStoryUsesDefault() {
            var cases = generator.generate(TestGenerationRequest.builder()
                    .testType(TestType.BACKEND_API).framework(TestFramework.KARATE).build());

            assertEquals(1, cases.size());
            assertTrue(cases.get(0).getTestContent().contains("Feature:"));
        }
    }

    @Nested
    @DisplayName("Gözlenen sözleşme case'i")
    class ObservedContractCase {

        /**
         * Ölçümde LLM çıktısı çoğu zaman koşuyor ama DÜŞÜYORDU; deterministik case
         * yalnızca LLM bozuksa devreye giren bir yedek olduğu için hiç tetiklenmiyor
         * ve geçen test kalmıyordu. Artık LLM çıktısından BAĞIMSIZ olarak ekleniyor.
         */
        @Test
        @DisplayName("Gözlem bağlamı varsa deterministik case ayrıca eklenir")
        void observedContextAddsDeterministicCase() {
            var cases = generator.generate(request()
                    .rawPayload("curl -X GET 'http://localhost:8080/api/pets'")
                    .payloadType("CAPTURED")
                    // Deterministik case yalnızca TÜRETİLMİŞ GERÇEKLER bloğu varsa üretilir
                    // (bkz. AssertionCompiler.fromPromptFacts) — ham gözlem metni yetmez.
                    .additionalContext("""
                            ## OBSERVED RESPONSE (canlı koşumdan yakalandı)
                            İstek        : GET http://localhost:8080/api/pets
                            Gözlenen Status: 200

                            ## OBSERVED FACTS (gerçek yanıttan türetildi — hepsi doğrulanmış)
                            status: 200
                            responseTime < 3000 ms
                            """)
                    .build());

            assertTrue(cases.size() >= 2,
                    "LLM case'i + deterministik case beklenirken " + cases.size() + " üretildi");
            assertTrue(cases.stream().anyMatch(com.testgen.model.GeneratedTestCase::isDeterministic),
                    "Deterministik case eklenmemiş");
        }

        @Test
        @DisplayName("Gözlem bağlamı yoksa deterministik case eklenmez")
        void withoutObservedContextNoDeterministicCase() {
            var cases = generator.generate(request()
                    .rawPayload("curl -X GET 'http://x/api'").payloadType("CAPTURED").build());

            assertFalse(cases.stream().anyMatch(com.testgen.model.GeneratedTestCase::isDeterministic),
                    "Gözlem yokken deterministik case uydurulmuş");
        }
    }
}
