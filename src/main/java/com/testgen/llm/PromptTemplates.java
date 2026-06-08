package com.testgen.llm;

/**
 * LLM prompt şablonları.
 * Her test türü için optimize edilmiş, few-shot örnekler içeren promptlar.
 */
public final class PromptTemplates {

    private PromptTemplates() {}

    // ─────────────────────────────────────────────────────────
    // KARATE (Backend API) Prompt
    // ─────────────────────────────────────────────────────────
    public static String buildKaratePrompt(String swaggerContent, String endpoint,
                                            String method, String context) {
        return """
                Aşağıdaki OpenAPI/Swagger spesifikasyonunu kullanarak Karate DSL test case'i oluştur.
                
                ## Endpoint Bilgisi
                Method: %s
                Path: %s
                
                ## Ek Bağlam
                %s
                
                ## OpenAPI Spec (ilgili kısım)
                ```yaml
                %s
                ```
                
                ## Gereksinimler
                1. Feature dosyası Karate DSL formatında olsun
                2. Background bölümünde base URL ve header'lar tanımlanmalı
                3. Şu test kapsamlarını ayrı Scenario veya Scenario Outline olarak mutlaka içermeli:
                   - [API][SMOKE] Kritik başarılı istek (200/201)
                   - [API][REGRESSION] Veri odaklı başarılı kombinasyonlar (Examples tablosu)
                   - [API][NEGATIVE] Validation hatası (400)
                   - [API][SECURITY] Unauthorized/forbidden kontrolü (401/403)
                   - [API][EDGE] Boundary, boş/null, maksimum uzunluk veya uç değer kontrolü
                   - [API][PERFORMANCE] Response time veya timeout beklentisi; Karate assertion ile ölçülebilecek hafif kontrol
                   - [API][REGRESSION] Not Found (404) veya state transition kontrolü - endpoint destekliyorsa
                4. Response validation schema kontrolü yapılsın
                5. `karate.configure` ile timeout ayarı yapılsın
                6. Her scenario adı kapsam etiketleriyle başlasın, örn: `Scenario: [API][SMOKE] ...`
                
                ## Örnek Karate Format
                ```
                Feature: [Feature Adı]
                
                  Background:
                    * url baseUrl
                    * configure ssl = true
                    * header Accept = 'application/json'
                    * header Content-Type = 'application/json'
                
                  Scenario: [Senaryo Adı]
                    Given path '/endpoint'
                    When method GET
                    Then status 200
                    And match response.id == '#notnull'
                ```
                
                Sadece feature dosyası içeriğini üret, başka açıklama ekleme.
                """.formatted(method, endpoint, context, swaggerContent);
    }

    // ─────────────────────────────────────────────────────────
    // SELENIUM (Frontend Web) Prompt
    // ─────────────────────────────────────────────────────────
    public static String buildSeleniumPrompt(String pageUrl, String userStory, String htmlHint) {
        return """
                Aşağıdaki bilgilere göre Selenium WebDriver Java test sınıfı oluştur.
                
                ## Test Edilecek Sayfa
                URL: %s
                
                ## Kullanıcı Hikayesi
                %s
                
                ## HTML İpuçları / Selector'lar
                %s
                
                ## Gereksinimler
                1. Page Object Model (POM) pattern kullan
                2. İKİ Java sınıfı üret:
                   a) [PageName]Page.java - Page Object sınıfı
                   b) [TestName]Test.java - Test sınıfı (JUnit 5)
                3. Şunları içermeli:
                   - WebDriverWait ile explicit wait
                   - @FindBy annotation'ları
                   - @BeforeEach / @AfterEach setup/teardown
                   - Pozitif ve negatif senaryolar
                   - Smoke, regression, edge, security ve hafif performance odaklı @Test metotları
                   - Screenshot on failure
                4. WebDriverManager ile driver yönetimi
                5. Chrome headless desteği
                6. Kod self-contained olsun:
                   - package satırı ekleme
                   - SeleniumExtension veya projede olmayan custom extension/base class kullanma
                   - Test sınıfı WebDriver'ı kendi @BeforeEach metodunda oluştursun
                   - Remote URL gerekiyorsa System.getProperty("selenium.remote.url", System.getenv("SELENIUM_REMOTE_URL")) oku
                7. Test metot adları kapsamı belli etsin:
                   - smokeShouldLoadCriticalJourney
                   - regressionShouldPersistExpectedState
                   - negativeShouldRejectInvalidInput
                   - edgeShouldHandleBoundaryInput
                   - securityShouldNotExposeProtectedAction
                   - performanceShouldCompleteCriticalUiActionWithinBudget
                
                ## Örnek Format
                ```java
                // LoginPage.java
                public class LoginPage {
                    @FindBy(id = "username")
                    private WebElement usernameField;
                    
                    public LoginPage enterUsername(String username) {
                        usernameField.sendKeys(username);
                        return this;
                    }
                }
                
                // LoginTest.java
                public class LoginTest {
                    private WebDriver driver;
                    
                    @BeforeEach
                    void setUp() { ... }
                    
                    @Test
                    void shouldLoginSuccessfully() { ... }
                }
                ```
                
                Her iki sınıfı tam olarak üret. Sadece kod üret.
                """.formatted(pageUrl, userStory, htmlHint);
    }

    // ─────────────────────────────────────────────────────────
    // APPIUM (Mobile) Prompt
    // ─────────────────────────────────────────────────────────
    public static String buildAppiumPrompt(String appPackage, String userStory,
                                            String platform, String additionalContext) {
        return """
                Aşağıdaki bilgilere göre Appium Java test sınıfı oluştur.
                
                ## Uygulama Bilgisi
                Platform: %s
                App Package: %s
                
                ## Kullanıcı Hikayesi
                %s
                
                ## Ek Bağlam
                %s
                
                ## Gereksinimler
                1. Appium Java Client v9+ kullan
                2. Page Object Model pattern uygula
                3. İKİ sınıf üret:
                   a) [ScreenName]Screen.java - Screen Object
                   b) [TestName]AppiumTest.java - Test sınıfı (JUnit 5)
                4. Capabilities tanımı:
                   - UiAutomator2Options (Android) veya XCUITestOptions (iOS)
                   - appPackage, appActivity, platformVersion
                5. Şunları içermeli:
                   - AppiumBy ile element bulma
                   - Touch actions / swipe
                   - Wait strategies (FluentWait)
                   - Pozitif/negatif senaryolar
                   - Smoke, regression, edge, security ve hafif performance odaklı @Test metotları
                6. Appium Server URL config'den alınsın
                7. Kod self-contained olsun:
                   - package satırı ekleme
                   - BaseScreen, BaseAppiumTest veya projede olmayan custom base class kullanma
                   - Appium driver test sınıfındaki @BeforeEach metodunda oluşturulsun
                   - Server URL System.getProperty("appium.server.url", System.getenv("APPIUM_SERVER_URL")) ile okunsun
                8. Test metot adları kapsamı belli etsin:
                   - smokeShouldOpenCriticalScreen
                   - regressionShouldCompleteCoreMobileFlow
                   - negativeShouldRejectInvalidMobileInput
                   - edgeShouldHandleOfflineOrBoundaryState
                   - securityShouldBlockUnauthorizedMobileAction
                   - performanceShouldCompleteGestureWithinBudget
                
                ## Örnek Format
                ```java
                // LoginScreen.java
                public class LoginScreen {
                    private final AppiumDriver driver;
                    
                    @AndroidFindBy(id = "com.app:id/username")
                    private WebElement usernameField;
                    
                    public LoginScreen typeUsername(String text) {
                        usernameField.sendKeys(text);
                        return this;
                    }
                }
                
                // LoginAppiumTest.java
                public class LoginAppiumTest {
                    private AppiumDriver driver;
                    
                    @BeforeEach
                    void setUp() { ... }
                    
                    @Test
                    void shouldLoginWithValidCredentials() { ... }
                }
                ```
                
                Her iki sınıfı tam olarak üret. Sadece kod üret.
                """.formatted(platform, appPackage, userStory, additionalContext);
    }

    // ─────────────────────────────────────────────────────────
    // USER STORY → Test Case (Genel)
    // ─────────────────────────────────────────────────────────
    public static String buildUserStoryPrompt(String userStory, String framework, String context) {
        return """
                Aşağıdaki kullanıcı hikayesini analiz ederek %s framework'ü için test case'ler üret.
                
                ## Kullanıcı Hikayesi
                %s
                
                ## Ek Bağlam
                %s
                
                Kapsamlı test coverage için:
                - API/contract senaryoları
                - Smoke test: kritik akışın hızlı doğrulaması
                - Regression test: daha önce çalışan temel davranışların veri varyasyonlarıyla korunması
                - Negative test: geçersiz input, validation ve hata mesajları
                - Edge/boundary test: boş/null/maksimum/limit değerleri
                - Security test: yetkisiz erişim, hassas veri sızıntısı veya forbidden kontrolü
                - Performance test: framework içinde ölçülebilen hafif süre/timeout beklentisi
                - Data-driven test: en az 4 gerçekçi veri seti
                
                Her senaryo veya test metodu kapsam etiketini açıkça taşısın:
                [API], [SMOKE], [REGRESSION], [NEGATIVE], [EDGE], [SECURITY], [PERFORMANCE].
                
                Sadece kod üret.
                """.formatted(framework, userStory, context);
    }
}
