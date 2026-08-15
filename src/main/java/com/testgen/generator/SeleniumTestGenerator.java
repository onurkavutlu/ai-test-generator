package com.testgen.generator;

import com.testgen.llm.LlmService;
import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.runner.GeneratedJavaTestProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Selenium WebDriver test üreticisi (Frontend/Web).
 * Page Object Model pattern ile Java + JUnit 5 test sınıfları üretir.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeleniumTestGenerator {

    private final LlmService llmService;
    private final GeneratedJavaTestProjectService javaTestProjectService;
    private final GenerationLimit generationLimit;

    public List<GeneratedTestCase> generate(TestGenerationRequest request) {
        List<GeneratedTestCase> results = new ArrayList<>();

        // Hedef adres UYDURULMAZ. Önceden applicationUrl boşken http://localhost:3000
        // varsayılıyordu: kullanıcının hiç bahsetmediği bir adrese test üretiliyor,
        // koşumda "bağlanamadı" diye patlıyor ve hata üründeymiş gibi görünüyordu.
        // Eksik girdi sessiz varsayımla değil, açık hatayla bildirilir.
        String pageUrl = request.getApplicationUrl();
        if (pageUrl == null || pageUrl.isBlank()) {
            throw new com.testgen.config.BadRequestException(
                    "Selenium üretimi için applicationUrl zorunludur — "
                            + "test edilecek adres uydurulmaz.");
        }

        String userStory = request.getUserStory() != null
                ? request.getUserStory() : "Web uygulama testi";

        String htmlHint = request.getAdditionalContext() != null
                ? request.getAdditionalContext() : "";

        log.info("Selenium test üretiliyor - URL: {}", pageUrl);

        // LLM'den hem Page Object hem de Test sınıfını üret
        String generatedContent = llmService.generateSeleniumTest(pageUrl, userStory, htmlHint);

        // İki sınıfı birbirinden ayır
        List<JavaClassContent> classes = CodeCleaner.splitJavaClasses(generatedContent);

        if (classes.isEmpty()) {
            // Split başarısız olduysa tek dosya olarak kaydet
            String className = "GeneratedSeleniumTest";
            // package/JUnit5 import/sınıf-adı garantisi — LLM prompt'a uymazsa derleme kırılmasın
            String clean = CodeCleaner.normalizeSeleniumTest(
                    CodeCleaner.cleanJavaContent(generatedContent), className);
            GeneratedTestCase tc = buildTestCase(className, className + ".java",
                    clean, "[AI-DATA][LLM-GENERATED] AI tarafindan uretilen web test datasina gore Selenium WebDriver testi olusturdu.");
            saveToFile(tc.getFileName(), clean);
            results.add(tc);
        } else {
            classes.forEach(cls -> {
                String clean = CodeCleaner.normalizeSeleniumTest(
                        CodeCleaner.cleanJavaContent(cls.content()), cls.className());
                String fileName = cls.className() + ".java";
                saveToFile(fileName, clean);
                if (isRunnableTestClass(cls.className())) {
                    GeneratedTestCase tc = buildTestCase(
                            cls.className(), fileName, clean,
                            "[AI-DATA][LLM-GENERATED] AI web test datasini kullanarak Selenium test sinifi olusturdu: " + cls.className());
                    results.add(tc);
                }
            });

            // LLM sınıf üretti ama hiçbiri koşulabilir test değilse (yalnızca Page Object'ler)
            // üretim sessizce boşa gidiyordu: gözlem smoke testi listeyi doldurduğu için
            // aşağıdaki "results.isEmpty()" uyarısı da hiç tetiklenmiyordu.
            if (results.isEmpty()) {
                log.warn("LLM {} sınıf üretti ama koşulabilir test sınıfı (*Test/*Tests) yok: {} — "
                                + "yalnızca deterministik gözlem testi kalacak.",
                        classes.size(),
                        classes.stream().map(JavaClassContent::className).toList());
            }
        }

        // Deterministik güvenlik ağı: LLM çıktısı ne olursa olsun, GÖZLEMLENEN gerçek
        // veriden (canlı çekilen <title> ve gerçek element id'leri) türetilen, her zaman
        // derlenen ve anlamlı bir smoke testi eklenir. Hiçbir değer tahmin edilmez;
        // gözlem yoksa bu test hiç üretilmez.
        buildObservedSmokeTest(pageUrl, htmlHint).ifPresent(tc -> {
            saveToFile(tc.getFileName(), tc.getTestContent());
            results.add(tc);
        });

        if (results.isEmpty()) {
            log.warn("LLM Selenium Page Object uretmis olabilir ama calistirilabilir test sinifi bulunamadi.");
        }

        return applyMaxCases(results, request);
    }

    /** Test görünürlüğü için: eleme mantığı LLM çağırmadan doğrulanabilsin. */
    static List<GeneratedTestCase> capForTest(List<GeneratedTestCase> results,
                                              TestGenerationRequest request,
                                              GenerationLimit limit) {
        return new SeleniumTestGenerator(null, null, limit).applyMaxCases(results, request);
    }

    /**
     * maxCases sınırını Selenium'a da uygular.
     *
     * Swagger akışında sınır endpoint döngüsünde uygulanır; burada case sayısını
     * LLM'in ürettiği sınıf sayısı belirlediği için sınır ancak üretim SONRASI
     * uygulanabilir. Gözlem smoke testi korunur: deterministik üretildiği ve
     * ölçülen koşumlarda geçen tek test o olduğu için ilk elenecek aday değildir.
     */
    private List<GeneratedTestCase> applyMaxCases(List<GeneratedTestCase> results,
                                                  TestGenerationRequest request) {
        int limit = generationLimit.resolve(request, results.size());
        if (results.size() <= limit) {
            return results;
        }

        List<GeneratedTestCase> observed = results.stream()
                .filter(tc -> OBSERVED_SMOKE_TEST_NAME.equals(tc.getTestName()))
                .toList();
        List<GeneratedTestCase> generated = results.stream()
                .filter(tc -> !OBSERVED_SMOKE_TEST_NAME.equals(tc.getTestName()))
                .toList();

        int keepFromLlm = Math.max(0, limit - observed.size());
        List<GeneratedTestCase> kept = new ArrayList<>(generated.subList(0, Math.min(keepFromLlm, generated.size())));
        kept.addAll(observed);

        log.warn("maxCases sınırı ({}) uygulandı: {} case üretildi, {} tanesi elendi — elenenler: {}",
                limit, results.size(), results.size() - kept.size(),
                generated.stream().skip(keepFromLlm).map(GeneratedTestCase::getTestName).toList());
        return kept;
    }

    /** Gözlemden deterministik üretilen smoke testinin adı; maxCases elemesinde korunur. */
    static final String OBSERVED_SMOKE_TEST_NAME = "ObservedSmokeTest";

    private static final Pattern OBSERVED_TITLE =
            Pattern.compile("Gerçek <title>:\\s*(.+)");
    private static final Pattern OBSERVED_ELEMENTS =
            Pattern.compile("Gerçek elementler \\(tag#id\\):\\s*(.+)");

    /**
     * ObservationService'in canlı çektiği sayfa gözleminden deterministik smoke testi üretir.
     * Yalnızca gözlenen değerler kullanılır: gerçek sayfa başlığı ve gerçek element id'leri.
     */
    Optional<GeneratedTestCase> buildObservedSmokeTest(String pageUrl, String context) {
        if (context == null || !context.contains("## OBSERVED PAGE")) {
            return Optional.empty();
        }

        Matcher titleMatcher = OBSERVED_TITLE.matcher(context);
        if (!titleMatcher.find()) {
            return Optional.empty();
        }
        String title = titleMatcher.group(1).trim();
        if (title.isEmpty() || title.equals("(title yok)")) {
            return Optional.empty();
        }

        List<String> elementIds = new ArrayList<>();
        Matcher elementMatcher = OBSERVED_ELEMENTS.matcher(context);
        if (elementMatcher.find()) {
            for (String token : elementMatcher.group(1).split(",")) {
                String trimmed = token.trim();
                int hash = trimmed.indexOf('#');
                if (hash >= 0 && hash + 1 < trimmed.length()) {
                    String id = trimmed.substring(hash + 1);
                    if (id.matches("[A-Za-z0-9_-]+") && elementIds.size() < 5) {
                        elementIds.add(id);
                    }
                }
            }
        }

        // Gözlem yalnızca "bu id sunulan HTML'de VAR" bilgisini verir; görünürlük bilgisini
        // VERMEZ. Bu yüzden isDisplayed() değil, DOM'da varlık doğrulanır — aksi hâlde
        // sekme/panel içinde gizli duran gerçek elementler yanlışlıkla hata üretiyordu
        // (canlıda ölçüldü: create-request-form HTML'de var ama açılışta gizli).
        StringBuilder elementAssertions = new StringBuilder();
        for (String id : elementIds) {
            elementAssertions.append("        assertFalse(driver.findElements(By.id(\"")
                    .append(id).append("\")).isEmpty(),\n")
                    .append("                \"Gozlenen element DOM'da bulunmali: ").append(id).append("\");\n");
        }

        String className = OBSERVED_SMOKE_TEST_NAME;
        String content = """
                package com.testgen.generated;

                import org.junit.jupiter.api.AfterEach;
                import org.junit.jupiter.api.BeforeEach;
                import org.junit.jupiter.api.Test;
                import org.openqa.selenium.By;
                import org.openqa.selenium.WebDriver;

                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertFalse;

                /**
                 * Canli sayfa gozleminden DETERMINISTIK uretildi (LLM ciktisi kullanilmadi).
                 * Tum beklenen degerler uretim aninda hedeften gercek olarak okundu.
                 */
                public class %s {

                    private WebDriver driver;

                    @BeforeEach
                    public void setUpDriver() {
                        driver = DriverFactory.createDriver();
                    }

                    @AfterEach
                    public void tearDownDriver() {
                        if (driver != null) {
                            driver.quit();
                        }
                    }

                    @Test
                    public void sayfaAcilmaliVeGozlenenBaslikGorunmeli() {
                        driver.get("%s");
                        assertEquals("%s", driver.getTitle(), "Gozlenen sayfa basligi eslesmeli");
                %s    }
                }
                """.formatted(className, pageUrl, escapeJava(title), elementAssertions);

        log.info("Deterministik gözlem smoke testi üretildi — başlık: '{}', element: {}",
                title, elementIds);

        GeneratedTestCase observed = buildTestCase(className, className + ".java", content,
                "[OBSERVED][DETERMINISTIC] Canli sayfa gozleminden uretildi: gercek <title> ve gercek element id'leri dogrulanir.");
        // LLM onarımından muaf: içeriği gözlemden geliyor, "düzeltilecek" bir yanı yok
        observed.setDeterministic(true);
        return Optional.of(observed);
    }

    private static String escapeJava(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private GeneratedTestCase buildTestCase(String name, String fileName,
                                             String content, String summary) {
        return GeneratedTestCase.builder()
                .testName(name)
                .fileName(fileName)
                .testContent(content)
                .testSummary(summary)
                .framework(TestFramework.SELENIUM)
                .build();
    }

    private void saveToFile(String fileName, String content) {
        javaTestProjectService.writeTestSource(TestFramework.SELENIUM, fileName, content);
    }

    private boolean isRunnableTestClass(String className) {
        return className.endsWith("Test") || className.endsWith("Tests");
    }
}
