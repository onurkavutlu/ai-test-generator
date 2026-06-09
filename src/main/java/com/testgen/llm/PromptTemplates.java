package com.testgen.llm;

/**
 * LLM prompt şablonları — ISTQB standartlarına uyumlu test üretimi için.
 *
 * Her prompt aşağıdaki ISTQB test tasarım tekniklerini içerir:
 * - Equivalence Partitioning (Eşdeğerlik Bölümleme)
 * - Boundary Value Analysis (Sınır Değer Analizi)
 * - Decision Table (Karar Tablosu)
 * - State Transition (Durum Geçişi)
 * - Error Guessing (Hata Tahmini)
 */
public final class PromptTemplates {

        private PromptTemplates() {
        }

        /**
         * Swagger spec'i 3000 karakterde keser — küçük modeller için context koruması.
         */
        private static String truncateSpec(String spec) {
                if (spec == null)
                        return "";
                return spec.length() > 3000 ? spec.substring(0, 3000) + "\n... [spec kısaltıldı]" : spec;
        }

        /**
         * ISTQB test tasarım teknikleri kural seti — tüm prompt'lara eklenir.
         */
        private static final String ISTQB_RULES = """

                        ISTQB Test Design Techniques (her senaryo için uygun tekniği belirle):
                        - [EP] Equivalence Partitioning: Girdi alanını geçerli/geçersiz sınıflara ayır, her sınıftan 1 temsilci seç.
                        - [BVA] Boundary Value Analysis: min, min+1, max-1, max sınır değerlerini test et.
                        - [DT] Decision Table: Birden fazla koşulun kombinasyonlarını tablo ile kapsa.
                        - [ST] State Transition: Sistem durumları arası geçişleri ve geçersiz geçişleri test et.
                        - [EG] Error Guessing: Deneyime dayalı yaygın hata senaryolarını (null, duplicate, timeout) ekle.

                        ISTQB Metadata (her senaryo başlığında belirt):
                        - Category: SMOKE | REGRESSION | NEGATIVE | BOUNDARY | SECURITY | PERFORMANCE | E2E | ACCEPTANCE
                        - Priority: P0_BLOCKER | P1_CRITICAL | P2_MAJOR | P3_MINOR
                        - Level: UNIT | INTEGRATION | SYSTEM | ACCEPTANCE
                        - Technique: EP | BVA | DT | ST | EG | AI_GENERATED

                        Senaryo başlık formatı: [CATEGORY][PRIORITY][TECHNIQUE] Senaryo Adı
                        Örnek: [SMOKE][P0_BLOCKER][EP] Geçerli kullanıcı ile login testi
                        """;

        // ─────────────────────────────────────────────────────────
        // KARATE (Backend API)
        // ─────────────────────────────────────────────────────────
        public static String buildKaratePrompt(String swaggerContent, String endpoint,
                        String method, String context) {
                return """
                                Write a Karate DSL feature file for the following API endpoint.
                                Follow ISTQB test design techniques strictly.

                                Method: %s
                                Path: %s
                                Context: %s

                                OpenAPI spec (excerpt):
                                %s

                                Requirements:
                                - Use Karate DSL syntax (Feature, Background, Scenario)
                                - Background: set baseUrl and Content-Type header
                                - Include these scenarios (minimum):

                                  ## Fonksiyonel Testler:
                                  1. [SMOKE][P0_BLOCKER][EP] Happy path — geçerli eşdeğerlik sınıfı (200/201)
                                  2. [REGRESSION][P1_CRITICAL][EP] Farklı geçerli veri varyasyonları (en az 3 senaryo)
                                  3. [NEGATIVE][P1_CRITICAL][EP] Geçersiz eşdeğerlik sınıfı — validation error (400)
                                  4. [NEGATIVE][P1_CRITICAL][EG] Eksik zorunlu alan, boş body, malformed JSON
                                  5. [BOUNDARY][P2_MAJOR][BVA] Sınır değerleri — min/max uzunluk, sıfır, negatif
                                  6. [E2E][P1_CRITICAL][ST] Durum geçişi — create → read → update → delete akışı
                                  7. [NEGATIVE][P2_MAJOR][ST] Geçersiz durum geçişi — silinmiş kaynağı güncelleme (404)

                                  ## Fonksiyonel Olmayan Testler:
                                  8. [SECURITY][P0_BLOCKER][EG] Yetkisiz erişim — Authorization header eksik (401)
                                  9. [SECURITY][P1_CRITICAL][EG] SQL injection / XSS payload denemesi
                                  10. [PERFORMANCE][P2_MAJOR][BVA] Büyük payload ile response time kontrolü

                                - Use `match response.field == '#notnull'` for assertions
                                - Set `karate.configure('connectTimeout', 5000)` in Background
                                - Her senaryonun tag'ında ISTQB kategorisini belirt: @smoke, @regression, @negative, @boundary, @security, @performance

                                %s

                                Return ONLY the .feature file content. No explanation.
                                """
                                .formatted(method, endpoint, context, truncateSpec(swaggerContent), ISTQB_RULES);
        }

        // ─────────────────────────────────────────────────────────
        // SELENIUM (Frontend Web)
        // ─────────────────────────────────────────────────────────
        public static String buildSeleniumPrompt(String pageUrl, String userStory, String htmlHint) {
                return """
                                Write Selenium WebDriver Java tests using Page Object Model.
                                Follow ISTQB test design techniques strictly.

                                URL: %s
                                User story: %s
                                HTML hints / selectors: %s

                                Requirements:
                                - TWO classes: [PageName]Page.java and [TestName]Test.java
                                - Use JUnit 5 (@Test, @BeforeEach, @AfterEach)
                                - Use WebDriverWait for explicit waits
                                - @FindBy annotations in Page class
                                - ChromeOptions with headless support
                                - Read remote URL from: System.getenv("SELENIUM_REMOTE_URL")
                                - No package declaration. No custom base classes.

                                Test Methods (minimum — ISTQB etiketli):
                                  ## Fonksiyonel:
                                  1. smokeShouldLoadPage — [SMOKE][P0][EP] Geçerli sayfa yüklemesi
                                  2. regressionShouldCompleteMainFlow — [REGRESSION][P1][ST] Ana akış durum geçişi
                                  3. regressionShouldWorkWithVariousData — [REGRESSION][P1][EP] Farklı veri sınıfları
                                  4. negativeShouldRejectEmptyForm — [NEGATIVE][P1][BVA] Boş form gönderimi
                                  5. negativeShouldRejectInvalidInput — [NEGATIVE][P1][EP] Geçersiz input sınıfı
                                  6. boundaryShouldHandleMaxLength — [BOUNDARY][P2][BVA] Max karakter sınırı
                                  7. boundaryShouldHandleMinLength — [BOUNDARY][P2][BVA] Min karakter sınırı

                                  ## Fonksiyonel Olmayan:
                                  8. securityShouldPreventXssInjection — [SECURITY][P0][EG] XSS payload
                                  9. usabilityShouldShowErrorMessages — [USABILITY][P2][EG] Hata mesajı görünürlüğü
                                  10. performanceShouldLoadWithinThreshold — [PERFORMANCE][P2][BVA] Sayfa yükleme süresi < 3s

                                %s

                                Return ONLY Java code. No explanation.
                                """
                                .formatted(pageUrl, userStory,
                                                htmlHint != null ? htmlHint : "not provided", ISTQB_RULES);
        }

        // ─────────────────────────────────────────────────────────
        // APPIUM (Mobile)
        // ─────────────────────────────────────────────────────────
        public static String buildAppiumPrompt(String appPackage, String userStory,
                        String platform, String additionalContext) {
                return """
                                Write Appium Java tests using Page Object Model.
                                Follow ISTQB test design techniques strictly.

                                Platform: %s
                                App Package: %s
                                User story: %s
                                Context: %s

                                Requirements:
                                - TWO classes: [ScreenName]Screen.java and [TestName]AppiumTest.java
                                - Use JUnit 5 (@Test, @BeforeEach, @AfterEach)
                                - UiAutomator2Options for Android, XCUITestOptions for iOS
                                - Use AppiumBy for element lookup
                                - Read server URL from: System.getenv("APPIUM_SERVER_URL")
                                - No package declaration. No custom base classes.

                                Test Methods (minimum — ISTQB etiketli):
                                  ## Fonksiyonel:
                                  1. smokeShouldOpenScreen — [SMOKE][P0][EP] Ekran açılma doğrulaması
                                  2. regressionShouldCompleteFlow — [REGRESSION][P1][ST] Ana akış durum geçişi
                                  3. regressionShouldWorkWithVariousInputs — [REGRESSION][P1][EP] Farklı girdi varyasyonları
                                  4. negativeShouldRejectInvalidInput — [NEGATIVE][P1][EP] Geçersiz girdi sınıfı
                                  5. boundaryShouldHandleEdgeState — [BOUNDARY][P2][BVA] Sınır durumları
                                  6. e2eShouldCompleteUserJourney — [E2E][P1][ST] Tam kullanıcı yolculuğu

                                  ## Fonksiyonel Olmayan:
                                  7. securityShouldHandleSessionTimeout — [SECURITY][P1][ST] Oturum zaman aşımı
                                  8. usabilityShouldSupportRotation — [USABILITY][P3][EG] Ekran döndürme
                                  9. reliabilityShouldRecoverFromBackground — [RELIABILITY][P2][ST] Arka plandan dönüş
                                  10. performanceShouldRespondQuickly — [PERFORMANCE][P2][BVA] Yanıt süresi kontrolü

                                %s

                                Return ONLY Java code. No explanation.
                                """
                                .formatted(platform, appPackage, userStory,
                                                additionalContext != null ? additionalContext : "", ISTQB_RULES);
        }

        // ─────────────────────────────────────────────────────────
        // USER STORY → Test Case (Genel)
        // ─────────────────────────────────────────────────────────
        public static String buildUserStoryPrompt(String userStory, String framework, String context) {
                return """
                                Write %s test cases for the following user story.
                                Follow ISTQB test design techniques strictly.

                                User story: %s
                                Context: %s

                                Generate scenarios covering ALL of the following ISTQB categories:

                                ## Fonksiyonel Test Senaryoları:
                                - [SMOKE][P0][EP] Happy path — geçerli eşdeğerlik sınıfı ile ana akış
                                - [REGRESSION][P1][EP] En az 3 farklı geçerli veri varyasyonu
                                - [NEGATIVE][P1][EP] Geçersiz eşdeğerlik sınıfı senaryoları
                                - [NEGATIVE][P1][EG] Yaygın hata durumları (null, empty, duplicate)
                                - [BOUNDARY][P2][BVA] Sınır değerleri (min, min+1, max-1, max)
                                - [E2E][P1][ST] Uçtan uca durum geçişi akışı
                                - [ACCEPTANCE][P1][DT] Karar tablosu kombinasyonları

                                ## Fonksiyonel Olmayan Test Senaryoları:
                                - [SECURITY][P0][EG] Yetki kontrolü ve injection denemesi
                                - [PERFORMANCE][P2][BVA] Yanıt süresi ve timeout kontrolü
                                - [USABILITY][P3][EG] Hata mesajı görünürlüğü ve kullanıcı geri bildirimi

                                %s

                                Return ONLY code. No explanation.
                                """.formatted(framework, userStory, context != null ? context : "", ISTQB_RULES);
        }

}

        private PromptTemplates() {
        }

        /**
         * Swagger spec'i 3000 karakterde keser — küçük modeller için context koruması.
         */
        private static String truncateSpec(String spec) {
                if (spec == null)
                        return "";
                return spec.length() > 3000 ? spec.substring(0, 3000) + "\n... [spec kısaltıldı]" : spec;
        }

        // ─────────────────────────────────────────────────────────
        // KARATE (Backend API)
        // ─────────────────────────────────────────────────────────
        public static String buildKaratePrompt(String swaggerContent, String endpoint,
                        String method, String context) {
                return """
                                Write a Karate DSL feature file for the following API endpoint.

                                Method: %s
                                Path: %s
                                Context: %s

                                OpenAPI spec (excerpt):
                                %s

                                Requirements:
                                - Use Karate DSL syntax (Feature, Background, Scenario)
                                - Background: set baseUrl and Content-Type header
                                - Include these scenarios:
                                  1. [SMOKE] Happy path (200/201)
                                  2. [NEGATIVE] Validation error (400)
                                  3. [SECURITY] Unauthorized (401)
                                  4. [EDGE] Not found or boundary value (404 or edge input)
                                - Use `match response.field == '#notnull'` for assertions
                                - Set `karate.configure('connectTimeout', 5000)` in Background

                                Return ONLY the .feature file content. No explanation.
                                """.formatted(method, endpoint, context, truncateSpec(swaggerContent));
        }

        // ─────────────────────────────────────────────────────────
        // SELENIUM (Frontend Web)
        // ─────────────────────────────────────────────────────────
        public static String buildSeleniumPrompt(String pageUrl, String userStory, String htmlHint) {
                return """
                                Write a Selenium WebDriver Java test using Page Object Model.

                                URL: %s
                                User story: %s
                                HTML hints / selectors: %s

                                Requirements:
                                - TWO classes: [PageName]Page.java and [TestName]Test.java
                                - Use JUnit 5 (@Test, @BeforeEach, @AfterEach)
                                - Use WebDriverWait for explicit waits
                                - @FindBy annotations in Page class
                                - ChromeOptions with headless support
                                - Read remote URL from: System.getenv("SELENIUM_REMOTE_URL")
                                - Include these test methods:
                                  1. smokeShouldLoadPage
                                  2. regressionShouldCompleteMainFlow
                                  3. negativeShouldRejectInvalidInput
                                  4. edgeShouldHandleBoundaryInput
                                - No package declaration. No custom base classes.

                                Return ONLY Java code. No explanation.
                                """.formatted(pageUrl, userStory, htmlHint != null ? htmlHint : "not provided");
        }

        // ─────────────────────────────────────────────────────────
        // APPIUM (Mobile)
        // ─────────────────────────────────────────────────────────
        public static String buildAppiumPrompt(String appPackage, String userStory,
                        String platform, String additionalContext) {
                return """
                                Write an Appium Java test using Page Object Model.

                                Platform: %s
                                App Package: %s
                                User story: %s
                                Context: %s

                                Requirements:
                                - TWO classes: [ScreenName]Screen.java and [TestName]AppiumTest.java
                                - Use JUnit 5 (@Test, @BeforeEach, @AfterEach)
                                - UiAutomator2Options for Android, XCUITestOptions for iOS
                                - Use AppiumBy for element lookup
                                - Read server URL from: System.getenv("APPIUM_SERVER_URL")
                                - Include these test methods:
                                  1. smokeShouldOpenScreen
                                  2. regressionShouldCompleteFlow
                                  3. negativeShouldRejectInvalidInput
                                  4. edgeShouldHandleBoundaryState
                                - No package declaration. No custom base classes.

                                Return ONLY Java code. No explanation.
                                """.formatted(platform, appPackage, userStory,
                                additionalContext != null ? additionalContext : "");
        }

        // ─────────────────────────────────────────────────────────
        // USER STORY → Test Case (Genel)
        // ─────────────────────────────────────────────────────────
        public static String buildUserStoryPrompt(String userStory, String framework, String context) {
                return """
                                Write %s test cases for the following user story.

                                User story: %s
                                Context: %s

                                Include:
                                - Smoke test (happy path)
                                - Regression test (core flows with data variations)
                                - Negative test (invalid input, error messages)
                                - Edge/boundary test (empty, null, max values)

                                Label each scenario: [SMOKE], [REGRESSION], [NEGATIVE], [EDGE]

                                Return ONLY code. No explanation.
                                """.formatted(framework, userStory, context != null ? context : "");
        }
}
