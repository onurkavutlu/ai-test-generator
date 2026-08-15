package com.testgen.generator;

import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gözlemlenen API davranışından LLM KULLANMADAN test üretir.
 *
 * NEDEN VAR — ölçülen gerçek: Üç uçtan uca koşumda LLM'in yazdığı 30'dan fazla
 * testin tamamı düştü; geçen tek test her seferinde Selenium tarafındaki
 * {@code ObservedSmokeTest} oldu — canlı sayfadan okunan gerçek değerlerle,
 * hiç tahmin yapılmadan üretilen test. Bu sınıf aynı deseni API tarafına taşır.
 *
 * Ayrıca PERFORMANS düzeltmesidir: başarısız testler self-healing tetikliyor ve
 * ölçümde LLM zamanının ~%50'si oraya gidiyordu. Geçen bir test hiç onarım
 * istemez; her istekte en az bir geçen testin bulunması bu döngüyü kırar.
 *
 * Hiçbir değer tahmin edilmez: yalnızca {@code ## OBSERVED API} bölümünde canlı
 * problanmış olan yol ve status kullanılır. Gözlem yoksa test de üretilmez.
 */
@Slf4j
final class ObservedApiTestBuilder {

    /** ObservationService'in yazdığı satır: "- GET /api/v1/tests → 200 | body: ..." */
    private static final Pattern OBSERVED_LINE =
            Pattern.compile("^-\\s+GET\\s+(\\S+)\\s+→\\s+(\\d{3})\\b");
    private static final Pattern BASE_URL_LINE =
            Pattern.compile("(?m)^Base URL:\\s*(\\S+)");
    private static final String OBSERVED_API_SECTION = "## OBSERVED API";

    /**
     * Tek yakalanmış istek biçimi: {@code generate-from-response} akışı Swagger yerine
     * "## OBSERVED RESPONSE" + "## OBSERVED FACTS" yazar. Bu biçim tanınmadığında
     * REST Assured tarafında hiç deterministik case üretilmiyordu (canlı doğrulamada yakalandı).
     */
    private static final Pattern CAPTURED_REQUEST_LINE =
            Pattern.compile("(?m)^İstek\\s*:\\s*(\\w+)\\s+(\\S+)");

    private ObservedApiTestBuilder() {}

    /**
     * Canlı problanmış tek bir endpoint gözlemi.
     *
     * @param facts ObservationService'in endpoint satırı altına girintili yazdığı
     *              türetilmiş gerçekler; boş olabilir (eski akış → yalnızca status).
     */
    private record Observation(String path, int status, List<com.testgen.runner.HttpAssertion> facts) {}

    static Optional<GeneratedTestCase> buildKarateCase(String context) {
        List<Observation> observations = parse(context);
        if (observations.isEmpty()) {
            return Optional.empty();
        }
        // Adres gözlemden okunamıyorsa case ÜRETİLMEZ — varsayılan adres uydurulmaz.
        Optional<String> resolvedBase = baseUrl(context);
        if (resolvedBase.isEmpty()) {
            return Optional.empty();
        }
        String baseUrl = resolvedBase.get();

        StringBuilder sb = new StringBuilder();
        sb.append("Feature: Gozlemlenen API kontrati (deterministik - LLM kullanilmadi)\n\n")
          .append("  # Bu feature canli problamadan uretildi. Her assertion, uretim aninda\n")
          .append("  # hedeften GERCEKTEN okunan degerdir; hicbir status tahmin edilmedi.\n\n")
          .append("  Background:\n")
          .append("    * def baseUrl = '").append(baseUrl).append("'\n\n");

        for (Observation o : observations) {
            sb.append("  Scenario: GET ").append(o.path()).append(" gozlemlenen ")
              .append(o.status()).append(" durumunu doner\n")
              .append("    Given url baseUrl + '").append(o.path()).append("'\n")
              .append("    When method get\n");

            List<String> steps = com.testgen.runner.AssertionCompiler.toKarateSteps(o.facts());
            if (steps.isEmpty()) {
                // Turetilmis gercek yoksa (eski akis) en azindan status dogrulanir
                steps = List.of("Then status " + o.status(), "And match response != null");
            }
            steps.forEach(step -> sb.append("    ").append(step).append('\n'));
            sb.append('\n');
        }

        log.info("Gözlemden deterministik Karate feature üretildi — {} endpoint: {}",
                observations.size(), observations.stream().map(Observation::path).toList());

        return Optional.of(GeneratedTestCase.builder()
                .testName("ObservedApiContractTest")
                .fileName("ObservedApiContractTest.feature")
                .testContent(sb.toString())
                .testSummary("[OBSERVED] Canlı problamadan deterministik üretildi — LLM kullanılmadı, "
                        + "tüm beklenen değerler üretim anında hedeften okundu.")
                .framework(TestFramework.KARATE)
                .deterministic(true)
                .build());
    }

    static Optional<GeneratedTestCase> buildRestAssuredCase(String context) {
        List<Observation> observations = parse(context);
        if (observations.isEmpty()) {
            return Optional.empty();
        }
        // Adres gözlemden okunamıyorsa case ÜRETİLMEZ — varsayılan adres uydurulmaz.
        Optional<String> resolvedBase = baseUrl(context);
        if (resolvedBase.isEmpty()) {
            return Optional.empty();
        }
        String baseUrl = resolvedBase.get();
        String className = "ObservedApiContractTest";

        StringBuilder sb = new StringBuilder();
        sb.append("package com.testgen.generated;\n\n")
          .append("import io.restassured.RestAssured;\n")
          .append("import org.junit.jupiter.api.BeforeEach;\n")
          .append("import org.junit.jupiter.api.Test;\n\n")
          .append("import static io.restassured.RestAssured.given;\n")
          // AssertionCompiler containsString/instanceOf/equalTo uretir; import'u OLMAZSA
          // sinif derlenmez ve TestContentGate LLM onarimini cagirip icerigi bozar.
          .append("import static org.hamcrest.Matchers.*;\n\n")
          .append("/**\n")
          .append(" * Canli problamadan DETERMINISTIK uretildi (LLM ciktisi kullanilmadi).\n")
          .append(" * Tum beklenen status degerleri uretim aninda hedeften gercek olarak okundu.\n")
          .append(" */\n")
          .append("public class ").append(className).append(" {\n\n")
          .append("    @BeforeEach\n")
          .append("    public void setUp() {\n")
          .append("        RestAssured.baseURI = \"").append(baseUrl).append("\";\n")
          .append("    }\n");

        int index = 0;
        for (Observation o : observations) {
            List<String> stmts = com.testgen.runner.AssertionCompiler.toRestAssuredStatements(o.facts());
            if (stmts.isEmpty()) {
                stmts = List.of(".statusCode(" + o.status() + ")");
            }
            sb.append("\n    @Test\n")
              .append("    public void observedContract").append(index++).append("() {\n")
              .append("        given()\n")
              .append("                .when().get(\"").append(o.path()).append("\")\n")
              .append("                .then()");
            stmts.forEach(st -> sb.append("\n                ").append(st));
            sb.append(";\n    }\n");
        }
        sb.append("}\n");

        log.info("Gözlemden deterministik REST Assured sınıfı üretildi — {} endpoint", observations.size());

        return Optional.of(GeneratedTestCase.builder()
                .testName(className)
                .fileName(className + ".java")
                .testContent(sb.toString())
                .testSummary("[OBSERVED] Canlı problamadan deterministik üretildi — LLM kullanılmadı, "
                        + "tüm beklenen değerler üretim anında hedeften okundu.")
                .framework(TestFramework.REST_ASSURED)
                .deterministic(true)
                .build());
    }

    private static List<Observation> parse(String context) {
        List<Observation> out = new ArrayList<>();
        if (context == null) {
            return out;
        }
        if (!context.contains(OBSERVED_API_SECTION)) {
            // Swagger gözlemi yok — tek yakalanmış istek biçimini dene
            return parseCapturedRequest(context);
        }
        String[] lines = context.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            Matcher m = OBSERVED_LINE.matcher(lines[i].trim());
            if (!m.find()) {
                continue;
            }
            // Endpoint satirinin ALTINDAKI girintili gercek satirlari bu endpoint'e aittir.
            StringBuilder factBlock = new StringBuilder("## OBSERVED FACTS\n");
            for (int j = i + 1; j < lines.length && lines[j].startsWith("    "); j++) {
                factBlock.append(lines[j].trim()).append('\n');
            }
            out.add(new Observation(m.group(1), Integer.parseInt(m.group(2)),
                    com.testgen.runner.AssertionCompiler.fromPromptFacts(factBlock.toString())));
        }
        return out;
    }

    /**
     * {@code generate-from-response} akışının bağlamını tek gözleme çevirir.
     * Yol, "İstek : GET http://host/path" satırından; doğrulamalar "## OBSERVED FACTS" bloğundan gelir.
     */
    private static List<Observation> parseCapturedRequest(String context) {
        List<Observation> out = new ArrayList<>();
        var facts = com.testgen.runner.AssertionCompiler.fromPromptFacts(context);
        if (facts.isEmpty()) {
            return out;
        }
        Matcher m = CAPTURED_REQUEST_LINE.matcher(context);
        if (!m.find()) {
            return out;
        }
        int status = facts.stream()
                .filter(a -> a.type() == com.testgen.runner.HttpAssertion.Type.STATUS)
                .findFirst().map(a -> Integer.parseInt(a.expected())).orElse(200);

        out.add(new Observation(pathOf(m.group(2)), status, facts));
        return out;
    }

    /** Tam URL'den yol kısmı: baseURI ayrı verildiği için istek yalnızca yolu kullanır. */
    private static String pathOf(String url) {
        try {
            String path = java.net.URI.create(url).getPath();
            return path == null || path.isBlank() ? "/" : path;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    /**
     * Base URL'i YALNIZCA gözlem bağlamından çıkarır; çıkaramazsa boş döner.
     *
     * <p>Önceden çıkaramadığında {@code http://localhost:8080} varsayıyordu. Bu, aynı
     * dosyanın ürettiği feature metnindeki "hiçbir status tahmin edilmedi" iddiasıyla
     * çelişiyordu: status tahmin edilmiyordu ama <b>hedef adres</b> tahmin ediliyordu.
     * Yanlış adrese kurulmuş bir sözleşme testi ya hep patlar ya da bambaşka bir
     * sistemi doğrular. Adres gözlemden okunamıyorsa case üretilmez.
     */
    private static Optional<String> baseUrl(String context) {
        Matcher m = BASE_URL_LINE.matcher(context);
        if (m.find()) {
            return Optional.of(stripTrailingSlash(m.group(1)));
        }
        Matcher captured = CAPTURED_REQUEST_LINE.matcher(context);
        if (captured.find()) {
            try {
                java.net.URI uri = java.net.URI.create(captured.group(2));
                if (uri.getScheme() != null && uri.getHost() != null) {
                    return Optional.of(uri.getScheme() + "://" + uri.getHost()
                            + (uri.getPort() > 0 ? ":" + uri.getPort() : ""));
                }
            } catch (IllegalArgumentException ignored) {
                // biçimsiz URL — adres uydurulmaz, boş dönülür
            }
        }
        return Optional.empty();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
