package com.testgen.generator;

import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestGenerationRequest;
import com.testgen.runner.GeneratedJavaTestProjectService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Uçtan uca koşumda gözlemlenen ÜRETİM HATALARININ regresyon testleri.
 *
 * Her test, gerçek bir koşumda LLM'in ürettiği ve testleri toptan düşüren
 * bir içerikten türetilmiştir — varsayımsal örnek yoktur.
 */
class GeneratedTestRepairTest {

    // ─────────────────────────────────────────────────────────
    // Karate: adsız header adımı (feature'daki TÜM senaryoları düşürüyordu)
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Adsız header adımı çoğul map biçimine çevrilir")
    void namelessHeaderStepBecomesHeadersMap() {
        String feature = """
                Feature: API Testleri

                  Background:
                    * def baseUrl = 'http://localhost:8080'
                    * header = { Content-Type: 'application/json' }

                  Scenario: Saglik kontrolu
                    Given url baseUrl + '/health'
                    When method get
                    Then status 200
                """;

        String repaired = CodeCleaner.repairFeatureSyntax(feature);

        assertTrue(repaired.contains("* headers { Content-Type: 'application/json' }"),
                "adsız header, map alan çoğul biçime çevrilmeli:\n" + repaired);
        assertFalse(repaired.contains("* header ="),
                "geçersiz adsız header adımı kalmamalı:\n" + repaired);
    }

    @Test
    @DisplayName("Adsız param/cookie adımları da çoğul biçime çevrilir")
    void namelessParamAndCookieStepsAreRepaired() {
        String repaired = CodeCleaner.repairFeatureSyntax("""
                Feature: F
                  Scenario: S
                    * param = { q: 'x' }
                    * cookie = { sid: '1' }
                    Then status 200
                """);

        assertTrue(repaired.contains("* params { q: 'x' }"), repaired);
        assertTrue(repaired.contains("* cookies { sid: '1' }"), repaired);
    }

    @Test
    @DisplayName("Onarılamayan adsız header adımı düşürülür, feature koşulabilir kalır")
    void unrepairableNamelessHeaderIsDropped() {
        String repaired = CodeCleaner.repairFeatureSyntax("""
                Feature: F
                  Background:
                    * header = 'application/json'
                  Scenario: S
                    Then status 200
                """);

        assertFalse(repaired.contains("header ="),
                "map olmayan adsız header onarılamaz, satır atılmalı:\n" + repaired);
        assertTrue(repaired.contains("Then status 200"), "geri kalan adımlar korunmalı");
    }

    @Test
    @DisplayName("Adı olan header adımına dokunulmaz")
    void namedHeaderStepIsLeftAlone() {
        String repaired = CodeCleaner.repairFeatureSyntax("""
                Feature: F
                  Scenario: S
                    * header Accept = 'application/json'
                    Then status 200
                """);

        assertTrue(repaired.contains("* header Accept = 'application/json'"), repaired);
    }

    // ─────────────────────────────────────────────────────────
    // Doğrulayıcı: parse'ın göremediği adım hatası
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Doğrulayıcı, step-definition'a eşleşmeyen adımı yakalar")
    void validatorCatchesUnmatchableStep() {
        String error = GeneratedTestValidator.findUnmatchableStep("""
                Feature: F
                  Background:
                    * header = { Accept: 'application/json' }
                  Scenario: S
                    Then status 200
                """);

        assertNotNull(error, "adsız header adımı yakalanmalıydı");
        assertTrue(error.contains("step-definition"), error);
    }

    @Test
    @DisplayName("Geçerli feature'da yanlış pozitif üretmez")
    void validatorAcceptsValidSteps() {
        assertNull(GeneratedTestValidator.findUnmatchableStep("""
                Feature: F
                  Background:
                    * def baseUrl = 'http://localhost:8080'
                    * headers { Accept: 'application/json' }
                  Scenario: S
                    Given url baseUrl
                    And path '/health'
                    When method get
                    Then status 200
                    And match response.status == '#notnull'
                """));
    }

    // ─────────────────────────────────────────────────────────
    // REST Assured: uydurma semboller (sınıfın derlenmesini engelliyordu)
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Var olmayan Matchers sınıfı hamcrest ile değiştirilir")
    void hallucinatedMatchersImportIsRewritten() {
        String repaired = CodeCleaner.repairRestAssuredHallucinations(
                "import static io.restassured.matcher.Matchers.hasStatusCode;");

        assertEquals("import static org.hamcrest.Matchers.hasStatusCode;", repaired);
    }

    @Test
    @DisplayName("Var olmayan timeLessThan çağrısı gerçek API'ye çevrilir")
    void hallucinatedTimeLessThanIsRewritten() {
        String repaired = CodeCleaner.repairRestAssuredHallucinations(
                "given().when().get(\"/x\").then().timeLessThan(10000L);");

        assertTrue(repaired.contains(".time(org.hamcrest.Matchers.lessThan(10000L))"), repaired);
        assertFalse(repaired.contains("timeLessThan"), repaired);
    }

    // ─────────────────────────────────────────────────────────
    // Dosya adı / public sınıf adı tutarlılığı
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Public sınıf adı dosya adıyla hizalanır")
    void publicClassNameIsAlignedWithFileName() {
        String aligned = GeneratedJavaTestProjectService.alignPublicClassName(
                "FixedHealthTest.java",
                "package com.testgen.generated;\npublic class OldHealthTest {\n}\n");

        assertTrue(aligned.contains("public class FixedHealthTest"), aligned);
        assertFalse(aligned.contains("OldHealthTest"), aligned);
    }

    @Test
    @DisplayName("Ad zaten uyuyorsa içerik değişmez")
    void matchingClassNameIsUntouched() {
        String content = "package com.testgen.generated;\npublic class HealthTest {\n}\n";
        assertEquals(content,
                GeneratedJavaTestProjectService.alignPublicClassName("HealthTest.java", content));
    }

    // ─────────────────────────────────────────────────────────
    // maxCases: case sayısı prompt'la değil döngüde sınırlanır
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("İstekteki maxCases her zaman kazanır")
    void requestMaxCasesWins() {
        assertEquals(5, new GenerationLimit(20).resolve(
                TestGenerationRequest.builder().maxCases(5).build(), 37));
    }

    @Test
    @DisplayName("İstek boşsa yapılandırma varsayılanı uygulanır")
    void configuredDefaultAppliesWhenRequestOmitsIt() {
        assertEquals(8, new GenerationLimit(8).resolve(
                TestGenerationRequest.builder().build(), 37));
    }

    @Test
    @DisplayName("Hiçbir sınır yoksa endpoint sayısı kadar üretilir")
    void noLimitFallsBackToEndpointCount() {
        assertEquals(37, new GenerationLimit(0).resolve(
                TestGenerationRequest.builder().build(), 37));
        assertEquals(1, new GenerationLimit(0).resolve(
                TestGenerationRequest.builder().build(), 0));
    }

    // ─────────────────────────────────────────────────────────
    // LLM kod yerine düzyazı döndürdüğünde
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Kod yerine açıklama metni test kodu sayılmaz")
    void proseIsNotRecognisedAsJavaTest() {
        assertFalse(CodeCleaner.looksRunnableJavaTest("""
                package com.testgen.generated;

                public class Getapi_v1_tests_healthTest {
                    Bu sorunun cevabi, API test data generasyonu icin gereken adimlar
                    ve kullanilan araclar hakkinda bilgi vermektedir.
                }
                """), "icinde @Test metodu olmayan icerik test kodu degildir");
    }

    @Test
    @DisplayName("Gerçek test sınıfı tanınır")
    void realTestClassIsRecognised() {
        assertTrue(CodeCleaner.looksRunnableJavaTest("""
                package com.testgen.generated;
                import org.junit.jupiter.api.Test;

                public class HealthTest {
                    @Test
                    public void smoke() {
                        given().when().get("/health").then().statusCode(200);
                    }
                }
                """));
    }

    @Test
    @DisplayName("Eksik TimeUnit import'u tamamlanır")
    void missingTimeUnitImportIsAdded() {
        String normalized = CodeCleaner.normalizeRestAssuredTest("""
                package com.testgen.generated;
                import org.junit.jupiter.api.Test;

                public class SuitesTest {
                    @Test
                    public void smoke() {
                        long ms = TimeUnit.SECONDS.toMillis(2);
                    }
                }
                """, "SuitesTest");

        assertTrue(normalized.contains("import java.util.concurrent.TimeUnit;"), normalized);
    }

    @Test
    @DisplayName("Selenium'da maxCases uygulanır ve gözlem testi korunur")
    void seleniumHonoursMaxCasesAndKeepsObservedTest() {
        List<GeneratedTestCase> produced = new ArrayList<>();
        for (String name : List.of("DashboardTest", "NewTestTest", "BoundaryTestTest",
                "SecurityTestTest", "UsabilityTestTest", "PerformanceTestTest")) {
            produced.add(GeneratedTestCase.builder().testName(name).build());
        }
        produced.add(GeneratedTestCase.builder()
                .testName(SeleniumTestGenerator.OBSERVED_SMOKE_TEST_NAME).build());

        List<GeneratedTestCase> kept = SeleniumTestGenerator.capForTest(
                produced, TestGenerationRequest.builder().maxCases(5).build(),
                new GenerationLimit(0));

        assertEquals(5, kept.size(), "maxCases=5 uygulanmali");
        assertTrue(kept.stream().anyMatch(
                        tc -> SeleniumTestGenerator.OBSERVED_SMOKE_TEST_NAME.equals(tc.getTestName())),
                "deterministik gozlem testi elenmemeli");
    }

    // ─────────────────────────────────────────────────────────
    // Gözlemden deterministik API testi (LLM'siz — geçmesi garanti)
    // ─────────────────────────────────────────────────────────

    private static final String OBSERVED_CONTEXT = """
            ## OBSERVED API (parametresiz GET endpoint'leri canli problandi)
            Base URL: http://localhost:8080
            - GET /api/v1/tests/health → 200 | body: {"status":"UP"}
            - GET /api/v1/suites → 200 | body: []
            KURAL: gozlenen degerler kontratin gercegidir.
            """;

    @Test
    @DisplayName("Gözlemden LLM'siz Karate feature üretilir")
    void buildsDeterministicKarateFeatureFromObservation() {
        var tc = ObservedApiTestBuilder.buildKarateCase(OBSERVED_CONTEXT).orElseThrow();

        assertEquals("ObservedApiContractTest", tc.getTestName());
        String content = tc.getTestContent();
        assertTrue(content.contains("* def baseUrl = 'http://localhost:8080'"), content);
        assertTrue(content.contains("/api/v1/tests/health"), content);
        assertTrue(content.contains("Then status 200"), content);
        assertNull(GeneratedTestValidator.findUnmatchableStep(content),
                "deterministik feature gecersiz adim icermemeli");
    }

    @Test
    @DisplayName("Gözlemden LLM'siz REST Assured sınıfı üretilir")
    void buildsDeterministicRestAssuredClassFromObservation() {
        var tc = ObservedApiTestBuilder.buildRestAssuredCase(OBSERVED_CONTEXT).orElseThrow();

        String content = tc.getTestContent();
        assertTrue(CodeCleaner.looksRunnableJavaTest(content), content);
        assertTrue(content.contains("RestAssured.baseURI = \"http://localhost:8080\""), content);
        assertTrue(content.contains(".statusCode(200)"), content);
    }

    @Test
    @DisplayName("Gözlem yoksa deterministik test üretilmez")
    void noObservationMeansNoDeterministicTest() {
        assertTrue(ObservedApiTestBuilder.buildKarateCase("ajan analizi metni").isEmpty());
        assertTrue(ObservedApiTestBuilder.buildRestAssuredCase(null).isEmpty());
    }

    // ─────────────────────────────────────────────────────────
    // Prompt bağlam bütçesi (performans)
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Uzun bağlam kısaltılır ama GÖZLEM bölümü korunur")
    void contextIsBoundedButObservationSurvives() {
        String context = "AJAN ANALIZI ".repeat(3000) + "\n" + OBSERVED_CONTEXT;

        String bounded = com.testgen.llm.PromptTemplates.boundContext(context);

        assertTrue(bounded.length() < context.length() / 4, "baglam belirgin sekilde kisalmali");
        assertTrue(bounded.contains("/api/v1/tests/health"), "gozlem verisi korunmali");
        assertTrue(bounded.contains("Base URL: http://localhost:8080"), "gozlem verisi korunmali");
    }

    // ─────────────────────────────────────────────────────────
    // Gözlem gerçekleri → zengin deterministik kontrat testi
    // ─────────────────────────────────────────────────────────

    /** ObservationService'in ürettiği biçim: endpoint satırı + altında girintili gerçekler. */
    private static final String OBSERVED_WITH_FACTS = """
            ## OBSERVED API (parametresiz GET endpoint'leri canli problandi)
            Base URL: http://localhost:8080
            - GET /api/v1/tests/health → 200 | body: {"status":"UP"}
                status: 200
                header Content-Type: application/json
                $.status : #string
            - GET /api/v1/suites → 200 | body: []
                status: 200
                $.items : array[0]
            KURAL: gozlenen degerler kontratin gercegidir.
            """;

    @Test
    @DisplayName("Gözlem gerçekleri Karate adımlarına derlenir — status'ten fazlası doğrulanır")
    void observedFactsCompileIntoRichKarateSteps() {
        var tc = ObservedApiTestBuilder.buildKarateCase(OBSERVED_WITH_FACTS).orElseThrow();
        String content = tc.getTestContent();

        assertTrue(content.contains("Then status 200"), content);
        assertTrue(content.contains("And match header Content-Type contains 'application/json'"), content);
        assertTrue(content.contains("And match response.status == '#string'"), content);
        assertTrue(content.contains("And match response.items == '#[0]'"), content);
        assertNull(GeneratedTestValidator.findUnmatchableStep(content),
                "uretilen adimlar gecerli olmali:\n" + content);
    }

    @Test
    @DisplayName("Gözlem gerçekleri REST Assured ifadelerine derlenir")
    void observedFactsCompileIntoRichRestAssured() {
        var tc = ObservedApiTestBuilder.buildRestAssuredCase(OBSERVED_WITH_FACTS).orElseThrow();
        String content = tc.getTestContent();

        assertTrue(CodeCleaner.looksRunnableJavaTest(content), content);
        assertTrue(content.contains(".statusCode(200)"), content);
        assertTrue(content.contains(".body(\"status\", instanceOf(String.class))"), content);
        assertFalse(content.contains("timeLessThan"), "uydurma metot uretilmemeli");
    }

    @Test
    @DisplayName("Gerçek yoksa eski davranış korunur — yalnızca status")
    void withoutFactsFallsBackToStatusOnly() {
        String legacy = """
                ## OBSERVED API
                Base URL: http://localhost:8080
                - GET /api/v1/tests/health → 200 | body: {"status":"UP"}
                """;

        String content = ObservedApiTestBuilder.buildKarateCase(legacy).orElseThrow().getTestContent();

        assertTrue(content.contains("Then status 200"), content);
        assertTrue(content.contains("And match response != null"), content);
    }

    @Test
    @DisplayName("Deterministik REST Assured sınıfı GERÇEKTEN derlenir")
    void observedRestAssuredClassActuallyCompiles() {
        // NEDEN BU TEST: Önceki sürüm "@Test metodu var mı" diye bakıyordu ve eksik
        // hamcrest import'unu kaçırdı. Sınıf derlenmeyince TestContentGate LLM onarımını
        // çağırdı, o da uydurma import ekleyip içeriği bozdu. Tek güvenilir kapı derleyici.
        var tc = ObservedApiTestBuilder.buildRestAssuredCase(OBSERVED_WITH_FACTS).orElseThrow();

        var result = new GeneratedTestValidator().validate(
                com.testgen.model.TestFramework.REST_ASSURED, tc.getFileName(), tc.getTestContent());

        assertFalse(result.isInvalid(),
                "uretilen sinif derlenmeli, hata:\n" + result.error() + "\n\n" + tc.getTestContent());
        assertTrue(tc.getTestContent().contains("import static org.hamcrest.Matchers.*;"),
                "matcher import'u bulunmali");
    }

    @Test
    @DisplayName("Kök dizi boyutu geçerli GPath üretir")
    void rootArraySizeUsesValidGPath() {
        var stmts = com.testgen.runner.AssertionCompiler.toRestAssuredStatements(List.of(
                com.testgen.runner.HttpAssertion.of(
                        com.testgen.runner.HttpAssertion.Type.JSON_ARRAY_SIZE, "$",
                        com.testgen.runner.HttpAssertion.Operator.EQUALS, "0", "kok dizi")));

        // ".body(\"$.size()\", ...)" gecersizdir
        assertEquals(List.of(".body(\"size()\", equalTo(0))"), stmts);
    }

    /** generate-from-response akışının bağlam biçimi (Swagger yok, tek yakalanmış istek). */
    private static final String CAPTURED_CONTEXT = """
            ## OBSERVED RESPONSE (canli kosumdan yakalandi)
            İstek        : GET http://localhost:8099/api/v1/suites
            Gözlenen Status: 200
            Content-Type : application/json

            ## OBSERVED FACTS (gerçek yanıttan türetildi)
            status: 200
            header Content-Type: application/json
            $ : array[0]
            KURAL: listede olmayan icin assertion yazma.
            """;

    @Test
    @DisplayName("Yakalanan istek biçiminden de deterministik REST Assured üretilir")
    void capturedRequestFormatProducesDeterministicCase() {
        // Canli dogrulamada yakalanan bosluk: generate-from-response + REST_ASSURED yolunda
        // "## OBSERVED API" yoktu, bu yuzden hic deterministik case uretilmiyordu.
        var tc = ObservedApiTestBuilder.buildRestAssuredCase(CAPTURED_CONTEXT).orElseThrow();
        String content = tc.getTestContent();

        assertTrue(content.contains("RestAssured.baseURI = \"http://localhost:8099\""), content);
        assertTrue(content.contains(".get(\"/api/v1/suites\")"), content);
        assertTrue(content.contains(".statusCode(200)"), content);
        assertTrue(content.contains(".body(\"size()\", equalTo(0))"), content);

        var result = new GeneratedTestValidator().validate(
                com.testgen.model.TestFramework.REST_ASSURED, tc.getFileName(), content);
        assertFalse(result.isInvalid(), "derlenmeli, hata:\n" + result.error() + "\n" + content);
    }

    @Test
    @DisplayName("Yakalanan istek biçiminden Karate feature'ı da üretilir")
    void capturedRequestFormatProducesKarateCase() {
        String content = ObservedApiTestBuilder.buildKarateCase(CAPTURED_CONTEXT)
                .orElseThrow().getTestContent();

        assertTrue(content.contains("Then status 200"), content);
        assertTrue(content.contains("match response == '#[0]'"), content);
        assertNull(GeneratedTestValidator.findUnmatchableStep(content), content);
    }

    // ─────────────────────────────────────────────────────────
    // Deterministik case'ler LLM onarımından muaf
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Gözlemden üretilen case'ler deterministik olarak işaretlenir")
    void observedCasesAreMarkedDeterministic() {
        assertTrue(ObservedApiTestBuilder.buildKarateCase(OBSERVED_WITH_FACTS)
                .orElseThrow().isDeterministic(), "Karate kontrat testi isaretlenmeli");
        assertTrue(ObservedApiTestBuilder.buildRestAssuredCase(OBSERVED_WITH_FACTS)
                .orElseThrow().isDeterministic(), "REST Assured kontrat testi isaretlenmeli");
        assertTrue(ObservedApiTestBuilder.buildRestAssuredCase(CAPTURED_CONTEXT)
                .orElseThrow().isDeterministic(), "yakalanan istek bicimi de isaretlenmeli");
    }

    @Test
    @DisplayName("LLM üretimi case'ler deterministik işaretlenmez")
    void llmCasesAreNotMarkedDeterministic() {
        assertFalse(com.testgen.model.GeneratedTestCase.builder()
                .testName("LlmTest").build().isDeterministic(),
                "varsayilan false olmali — yalnizca gozlemden uretilenler muaf");
    }
}
