package com.testgen.llm;

/**
 * LLM prompt şablonları — codellama:7b-instruct için optimize edilmiş.
 * Kısa, net, az token tüketen formatlar.
 */
public final class PromptTemplates {

    private PromptTemplates() {}

    /** Swagger spec'i 3000 karakterde keser — küçük modeller için context koruması. */
    private static String truncateSpec(String spec) {
        if (spec == null) return "";
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
