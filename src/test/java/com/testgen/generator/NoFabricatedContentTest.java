package com.testgen.generator;

import com.testgen.config.BadRequestException;
import com.testgen.llm.LlmService;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestType;
import com.testgen.parser.ApiCollectionParser;
import com.testgen.parser.GraphQLParser;
import com.testgen.parser.HarFileParser;
import com.testgen.parser.SoapXmlParser;
import com.testgen.runner.GeneratedJavaTestProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>Kurumsal kural: hiçbir koşulda uydurma içerik üretilmez.</b>
 *
 * <p>Referans davranış Postman'dir: Postman bir isteği gönderemediğinde sana sahte bir
 * yanıt göstermez, hatayı gösterir. Bu araç da öyle olmalı — gözlemlemediği bir değeri
 * asla "gözlenmiş" gibi sunmamalı.
 *
 * <p>Bu sınıf, denetimde bulunan ve kapatılan <b>uydurma kaynaklarının</b> geri
 * gelmediğini kilitler. Her biri gerçekten kodda vardı:
 * <ol>
 *   <li>Gözlem yokken {@code status 200} + {@code http://localhost:8080} + gözlenmemiş
 *       10sn SLA ile "garanti geçen" deterministik case</li>
 *   <li>{@code applicationUrl} boşken {@code http://localhost:3000} varsayan Selenium üretimi</li>
 *   <li>Adres çıkarılamadığında {@code http://localhost:8080} varsayan gözlem sözleşmesi</li>
 * </ol>
 *
 * <p>Ortak sonuç hep aynıydı: kullanıcının hiç bahsetmediği bir hedefe kurulmuş,
 * yeşil yanan ama hiçbir şey kanıtlamayan test.
 */
class NoFabricatedContentTest {

    @TempDir
    Path tempDir;

    private LlmService llmService;
    private KarateTestGenerator karateGenerator;
    private SeleniumTestGenerator seleniumGenerator;

    private static final String VALID_FEATURE = """
            Feature: X
              Background:
                * url 'http://localhost:8080'
              Scenario: [SMOKE][P0_BLOCKER][EP] Gecerli
                When method GET
                Then status 200
            """;

    @BeforeEach
    void setUp() {
        llmService = mock(LlmService.class);
        when(llmService.generateTestCase(anyString())).thenReturn(VALID_FEATURE);
        when(llmService.generateTestCase(anyString(), anyString())).thenReturn(VALID_FEATURE);
        when(llmService.generateFromRawPayload(anyString(), anyString(), anyString()))
                .thenReturn(VALID_FEATURE);

        karateGenerator = new KarateTestGenerator(llmService,
                mock(ApiCollectionParser.class), mock(HarFileParser.class),
                mock(GraphQLParser.class), mock(SoapXmlParser.class), new GenerationLimit());
        ReflectionTestUtils.setField(karateGenerator, "outputPath", tempDir.toString());

        // SeleniumTestGenerator çıktı yolunu GeneratedJavaTestProjectService üzerinden
        // yazar; ayrı bir outputPath alanı yoktur.
        seleniumGenerator = new SeleniumTestGenerator(llmService,
                mock(GeneratedJavaTestProjectService.class), new GenerationLimit());
    }

    @Nested
    @DisplayName("Deterministik gözlem case'i — değer uydurmaz")
    class DeterministicCase {

        /**
         * Eski davranış: boş bağlamda bile {@code Then status 200} içeren, hedefi
         * {@code http://localhost:8080} olan bir feature üretiliyordu. Bu test o
         * davranışın geri gelmediğini kilitler.
         */
        @Test
        @DisplayName("Boş bağlamda case üretilmez, varsayılan status/URL uydurulmaz")
        void emptyContextProducesNothing() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> KarateTestGenerator.buildDeterministicCapturedFeature(null));

            assertTrue(ex.getMessage().contains("uydurulmaz")
                            || ex.getMessage().contains("üretilemez"),
                    ex.getMessage());
        }

        @Test
        @DisplayName("İstek satırı yoksa case üretilmez")
        void missingRequestLineProducesNothing() {
            String ctx = """
                    ## OBSERVED FACTS (gerçek yanıttan türetildi)
                    status: 200
                    """;

            assertThrows(IllegalArgumentException.class,
                    () -> KarateTestGenerator.buildDeterministicCapturedFeature(ctx));
        }

        /**
         * Hiçbir gözlem yoksa (ne türetilmiş gerçek ne de ölçülmüş status) case üretilmez.
         * Eski kod burada sabit {@code status 200} ve uydurma bir SLA ekliyordu.
         */
        @Test
        @DisplayName("Hiç gözlenmiş değer yoksa case üretilmez")
        void noObservedValueProducesNothing() {
            String ctx = "İstek        : GET http://api.example.com/v1/pets\n";

            assertThrows(IllegalArgumentException.class,
                    () -> KarateTestGenerator.buildDeterministicCapturedFeature(ctx));
        }

        /**
         * Türetilmiş gerçek yoksa bile "Gözlenen Status" CANLI ÖLÇÜLMÜŞ bir değerdir;
         * onu kullanmak uydurma değildir. Kural "gözlem yoksa üretme"dir, "türetme
         * yoksa üretme" değil — aksi hâlde gerçek veri çöpe atılırdı.
         */
        @Test
        @DisplayName("Türetme yoksa ölçülmüş status kullanılır, SLA uydurulmaz")
        void observedStatusAloneIsEnoughAndNoSlaInvented() {
            String ctx = """
                    İstek        : DELETE http://api.example.com/v1/pets/7
                    Gözlenen Status: 404
                    """;

            String feature = KarateTestGenerator.buildDeterministicCapturedFeature(ctx);

            assertTrue(feature.contains("Then status 404"), feature);
            assertTrue(feature.contains("method DELETE"), feature);
            assertFalse(feature.contains("responseTime"),
                    "Ölçülmemiş SLA eklenmiş: " + feature);
        }

        @Test
        @DisplayName("Gözlem tamsa yalnızca gözlenen değerlerle case üretilir")
        void completeObservationProducesObservedOnlyCase() {
            String ctx = """
                    ## OBSERVED RESPONSE (canlı koşumdan yakalandı)
                    İstek        : GET http://api.example.com/v1/pets
                    Gözlenen Status: 204

                    ## OBSERVED FACTS (gerçek yanıttan türetildi — hepsi doğrulanmış)
                    status: 204
                    """;

            String feature = KarateTestGenerator.buildDeterministicCapturedFeature(ctx);

            assertTrue(feature.contains("http://api.example.com/v1/pets"),
                    "Gözlenen adres kullanılmalı: " + feature);
            assertTrue(feature.contains("204"), "Gözlenen status kullanılmalı: " + feature);
            // Uydurma izleri kesinlikle olmamalı
            assertFalse(feature.contains("localhost:8080"),
                    "Uydurma varsayılan adres sızmış: " + feature);
            assertFalse(feature.contains("responseTime < 10000"),
                    "Gözlenmemiş SLA sızmış: " + feature);
        }

        /**
         * Üretim akışında eksik gözlem PATLAMAMALI; case sessizce atlanmalı ve LLM
         * tarafındaki normal üretim sürmeli.
         */
        @Test
        @DisplayName("Üretim akışında eksik gözlem case'i atlar, akışı kırmaz")
        void incompleteObservationSkipsCaseWithoutBreakingFlow() {
            var cases = karateGenerator.generate(TestGenerationRequest.builder()
                    .testType(TestType.BACKEND_API)
                    .framework(TestFramework.KARATE)
                    .userStory("hikaye")
                    .rawPayload("curl -X GET 'http://api.example.com/v1/pets'")
                    .payloadType("CAPTURED")
                    // OBSERVED FACTS var ama İstek satırı YOK → deterministik case üretilemez
                    .additionalContext("""
                            ## OBSERVED FACTS (gerçek yanıttan türetildi)
                            status: 200
                            """)
                    .build());

            assertFalse(cases.isEmpty(), "LLM case'i yine üretilmeliydi");
            assertFalse(cases.stream().anyMatch(com.testgen.model.GeneratedTestCase::isDeterministic),
                    "Eksik gözleme rağmen deterministik case uydurulmuş");
        }
    }

    @Nested
    @DisplayName("Selenium — hedef adres uydurmaz")
    class SeleniumTarget {

        /**
         * Eski davranış: applicationUrl boşken http://localhost:3000 varsayılıyordu.
         * Üretilen test kullanıcının hiç bahsetmediği bir adrese bağlanmaya çalışıp
         * patlıyor, hata da üründeymiş gibi görünüyordu.
         */
        @Test
        @DisplayName("applicationUrl yoksa üretim reddedilir, localhost:3000 varsayılmaz")
        void missingApplicationUrlIsRejected() {
            var request = TestGenerationRequest.builder()
                    .testType(TestType.FRONTEND_WEB)
                    .framework(TestFramework.SELENIUM)
                    .userStory("Kullanıcı giriş yapabilmeli")
                    .build();

            var ex = assertThrows(BadRequestException.class,
                    () -> seleniumGenerator.generate(request));

            assertTrue(ex.getMessage().contains("applicationUrl"), ex.getMessage());
            assertTrue(ex.getMessage().contains("uydurulmaz"), ex.getMessage());
        }

        @Test
        @DisplayName("Boş metin de eksik sayılır")
        void blankApplicationUrlIsRejected() {
            var request = TestGenerationRequest.builder()
                    .testType(TestType.FRONTEND_WEB)
                    .framework(TestFramework.SELENIUM)
                    .applicationUrl("   ")
                    .build();

            assertThrows(BadRequestException.class, () -> seleniumGenerator.generate(request));
        }
    }
}
