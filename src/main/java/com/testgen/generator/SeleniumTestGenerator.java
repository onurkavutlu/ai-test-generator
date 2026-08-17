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
public class SeleniumTestGenerator implements FrameworkTestGenerator {

    private final LlmService llmService;
    private final GeneratedJavaTestProjectService javaTestProjectService;
    private final GenerationLimit generationLimit;

    @Override
    public TestFramework framework() {
        return TestFramework.SELENIUM;
    }

    @Override
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

        // Gerçekten yürütülmüş bir kullanıcı akışı varsa, LLM'in serbest kodu bu
        // dikey dilimde çalıştırılabilir test olarak kabul edilmez. LLM'in selector
        // veya ara durum uydurma olasılığı vardır; aşağıdaki derleyici doğrudan
        // kanıttaki locator/sonuçtan Java üretir. Bu kural yalnız flow-discovery
        // opt-in iken devreye girer; mevcut Selenium üretim davranışı korunur.
        if (htmlHint.contains("## OBSERVED USER FLOW")) {
            buildObservedSmokeTest(pageUrl, htmlHint).ifPresent(tc -> {
                saveToFile(tc.getFileName(), tc.getTestContent());
                results.add(tc);
            });
            buildObservedUserFlowTest(pageUrl, htmlHint).ifPresent(tc -> {
                saveToFile(tc.getFileName(), tc.getTestContent());
                results.add(tc);
            });
            if (results.stream().noneMatch(tc -> "ObservedUserFlowTest".equals(tc.getTestName()))) {
                throw new com.testgen.config.BadRequestException(
                        "Doğrulanmış kullanıcı akışı Selenium testine güvenle derlenemedi; "
                                + "eksik veya desteklenmeyen locator için test uydurulmaz.");
            }
            return applyMaxCases(results, request);
        }

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
        // Çok-adımlı bir kullanıcı yolu gerçekten tarayıcıda doğrulandıysa, LLM'in
        // yorumuna ihtiyaç duymayan ikinci bir kanıt-temelli koşum testi üret. Bu
        // test yalnız gözlemlenen tıklama sırasını, locator'ı ve son durumu kullanır.
        buildObservedUserFlowTest(pageUrl, htmlHint).ifPresent(tc -> {
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
                // Eski çağıranlar yalnız adıyla oluşturulmuş ObservedSmokeTest de
                // gönderebilir; geriye dönük maxCases sözleşmesini koru.
                .filter(tc -> tc.isDeterministic()
                        || OBSERVED_SMOKE_TEST_NAME.equals(tc.getTestName())
                        || "ObservedUserFlowTest".equals(tc.getTestName()))
                // Bir limit altında çok-adımlı, kullanıcı tarafından istenmiş akış;
                // genel sayfa smoke'undan daha değerli kanıttır.
                .sorted((left, right) -> Boolean.compare(
                        "ObservedUserFlowTest".equals(right.getTestName()),
                        "ObservedUserFlowTest".equals(left.getTestName())))
                .toList();
        List<GeneratedTestCase> generated = results.stream()
                .filter(tc -> !observed.contains(tc))
                .toList();

        int keepFromLlm = Math.max(0, limit - observed.size());
        List<GeneratedTestCase> kept = new ArrayList<>(generated.subList(0, Math.min(keepFromLlm, generated.size())));
        kept.addAll(observed.subList(0, Math.min(limit, observed.size())));

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
    private static final Pattern OBSERVED_UI_SELECTOR = Pattern.compile(
            "(?m)^-\\s+\\w+\\s+\\|\\s+selector:\\s+(data-testid|id|name)=([A-Za-z0-9_.:-]+)([^\\r\\n]*)$");
    private static final Pattern OBSERVED_USER_FLOW = Pattern.compile(
            "(?ms)^## OBSERVED USER FLOW.*?(?=^## |\\z)");
    private static final Pattern OBSERVED_FLOW_STEP = Pattern.compile(
            "(?m)^\\d+\\. tıkla: (.+?) \\| locator: (.+?) \\| sonuç: (.+)$");
    private static final Pattern FLOW_LINK_TEXT = Pattern.compile("(?:visible )?link text '(.+)'$");
    private static final Pattern FLOW_CARD_LINK = Pattern.compile(
            "visible \\.tarife-card containing '(.+)' -> link text '(.+)'$");
    private static final Pattern FLOW_FINAL_URL = Pattern.compile("(?m)^Akış sonu URL: (.+)$");
    private static final Pattern FLOW_FINAL_TITLE = Pattern.compile("(?m)^Akış sonu başlık: (.+)$");

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

        List<ObservedLocator> visibleLocators = new ArrayList<>();
        List<ObservedLocator> sourceLocators = new ArrayList<>();
        Matcher selectorMatcher = OBSERVED_UI_SELECTOR.matcher(context);
        while (selectorMatcher.find()) {
            boolean visible = selectorMatcher.group(3).contains("| state: visible");
            ObservedLocator locator = new ObservedLocator(selectorMatcher.group(1), selectorMatcher.group(2), visible);
            List<ObservedLocator> target = visible ? visibleLocators : sourceLocators;
            if (!target.contains(locator)) {
                target.add(locator);
            }
        }
        // Render edilmiş sözleşme görünürlük kanıtı taşır; bu yüzden kaynak HTML'den
        // çıkarılan (gizli de olabilecek) locator'ların önünde yer alır.
        List<ObservedLocator> locators = new ArrayList<>(visibleLocators);
        for (ObservedLocator locator : sourceLocators) {
            if (locators.size() >= 5) break;
            if (locators.stream().noneMatch(existing -> sameLocator(existing, locator))) locators.add(locator);
        }

        // Eski gözlem biçimleri yalnız id listesi taşır; geriye dönük olarak aynı
        // deterministik smoke testini üretmeye devam ederiz.
        Matcher elementMatcher = OBSERVED_ELEMENTS.matcher(context);
        if (elementMatcher.find()) {
            for (String token : elementMatcher.group(1).split(",")) {
                String trimmed = token.trim();
                int hash = trimmed.indexOf('#');
                if (hash >= 0 && hash + 1 < trimmed.length()) {
                    String id = trimmed.substring(hash + 1);
                    ObservedLocator locator = new ObservedLocator("id", id, false);
                    if (id.matches("[A-Za-z0-9_.:-]+") && locators.size() < 5
                            && locators.stream().noneMatch(existing -> sameLocator(existing, locator))) {
                        locators.add(locator);
                    }
                }
            }
        }

        // Kaynak HTML sözleşmesi yalnız DOM varlığını bilir. Render edilmiş sözleşme ise
        // görünürlüğü gerçekten ölçer; yalnız onda explicit wait + görünürlük assertion'ı
        // kullanılır. Böylece kapalı sekme/panel için yanlış negatif üretilmez.
        StringBuilder elementAssertions = new StringBuilder();
        for (ObservedLocator locator : locators) {
            if (locator.visible()) {
                elementAssertions.append("        assertTrue(new WebDriverWait(driver, Duration.ofSeconds(10))\n")
                        .append("                .until(ExpectedConditions.visibilityOfElementLocated(")
                        .append(toSeleniumLocator(locator)).append(")).isDisplayed(),\n")
                        .append("                \"Render edilmis ve gorunur element bulunmali: ")
                        .append(locator.kind()).append('=').append(locator.value()).append("\");\n");
            } else {
                elementAssertions.append("        assertFalse(driver.findElements(")
                        .append(toSeleniumLocator(locator)).append(").isEmpty(),\n")
                        .append("                \"Gozlenen element DOM'da bulunmali: ")
                        .append(locator.kind()).append('=').append(locator.value()).append("\");\n");
            }
        }

        String className = OBSERVED_SMOKE_TEST_NAME;
        String content = """
                package com.testgen.generated;

                import org.junit.jupiter.api.AfterEach;
                import org.junit.jupiter.api.BeforeEach;
                import org.junit.jupiter.api.Test;
                import org.openqa.selenium.By;
                import org.openqa.selenium.WebDriver;
                import org.openqa.selenium.support.ui.ExpectedConditions;
                import org.openqa.selenium.support.ui.WebDriverWait;

                import java.time.Duration;

                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertFalse;
                import static org.junit.jupiter.api.Assertions.assertTrue;

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
                title, locators);

        GeneratedTestCase observed = buildTestCase(className, className + ".java", content,
                "[OBSERVED][DETERMINISTIC] Canli sayfa gozleminden uretildi: gercek baslik, locator ve varsa render edilmis gorunurluk dogrulanir.");
        // LLM onarımından muaf: içeriği gözlemden geliyor, "düzeltilecek" bir yanı yok
        observed.setDeterministic(true);
        return Optional.of(observed);
    }

    /**
     * LLM kararına bırakılmayan, çalıştırılmış kullanıcı yolunun doğrudan Selenium
     * karşılığı. Bu yöntem kanıtı çözemiyorsa kısmi/varsayımsal test üretmez.
     */
    Optional<GeneratedTestCase> buildObservedUserFlowTest(String pageUrl, String context) {
        if (context == null) {
            return Optional.empty();
        }
        Matcher flowSection = OBSERVED_USER_FLOW.matcher(context);
        if (!flowSection.find()) {
            return Optional.empty();
        }
        String flow = flowSection.group();
        Matcher stepMatcher = OBSERVED_FLOW_STEP.matcher(flow);
        List<String> actions = new ArrayList<>();
        while (stepMatcher.find()) {
            Optional<String> locator = flowLocator(stepMatcher.group(2));
            if (locator.isEmpty()) {
                return Optional.empty();
            }
            actions.add("        new WebDriverWait(driver, Duration.ofSeconds(10))\n"
                    + "                .until(ExpectedConditions.elementToBeClickable(" + locator.get() + ")).click();\n");
        }
        if (actions.isEmpty()) {
            return Optional.empty();
        }

        String assertions = finalValue(FLOW_FINAL_URL, flow)
                .map(url -> "        assertEquals(\"" + escapeJava(url) + "\", driver.getCurrentUrl(),\n"
                        + "                \"Gözlemlenen akış sonu URL eşleşmeli\");\n")
                .orElse("");
        assertions += finalValue(FLOW_FINAL_TITLE, flow)
                .map(title -> "        assertEquals(\"" + escapeJava(title) + "\", driver.getTitle(),\n"
                        + "                \"Gözlemlenen akış sonu başlık eşleşmeli\");\n")
                .orElse("");

        String className = "ObservedUserFlowTest";
        String content = """
                package com.testgen.generated;

                import org.junit.jupiter.api.AfterEach;
                import org.junit.jupiter.api.BeforeEach;
                import org.junit.jupiter.api.Test;
                import org.openqa.selenium.By;
                import org.openqa.selenium.WebDriver;
                import org.openqa.selenium.support.ui.ExpectedConditions;
                import org.openqa.selenium.support.ui.WebDriverWait;

                import java.time.Duration;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                /** Tarayıcıda gerçekten yürütülmüş kullanıcı yolundan deterministik üretildi. */
                public class %s {
                    private WebDriver driver;

                    @BeforeEach
                    public void setUpDriver() {
                        driver = DriverFactory.createDriver();
                    }

                    @AfterEach
                    public void tearDownDriver() {
                        if (driver != null) driver.quit();
                    }

                    @Test
                    public void gozlemlenenKullaniciAkisiCalismali() {
                        driver.get("%s");
                %s%s    }
                }
                """.formatted(className, escapeJava(pageUrl), String.join("", actions), assertions);

        GeneratedTestCase observed = buildTestCase(className, className + ".java", content,
                "[OBSERVED][DETERMINISTIC] Tarayıcıda doğrulanmış kullanıcı akışından üretildi; LLM locator seçmedi.");
        observed.setDeterministic(true);
        return Optional.of(observed);
    }

    private static Optional<String> flowLocator(String rawLocator) {
        Matcher card = FLOW_CARD_LINK.matcher(rawLocator);
        if (card.find()) {
            String cardText = xpathLiteral(card.group(1));
            String linkText = xpathLiteral(card.group(2));
            return Optional.of("By.xpath(\"//*[contains(concat(' ', normalize-space(@class), ' '), ' tarife-card ')]"
                    + "[contains(normalize-space(.), " + escapeJava(cardText) + ")]//a[normalize-space(.)="
                    + escapeJava(linkText) + "]\")");
        }
        Matcher link = FLOW_LINK_TEXT.matcher(rawLocator);
        return link.find() ? Optional.of("By.linkText(\"" + escapeJava(link.group(1)) + "\")") : Optional.empty();
    }

    private static Optional<String> finalValue(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? Optional.of(matcher.group(1).trim()) : Optional.empty();
    }

    /** XPath string literal: tek/tırnak içeren görünen metin de güvenle derlenir. */
    private static String xpathLiteral(String value) {
        if (!value.contains("'")) return "'" + value + "'";
        if (!value.contains("\"")) return "\"" + value + "\"";
        return "concat('" + value.replace("'", "', \"'\", '") + "')";
    }

    private static String escapeJava(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String toSeleniumLocator(ObservedLocator locator) {
        return switch (locator.kind()) {
            case "data-testid" -> "By.cssSelector(\"[data-testid='" + locator.value() + "']\")";
            case "name" -> "By.name(\"" + locator.value() + "\")";
            default -> "By.id(\"" + locator.value() + "\")";
        };
    }

    private static boolean sameLocator(ObservedLocator left, ObservedLocator right) {
        return left.kind().equals(right.kind()) && left.value().equals(right.value());
    }

    private record ObservedLocator(String kind, String value, boolean visible) { }

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
