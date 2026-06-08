// ─── LoginPage.java (AI-Generated Page Object) ───────────────────────────────
package com.testgen.generated.selenium;

import org.openqa.selenium.*;
import org.openqa.selenium.support.*;
import org.openqa.selenium.support.ui.*;
import java.time.Duration;

/**
 * Login sayfası Page Object - AI Test Generator tarafından üretildi
 */
public class LoginPage {

    private final WebDriver driver;
    private final WebDriverWait wait;

    @FindBy(id = "username")
    private WebElement usernameField;

    @FindBy(id = "password")
    private WebElement passwordField;

    @FindBy(css = "button[type='submit']")
    private WebElement loginButton;

    @FindBy(css = ".error-message, [data-testid='error']")
    private WebElement errorMessage;

    @FindBy(css = ".success-toast, [data-testid='success']")
    private WebElement successMessage;

    public LoginPage(WebDriver driver) {
        this.driver = driver;
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(10));
        PageFactory.initElements(driver, this);
    }

    public LoginPage open(String baseUrl) {
        driver.get(baseUrl + "/login");
        wait.until(ExpectedConditions.visibilityOf(usernameField));
        return this;
    }

    public LoginPage enterUsername(String username) {
        usernameField.clear();
        usernameField.sendKeys(username);
        return this;
    }

    public LoginPage enterPassword(String password) {
        passwordField.clear();
        passwordField.sendKeys(password);
        return this;
    }

    public void clickLogin() {
        loginButton.click();
    }

    public DashboardPage loginSuccessfully(String username, String password) {
        enterUsername(username).enterPassword(password).clickLogin();
        wait.until(ExpectedConditions.urlContains("/dashboard"));
        return new DashboardPage(driver);
    }

    public String getErrorMessage() {
        wait.until(ExpectedConditions.visibilityOf(errorMessage));
        return errorMessage.getText();
    }

    public boolean isErrorDisplayed() {
        try {
            return wait.until(ExpectedConditions.visibilityOf(errorMessage)).isDisplayed();
        } catch (TimeoutException e) {
            return false;
        }
    }
}

// ─── LoginTest.java (AI-Generated Test Class) ────────────────────────────────
package com.testgen.generated.selenium;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.junit.jupiter.api.*;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Login UI testleri - AI Test Generator tarafından üretildi
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LoginTest {

    private static WebDriver driver;
    private LoginPage loginPage;

    private static final String BASE_URL  = System.getProperty("app.url", "http://localhost:3000");
    private static final String VALID_USER = System.getProperty("test.user", "admin@example.com");
    private static final String VALID_PASS = System.getProperty("test.pass", "Admin@123");

    @BeforeAll
    static void setupDriver() {
        WebDriverManager.chromedriver().setup();
        var options = new ChromeOptions();
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage",
                             "--window-size=1920,1080");
        driver = new org.openqa.selenium.chrome.ChromeDriver(options);
    }

    @AfterAll
    static void tearDownDriver() {
        if (driver != null) driver.quit();
    }

    @BeforeEach
    void setUp() {
        loginPage = new LoginPage(driver);
        loginPage.open(BASE_URL);
    }

    @AfterEach
    void captureScreenshotOnFailure(TestInfo testInfo) {
        // JUnit 5 ile screenshot on failure – test başarısız olduysa ekran görüntüsü al
        if (driver instanceof TakesScreenshot ts) {
            byte[] screenshot = ts.getScreenshotAs(OutputType.BYTES);
            // İsteğe göre dosyaya kaydet
        }
    }

    @Test
    @Order(1)
    @DisplayName("Geçerli kimlik bilgileriyle başarılı giriş")
    void shouldLoginWithValidCredentials() {
        var dashboard = loginPage.loginSuccessfully(VALID_USER, VALID_PASS);
        assertThat(driver.getCurrentUrl()).contains("/dashboard");
    }

    @Test
    @Order(2)
    @DisplayName("Hatalı şifre ile giriş hata mesajı göstermeli")
    void shouldShowErrorWithInvalidPassword() {
        loginPage.enterUsername(VALID_USER)
                 .enterPassword("WrongPassword123!")
                 .clickLogin();

        assertThat(loginPage.isErrorDisplayed()).isTrue();
        assertThat(loginPage.getErrorMessage())
                .containsIgnoringCase("invalid")
                .isNotBlank();
    }

    @Test
    @Order(3)
    @DisplayName("Boş kullanıcı adıyla giriş hata göstermeli")
    void shouldShowErrorWithEmptyUsername() {
        loginPage.enterUsername("")
                 .enterPassword(VALID_PASS)
                 .clickLogin();

        assertThat(loginPage.isErrorDisplayed()).isTrue();
    }

    @Test
    @Order(4)
    @DisplayName("SQL Injection girişimi engellenmeli")
    void shouldPreventSqlInjection() {
        loginPage.enterUsername("admin' OR '1'='1")
                 .enterPassword("anything")
                 .clickLogin();

        assertThat(driver.getCurrentUrl()).doesNotContain("/dashboard");
        assertThat(loginPage.isErrorDisplayed()).isTrue();
    }
}
