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

        /** Bağlam bütçesi — ölçüme göre seçildi, bkz. {@link #boundContext}. */
        private static final int CONTEXT_BUDGET = 6000;

        /**
         * additionalContext'i bütçeye sığdırır; GÖZLEM bölümlerini korur.
         *
         * ÖLÇÜLEN SORUN: Bu bağlam ajan analizleriyle birlikte 25.000 karaktere kadar
         * büyüyor ve HER ENDPOINT çağrısında yeniden gönderiliyordu. Prompt'lar 34.000
         * karaktere çıkınca çağrı başına ~40 saniyeye ulaştı; 5 case'lik bir modül
         * 8-10 dakika sürüyordu. Prompt'un uzunluğu doğrudan maliyettir.
         *
         * Kesme sırası bilinçli: "## OBSERVED" bölümleri hedeften CANLI okunan gerçek
         * veridir ve üretim kalitesini en çok belirleyen kısımdır — onlar tam korunur,
         * kısaltma ajan analizlerinden yapılır.
         */
        public static String boundContext(String context) {
                if (context == null || context.length() <= CONTEXT_BUDGET) {
                        return context == null ? "" : context;
                }

                int observedAt = context.indexOf("## OBSERVED");
                if (observedAt < 0) {
                        return context.substring(0, CONTEXT_BUDGET) + "\n... [bağlam kısaltıldı]";
                }

                String observed = context.substring(observedAt);
                if (observed.length() >= CONTEXT_BUDGET) {
                        // Gözlem tek başına bütçeyi doldurusa yalnızca onu gönder
                        return observed.substring(0, CONTEXT_BUDGET) + "\n... [gözlem kısaltıldı]";
                }

                int headBudget = CONTEXT_BUDGET - observed.length();
                return context.substring(0, Math.min(headBudget, observedAt))
                                + "\n... [ajan analizi kısaltıldı]\n\n"
                                + observed;
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
                                - SYNTAX RULES (VIOLATING THESE MAKES THE WHOLE FEATURE UNRUNNABLE):
                                  * A header step REQUIRES a name:  `* header Accept = 'application/json'`
                                  * For several headers use the PLURAL map form: `* headers { Accept: 'application/json' }`
                                  * NEVER write `* header = { ... }` — Karate has no such step and every
                                    scenario in the file will fail with "no step-definition method match found".
                                  * Value-taking steps take NO `=`: write `* url 'http://host'`, `* path '/x'`,
                                    `* method get`, `* status 200` — never `* url = '...'`.
                                  * Variables need `def`: `* def baseUrl = 'http://host'`.
                                  * The response object is `response` — there is NO `response.body`.
                                    Assert with `match response ...` / `match response.field == '#notnull'`.
                                - Background: set baseUrl with `* def baseUrl = '...'`; add a Content-Type
                                  header only when the request actually sends a body
                                - APPLICABILITY RULE (CRITICAL): The scenario lists below are TEMPLATES.
                                  Only generate scenarios that genuinely apply to the target.
                                  If the Context explicitly states something does not exist or must not be tested
                                  (e.g. "auth YOK", "400 senaryosu YOKTUR", "form YOK"), OMIT those scenarios entirely.
                                  A passing, applicable subset is better than a failing, complete template.
                                - EVIDENCE RULE (CRITICAL): Never invent an expected status code. Assert a status
                                  only when the OpenAPI excerpt declares it or the Context states it. If the spec
                                  declares only 200, do NOT write 400/401/404/500 scenarios for that endpoint —
                                  omit them. An endpoint with no documented auth has no 401 scenario.
                                - Include these scenarios (only the applicable ones):

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
                                - Her senaryonun tag'ında ISTQB kategorisini belirt: @smoke, @regression, @negative, @boundary, @security, @performance. Ayrıca yapay zeka üretimi olduğunu belirtmek için ek olarak mutlaka @testCaseLLM tag'ini ekle.

                                %s

                                Return ONLY the .feature file content. No explanation.
                                """
                                .formatted(method, endpoint, boundContext(context), truncateSpec(swaggerContent), ISTQB_RULES);
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
                                - MUST start with exactly: package com.testgen.generated;
                                - MUST include these exact imports:
                                  import org.openqa.selenium.WebDriver;
                                  import org.openqa.selenium.WebElement;
                                  import org.openqa.selenium.By;
                                  import org.openqa.selenium.support.ui.WebDriverWait;
                                  import org.openqa.selenium.support.ui.ExpectedConditions;
                                  import java.time.Duration;
                                - Use JUnit 5 (@Test, @BeforeEach, @AfterEach)
                                - WARNING (SELENIUM 4 SYNTAX): ALWAYS use `Duration.ofSeconds(...)` for timeouts. NEVER pass an integer directly to WebDriverWait. (e.g. `new WebDriverWait(driver, Duration.ofSeconds(10))`)
                                - Driver setup: ONLY use `driver = DriverFactory.createDriver();` in @BeforeEach.
                                  (DriverFactory is already provided in the same package — do NOT define it or import it)
                                - Always call driver.quit() in @AfterEach
                                - No custom base classes.
                                - PAGE OBJECT CONTRACT (CRITICAL — VIOLATING THIS MAKES EVERY TEST FAIL WITH NullPointerException):
                                  * The page class MUST take the driver in its constructor:
                                    `public DashboardPage(WebDriver driver) { this.driver = driver; }`
                                  * The test MUST pass its own driver when creating it: `new DashboardPage(driver)`.
                                  * NEVER write `new DashboardPage()` — the page's driver field stays null and
                                    every call fails with "this.driver is null".
                                  * The page class must NOT create its own driver.

                                - APPLICABILITY RULE (CRITICAL): The scenario lists below are TEMPLATES.
                                  Only generate scenarios that genuinely apply to the target.
                                  If the Context explicitly states something does not exist or must not be tested
                                  (e.g. "auth YOK", "400 senaryosu YOKTUR", "form YOK"), OMIT those scenarios entirely.
                                  A passing, applicable subset is better than a failing, complete template.
                                - EVIDENCE RULE (CRITICAL): Use ONLY the selectors and texts given in the HTML hints.
                                  Never invent an element id, class, or expected label. If the hints show no login
                                  form, do not write a login test; if they show no error banner, do not assert one.
                                Test Methods (only the applicable ones — ISTQB etiketli):
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
                                                htmlHint != null ? boundContext(htmlHint) : "not provided", ISTQB_RULES);
        }



        // ─────────────────────────────────────────────────────────
        // REST ASSURED (Backend API)
        // ─────────────────────────────────────────────────────────
        public static String buildRestAssuredPrompt(String swaggerContent, String endpoint,
                        String method, String context) {
                return """
                                Write a REST Assured Java test class using JUnit 5 for the following API endpoint.
                                Follow ISTQB test design techniques strictly.

                                Method: %s
                                Path: %s
                                Context: %s

                                OpenAPI spec (excerpt):
                                %s

                                Requirements:
                                - MUST start with exactly: package com.testgen.generated;
                                - MUST include all required imports (e.g. io.restassured.RestAssured, org.junit.jupiter.api.*, org.hamcrest.Matchers.*).
                                - API RULES (VIOLATING THESE BREAKS COMPILATION AND KILLS EVERY TEST IN THE CLASS):
                                  * Matchers live in `org.hamcrest.Matchers` — there is NO `io.restassured.matcher.Matchers`.
                                  * Do NOT import symbols you never use.
                                  * There is NO `timeLessThan(...)`. For response time write:
                                    `.then().time(lessThan(2000L))` with `import static org.hamcrest.Matchers.lessThan;`
                                  * Use only real REST Assured / Hamcrest methods. If unsure a method exists,
                                    assert with `.statusCode(...)` or `.body(...)` instead of inventing one.
                                - Class name must end with 'Test' (e.g. GeneratedApiTest).
                                - Use JUnit 5 (@Test, @BeforeEach, etc).
                                - Set RestAssured.baseURI and setup headers in @BeforeEach.
                                - Use BDD syntax: given().when().then().
                                - APPLICABILITY RULE (CRITICAL): The scenario lists below are TEMPLATES.
                                  Only generate scenarios that genuinely apply to the target.
                                  If the Context explicitly states something does not exist or must not be tested
                                  (e.g. "auth YOK", "400 senaryosu YOKTUR"), OMIT those scenarios entirely.
                                  A passing, applicable subset is better than a failing, complete template.
                                - EVIDENCE RULE (CRITICAL): Never invent an expected status code. Assert a status
                                  only when the OpenAPI excerpt declares it or the Context states it. If the spec
                                  declares only 200, do NOT write 400/401/404/500 scenarios for that endpoint —
                                  omit them. An endpoint with no documented auth has no 401 scenario.
                                - Include these scenarios (only the applicable ones):

                                  ## Fonksiyonel Testler:
                                  1. smokeHappyPath — [SMOKE][P0_BLOCKER][EP] Happy path (200/201)
                                  2. regressionValidVariations — [REGRESSION][P1_CRITICAL][EP] Different valid data
                                  3. negativeValidationError — [NEGATIVE][P1_CRITICAL][EP] Invalid equivalence class (400)
                                  4. negativeMissingFields — [NEGATIVE][P1_CRITICAL][EG] Missing required fields
                                  5. boundaryEdgeCases — [BOUNDARY][P2_MAJOR][BVA] Boundary values (min/max length)

                                  ## Fonksiyonel Olmayan Testler:
                                  6. securityUnauthorized — [SECURITY][P0_BLOCKER][EG] Missing auth header (401)
                                  7. performanceResponseTime — [PERFORMANCE][P2_MAJOR][BVA] Response time assertion

                                %s

                                Return ONLY Java code. No explanation.
                                """
                                .formatted(method, endpoint, boundContext(context), truncateSpec(swaggerContent), ISTQB_RULES);
        }

        // ─────────────────────────────────────────────────────────
        // CAPTURED RESPONSE → gözlem-temelli dar senaryo seti
        // ─────────────────────────────────────────────────────────
        /**
         * Canlı koşumda yakalanan gerçek yanıt için test üretimi.
         * Şablon senaryo listesi BİLEREK kullanılmaz: assertion'lar yalnızca
         * gözlenen davranışa yazılır — geçmeyen spekülatif senaryo üretilmez.
         */
        public static String buildCapturedResponsePrompt(String rawPayload, String context) {
                return """
                                Write a Karate DSL feature file for a request whose REAL response was captured live.
                                The context below contains an OBSERVED RESPONSE section — that is the ground truth.

                                Request (cURL):
                                %s

                                Context (includes OBSERVED RESPONSE):
                                %s

                                STRICT RULES — generate EXACTLY these three scenarios and NOTHING else:
                                  1. [SMOKE][P0_BLOCKER][EP] Send the exact request; assert the OBSERVED status code
                                     and match ONLY the fields/values visible in the OBSERVED body.
                                  2. [PERFORMANCE][P2_MAJOR][BVA] Send the same request; `Then assert responseTime < 5000`
                                  3. [NEGATIVE][P2_MAJOR][EG] Same base URL with path suffix '/nonexistent-path-xyz'
                                     appended; assert status 404.
                                - DO NOT invent 400/401/403/405 scenarios. DO NOT modify the request body or headers.
                                - DO NOT assert any field that is not present in the OBSERVED body.
                                - Background: set the base url and headers from the cURL. Add @testCaseLLM tags.
                                - Use VALID Karate syntax: `* url 'http://...'` (no equals sign, no def for url).

                                Return ONLY the .feature file content. No explanation.
                                """.formatted(rawPayload, context != null ? context : "");
        }

        // ─────────────────────────────────────────────────────────
        // RAW PAYLOAD (cURL / JSON / XML) → Karate Feature
        // ─────────────────────────────────────────────────────────
        public static String buildRawPayloadPrompt(String rawPayload, String payloadType, String context) {
                // Canlı yakalanan yanıt akışı: şablon senaryolar yerine gözlem-temelli dar set
                if ("CAPTURED".equalsIgnoreCase(payloadType)
                                || (context != null && context.contains("## OBSERVED RESPONSE"))) {
                        return buildCapturedResponsePrompt(rawPayload, context);
                }
                return """
                                Write a Karate DSL feature file based on the following raw payload.
                                The payload represents an API request (e.g. cURL, JSON body, or XML).
                                Follow ISTQB test design techniques strictly.

                                Payload Type: %s
                                Raw Payload:
                                %s

                                Context: %s

                                Requirements:
                                - Analyze the payload to determine the endpoint URL, method, headers, and body.
                                - Use Karate DSL syntax (Feature, Background, Scenario).
                                - Background: set baseUrl to the actual endpoint extracted from the payload, configure headers.
                                - Generate a test scenario that sends this exact request to the real endpoint and asserts a successful response (e.g., status 200/201).
                                - Generate negative/edge case scenarios based on the input structure (e.g., missing fields).
                                - APPLICABILITY RULE (CRITICAL): The scenario lists below are TEMPLATES.
                                  Only generate scenarios that genuinely apply to the target.
                                  If the Context explicitly states something does not exist or must not be tested
                                  (e.g. "auth YOK", "400 senaryosu YOKTUR", "form YOK"), OMIT those scenarios entirely.
                                  A passing, applicable subset is better than a failing, complete template.
                                - EVIDENCE RULE (CRITICAL): Never invent an expected status code. Assert a status
                                  only when the OpenAPI excerpt declares it or the Context states it. If the spec
                                  declares only 200, do NOT write 400/401/404/500 scenarios for that endpoint —
                                  omit them. An endpoint with no documented auth has no 401 scenario.
                                - Include these scenarios (only the applicable ones):
                                  1. [SMOKE][P0_BLOCKER][EP] Happy path — send the exact payload and verify success
                                  2. [REGRESSION][P1_CRITICAL][EP] Modify the payload with different valid variations
                                  3. [NEGATIVE][P1_CRITICAL][EG] Send missing/invalid fields and expect validation errors (400)
                                  4. [SECURITY][P0_BLOCKER][EG] Test without authorization headers (401)
                                - Her senaryoda yapay zeka üretimi olduğunu belirtmek için mutlaka @testCaseLLM tag'ini ekle.
                                
                                %s

                                Return ONLY the .feature file content. No explanation.
                                """
                                .formatted(payloadType, rawPayload, context != null ? context : "", ISTQB_RULES);
        }

        // ─────────────────────────────────────────────────────────
        // GRAPHQL
        // ─────────────────────────────────────────────────────────
        public static String buildGraphQLPrompt(String graphqlDetails, String context) {
                return """
                                Write a Karate DSL feature file based on the following GraphQL request.
                                Follow ISTQB test design techniques strictly.

                                Request Details:
                                %s

                                Context: %s

                                Requirements:
                                - Use Karate DSL syntax.
                                - Background: set baseUrl to the GraphQL endpoint (usually '/graphql').
                                - Request body must include 'query' and 'variables' correctly as JSON.
                                - Method must be POST.
                                - Generate a test scenario that asserts the response structure.
                                - Ensure to verify: `match response.data != null` and `match response.errors == '#notpresent'`.
                                - Generate at least one negative scenario (e.g. invalid query or missing variable).
                                - Her senaryoda yapay zeka üretimi olduğunu belirtmek için mutlaka @testCaseLLM tag'ini ekle.
                                
                                %s

                                Return ONLY the .feature file content. No explanation.
                                """
                                .formatted(graphqlDetails, context != null ? context : "", ISTQB_RULES);
        }

        // ─────────────────────────────────────────────────────────
        // SOAP
        // ─────────────────────────────────────────────────────────
        public static String buildSoapPrompt(String soapXml, String context) {
                return """
                                Write a Karate DSL feature file based on the following SOAP XML payload.
                                Follow ISTQB test design techniques strictly.

                                SOAP Payload:
                                %s

                                Context: %s

                                Requirements:
                                - Use Karate DSL syntax.
                                - Background: set baseUrl to the SOAP endpoint.
                                - Set header `Content-Type = 'text/xml;charset=UTF-8'`.
                                - Pass the SOAP Payload as the request body.
                                - Method must be POST.
                                - Generate a test scenario that asserts the response XML structure.
                                - Use Karate's XML validation features (e.g., `match response /Envelope/Body...`).
                                - Generate at least one negative scenario (e.g. invalid envelope or missing mandatory tag).
                                - Her senaryoda yapay zeka üretimi olduğunu belirtmek için mutlaka @testCaseLLM tag'ini ekle.
                                
                                %s

                                Return ONLY the .feature file content. No explanation.
                                """
                                .formatted(soapXml, context != null ? context : "", ISTQB_RULES);
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
                                - Her senaryoda yapay zeka üretimi olduğunu belirtmek için mutlaka @testCaseLLM tag'ini ekle.

                                %s

                                Return ONLY code. No explanation.
                                """.formatted(framework, userStory, context != null ? context : "", ISTQB_RULES);
        }

}


