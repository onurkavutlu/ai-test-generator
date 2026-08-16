package com.testgen.e2e;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Son kullanıcı gözünden</b> dashboard testi — gerçek tarayıcı, gerçek sunucu.
 *
 * <p>Bu sınıf, projenin kendi ürettiği testlerden farklı olarak <b>ürünün kendisini</b>
 * test eder. Buradaki her senaryo, kullanıcının fareyle yaptığı bir işi taklit eder:
 * sayfayı aç, menüden geç, formu doldur, butona bas, sonucu gör.
 *
 * <p><b>Neden değerli:</b> Birim ve controller testleri "API doğru cevap veriyor mu"
 * sorusunu cevaplar. Ama dashboard 3.000 satırlık tek bir HTML dosyası ve tüm JS'i
 * inline; bir {@code id} değiştiğinde ya da {@code switchView} bozulduğunda hiçbir Java
 * testi kırılmaz — ekran sessizce boş kalır. Bu testler o boşluğu kapatır.
 *
 * <p><b>Çalıştırma:</b> Chrome/Chromium ve chromedriver gerekir. CI'da headless koşar:
 * {@code SELENIUM_HEADLESS=true}. Sunucu rastgele portta Spring tarafından ayağa kaldırılır,
 * yani ayrıca uygulama başlatmak gerekmez.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:e2e_selenium;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "notification.email.enabled=false",
        "scheduler.daily-run.cron=-"
})
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Dashboard — son kullanıcı akışı (Selenium)")
class DashboardSeleniumE2ETest {

    @LocalServerPort
    private int port;

    private static WebDriver driver;
    private static WebDriverWait wait;

    /** Tarayıcı başlatılamadıysa nedeni; testler bu durumda ATLANIR, kırmızı yanmaz. */
    private static String browserUnavailableReason;

    @BeforeAll
    static void startBrowser() {
        try {
            launchBrowser();
        } catch (Exception | Error e) {
            // Chrome/chromedriver kurulu değilse ya da sürümleri uyuşmuyorsa testin
            // BAŞARISIZ olması yanıltıcıdır: ürün bozuk değil, ortam eksiktir. Kırmızıya
            // duyarsızlaşmamak için atlanır ve neden açıkça yazılır.
            browserUnavailableReason = e.getClass().getSimpleName() + ": "
                    + String.valueOf(e.getMessage()).lines().findFirst().orElse("");
        }
    }

    private static void launchBrowser() {
        ChromeOptions options = new ChromeOptions();
        // CI ve konteyner ortamı: görünür tarayıcı yok, paylaşımlı bellek kısıtlı
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage",
                "--disable-gpu", "--window-size=1600,1200");
        String binary = System.getenv("CHROME_BINARY");
        if (binary != null && !binary.isBlank()) {
            options.setBinary(binary);
        }

        // Tarayıcı konsol loglarını okuyabilmek için (bkz. son senaryo)
        var logPrefs = new org.openqa.selenium.logging.LoggingPreferences();
        logPrefs.enable(org.openqa.selenium.logging.LogType.BROWSER,
                java.util.logging.Level.WARNING);
        options.setCapability("goog:loggingPrefs", logPrefs);

        // Sürüm kontrolü VARSAYILAN OLARAK AÇIK kalır: chromedriver ile tarayıcı
        // sürümünün uyuşmaması gerçek bir kurulum hatasıdır ve sessizce geçilmemeli.
        // Yalnızca sürümleri eşleştiremeyen kapalı ortamlarda (ör. hazır imajlı CI
        // konteynerleri) SELENIUM_DISABLE_BUILD_CHECK=true ile geçici olarak kapatılır.
        if (Boolean.parseBoolean(System.getenv("SELENIUM_DISABLE_BUILD_CHECK"))) {
            var service = new org.openqa.selenium.chrome.ChromeDriverService.Builder()
                    .withBuildCheckDisabled(true)
                    .build();
            driver = new ChromeDriver(service, options);
        } else {
            driver = new ChromeDriver(options);
        }
        wait = new WebDriverWait(driver, Duration.ofSeconds(20));
    }

    @AfterAll
    static void stopBrowser() {
        if (driver != null) {
            driver.quit();
        }
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private void openDashboard() {
        org.junit.jupiter.api.Assumptions.assumeTrue(driver != null,
                () -> "Tarayıcı başlatılamadı, test atlandı — " + browserUnavailableReason);
        driver.get(baseUrl() + "/");
        wait.until(ExpectedConditions.presenceOfElementLocated(By.id("nav-requests")));
    }

    /**
     * Menüden bir görünüme geç ve o görünümün gerçekten aktif <b>ve görünür</b>
     * olduğunu doğrula.
     *
     * <p>Yalnızca {@code class="active"} beklemek yetmiyordu: sınıf DOM'a anında
     * yazılıyor, görünürlük ise CSS geçişi bitince oluşuyor. Yük altında (tüm suite
     * koşarken) aradaki boşluk açılıyor ve görünürlük iddiaları rastgele düşüyordu —
     * bu test tek başına geçip suite içinde patlıyordu. Görünürlüğü de beklemek bu
     * kırılganlığı kapatır; {@code sleep} eklemek yalnızca gizlerdi.
     */
    private void switchTo(String view) {
        driver.findElement(By.id("nav-" + view)).click();
        wait.until(ExpectedConditions.attributeContains(
                By.id("view-" + view), "class", "active"));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("view-" + view)));
    }

    @Test
    @Order(1)
    @DisplayName("Dashboard açılır ve başlığı doğrudur")
    void dashboardLoads() {
        openDashboard();

        assertEquals("AI Test Generator - Dashboard", driver.getTitle());
    }

    /**
     * Tüm menü girdilerinin çalışması kritik: {@code switchView} tek bir fonksiyon ve
     * bir görünümün id'si değiştiğinde JS sessizce patlar (konsol hatası), kullanıcı
     * boş ekran görür. Java tarafında hiçbir test bunu yakalamaz.
     */
    @Test
    @Order(2)
    @DisplayName("Tüm menü girdileri ilgili görünümü açar")
    void everyNavigationLinkOpensItsView() {
        openDashboard();

        for (String view : List.of("requests", "create", "stats", "ai-reports",
                "runner", "suites", "plans", "executions", "system-logs")) {
            switchTo(view);

            WebElement section = driver.findElement(By.id("view-" + view));
            assertTrue(section.getAttribute("class").contains("active"),
                    "Görünüm aktif olmadı: " + view);
            assertTrue(section.isDisplayed(), "Görünüm görünmüyor: " + view);
        }
    }

    @Test
    @Order(3)
    @DisplayName("Yeni Test Üretimi formu gerekli alanları taşır")
    void generationFormExposesRequiredFields() {
        openDashboard();
        switchTo("create");

        // Görünürlük beklenerek doğrulanır: anlık findElement+isDisplayed, CSS geçişi
        // henüz bitmemişken false döner ve testi yük altında kırılgan yapardı.
        for (String id : List.of("create-request-form", "framework", "btn-submit-generate")) {
            assertTrue(wait.until(ExpectedConditions.visibilityOfElementLocated(By.id(id)))
                            .isDisplayed(),
                    "Form alanı görünür değil: " + id);
        }
    }

    /**
     * Framework listesi {@code adaptFrameworkOptions()} ile doluyor. Bu daha önce
     * yalnızca testType'ın onchange'ine bağlıydı ve ilk açılışta liste BOŞ kalıyordu —
     * kullanıcı framework seçemiyordu. Kodda düzeltilmiş; burada kilitleniyor.
     */
    @Test
    @Order(4)
    @DisplayName("Framework listesi ilk açılışta dolu gelir")
    void frameworkOptionsArePopulatedOnFirstOpen() {
        openDashboard();
        switchTo("create");

        List<WebElement> options = driver.findElements(By.cssSelector("#framework option"));

        assertFalse(options.isEmpty(),
                "Framework listesi boş — kullanıcı framework seçemez");
        assertTrue(options.stream().anyMatch(o -> "KARATE".equals(o.getAttribute("value"))),
                "KARATE seçeneği yok: " + options.stream().map(o -> o.getAttribute("value")).toList());
    }

    /**
     * Runner ekranı ürünün "Postman kadar faydalı" olduğu yer. Buradaki akış uçtan uca:
     * URL yaz → Çalıştır → gerçek HTTP isteği atılır → status ve gövde ekranda belirir.
     * Bu test gerçek bir istek attırır (uygulamanın kendi health ucuna).
     */
    @Test
    @Order(5)
    @DisplayName("Runner: gerçek istek atılır ve yanıt ekranda gösterilir")
    void runnerExecutesRealRequestAndShowsResponse() {
        openDashboard();
        switchTo("runner");

        WebElement urlInput = driver.findElement(By.id("direct-url"));
        urlInput.clear();
        urlInput.sendKeys(baseUrl() + "/api/v1/tests/health");

        driver.findElement(By.id("direct-run-btn")).click();

        // Sonuç bloğu görünür olana kadar bekle — asenkron fetch
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("direct-result")));
        wait.until(ExpectedConditions.textMatches(By.id("direct-status-badge"), java.util.regex.Pattern.compile("200")));

        String body = driver.findElement(By.id("direct-response-body")).getText();
        assertTrue(body.contains("AI Test Generator"),
                "Gerçek yanıt gövdesi ekrana yazılmadı: " + body);

        String latency = driver.findElement(By.id("direct-latency")).getText();
        assertFalse(latency.isBlank(), "Gecikme ölçümü gösterilmedi");
    }

    @Test
    @Order(6)
    @DisplayName("Runner: Postman cURL metodu algılanır ve response sekmeleri çalışır")
    void runnerInfersCurlMethodAndRendersResponseTabs() {
        openDashboard();
        switchTo("runner");

        WebElement raw = driver.findElement(By.id("runner-rawPayload"));
        raw.sendKeys("curl --location 'http://localhost:" + port
                + "/api/v1/tests/health' --data '{}'");

        WebElement analysis = wait.until(
                ExpectedConditions.visibilityOfElementLocated(By.id("runner-curl-analysis")));
        assertTrue(analysis.getText().contains("POST"),
                "--data içeren cURL POST olarak gösterilmedi: " + analysis.getText());

        ((JavascriptExecutor) driver).executeScript("runnerRenderObservation(arguments[0])", Map.of(
                "observedStatus", 200,
                "observedDurationMs", 42,
                "observedRequestLine", "POST https://example.test/soap",
                "observedBody", "<Envelope><Body><result>OK</result></Body></Envelope>",
                "observedResponseHeaders", "content-type: text/xml\nx-trace-id: trace-1",
                "observedResponseCookies", "SESSION=abc; Path=/; HttpOnly",
                "observedResponseSizeBytes", 57,
                "observedHttpVersion", "HTTP_2"
        ));

        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("runner-observed-section")));
        assertTrue(driver.findElement(By.id("runner-observed-section")).getText().contains("HTTP 200"));
        assertTrue(driver.findElement(By.id("runner-response-pane-body")).getText().contains("<Envelope>"));

        driver.findElement(By.cssSelector("button[data-tab='headers']")).click();
        assertTrue(driver.findElement(By.id("runner-response-pane-headers")).getText().contains("x-trace-id"));

        driver.findElement(By.cssSelector("button[data-tab='cookies']")).click();
        assertTrue(driver.findElement(By.id("runner-response-pane-cookies")).getText().contains("SESSION=abc"));
    }

    /**
     * SSRF kapısı ürün davranışıdır ve kullanıcıya <b>anlaşılır</b> görünmelidir.
     * Sunucu 400 + açıklayıcı mesaj döner; ekranda "sistem bozuk" izlenimi doğmamalı.
     */
    @Test
    @Order(7)
    @DisplayName("Runner: engellenen adres kullanıcıya açıklamasıyla bildirilir")
    void runnerShowsBlockedAddressExplanation() {
        openDashboard();
        switchTo("runner");

        WebElement urlInput = driver.findElement(By.id("direct-url"));
        urlInput.clear();
        urlInput.sendKeys("http://169.254.169.254/latest/meta-data/");

        driver.findElement(By.id("direct-run-btn")).click();

        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("direct-result")));
        String shown = driver.findElement(By.id("direct-result")).getText().toLowerCase();

        assertTrue(shown.contains("metadata") || shown.contains("400") || shown.contains("atılamaz"),
                "Engelleme nedeni kullanıcıya gösterilmedi. Ekranda: " + shown);
    }

    @Test
    @Order(8)
    @DisplayName("Sistem Logları görünümü log satırlarını gösterir")
    void systemLogsViewRendersLines() {
        openDashboard();
        switchTo("system-logs");

        WebElement view = driver.findElement(By.id("view-system-logs"));
        wait.until(d -> !view.getText().isBlank());

        assertFalse(view.getText().isBlank(), "Log görünümü boş kaldı");
    }

    /**
     * Tarayıcı konsolundaki JS hataları sessizdir: sunucu 200 döner, sayfa açılır, ama
     * bir fonksiyon patladığı için ekranın yarısı çalışmaz. Bu kontrol o sessizliği kırar.
     *
     * <p><b>Neden filtre var:</b> Konsoldaki her SEVERE satırı JS hatası değildir.
     * İki tür gürültü bilinçli olarak dışarıda bırakılıyor:
     * <ul>
     *   <li><b>Dış kaynak yükleme hataları</b> (Google Fonts vb.): ağı kısıtlı ortamlarda
     *       her zaman düşer, sayfanın çalışmasını engellemez.</li>
     *   <li><b>Kendi API'mizden gelen beklenen 4xx'ler</b>: SSRF reddi gibi durumlar
     *       ÜRÜNÜN DOĞRU davranışıdır; hata sayılırsa test kendi doğruluğunu cezalandırır.</li>
     * </ul>
     * Geriye yalnızca gerçek JS istisnaları kalır — sayfayı bozan tek şey odur.
     */
    @Test
    @Order(9)
    @DisplayName("Gezinme sırasında tarayıcı konsolunda JS istisnası oluşmaz")
    void noJavaScriptErrorsWhileNavigating() {
        openDashboard();
        // Önceki senaryoların bıraktığı konsol kayıtlarını tüket — bu test yalnızca
        // buradan sonrasını değerlendirir.
        driver.manage().logs().get("browser").getAll();

        for (String view : List.of("requests", "create", "runner", "suites", "plans", "executions")) {
            switchTo(view);
        }

        List<String> jsErrors = driver.manage().logs().get("browser").getAll().stream()
                .filter(e -> "SEVERE".equals(e.getLevel().getName()))
                .map(e -> e.getMessage())
                .filter(DashboardSeleniumE2ETest::isRealJavaScriptError)
                .toList();

        assertTrue(jsErrors.isEmpty(), "Konsolda JS istisnası var:\n" + String.join("\n", jsErrors));
    }

    /** Ağ/kaynak gürültüsünü eleyip yalnızca JS çalışma zamanı istisnalarını bırakır. */
    private static boolean isRealJavaScriptError(String message) {
        if (message.contains("Failed to load resource") || message.contains("favicon")) {
            return false;
        }
        return message.contains("Uncaught")
                || message.contains("TypeError")
                || message.contains("ReferenceError")
                || message.contains("SyntaxError")
                || message.contains("is not a function")
                || message.contains("is not defined");
    }
}
