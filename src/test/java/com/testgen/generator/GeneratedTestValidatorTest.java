package com.testgen.generator;

import com.testgen.model.TestFramework;
import com.testgen.model.ValidationStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Doğrulama kapısı, bu oturumda CANLI ölçülen bozuk üretim çıktıları üzerinden test edilir.
 * Amaç: bu hatalar koşumda "0/0 FAILED" olarak değil, üretim anında yakalansın.
 */
public class GeneratedTestValidatorTest {

    private final GeneratedTestValidator validator = new GeneratedTestValidator();

    // ── Karate ───────────────────────────────────────────────

    @Test
    public void validFeaturePasses() {
        String feature = """
                @testCaseLLM
                Feature: Sağlık Kontrolü

                  Scenario: Health endpoint UP dönmeli
                    Given url 'http://localhost:8080/api/v1/tests/health'
                    When method get
                    Then status 200
                """;

        var result = validator.validate(TestFramework.KARATE, "HealthTest.feature", feature);

        assertEquals(ValidationStatus.VALID, result.status(), result.error());
        assertNull(result.error());
    }

    @Test
    public void featureWithTrailingProseIsRejected() {
        // Canlıda ölçüldü: self-healing çıktısına açıklama paragrafı eklenmişti →
        // "mismatched input 'B' expecting <EOF>"
        String feature = """
                Feature: X
                  Scenario: s
                    Then status 200

                Bu düzeltilmiş kodda response.length yerine #arrayLength kullanıyoruz.
                """;

        var result = validator.validate(TestFramework.KARATE, "X.feature", feature);

        assertEquals(ValidationStatus.INVALID, result.status());
        assertNotNull(result.error());
    }

    @Test
    public void featureWithoutScenarioIsRejected() {
        var result = validator.validate(TestFramework.KARATE, "X.feature", "Feature: sadece baslik\n");
        assertEquals(ValidationStatus.INVALID, result.status());
    }

    @Test
    public void emptyContentIsRejectedForEveryFramework() {
        for (TestFramework fw : TestFramework.values()) {
            assertEquals(ValidationStatus.INVALID,
                    validator.validate(fw, "X", "   ").status(), fw.name());
        }
    }

    // ── Java ─────────────────────────────────────────────────

    @Test
    public void validRestAssuredClassPasses() {
        String java = """
                package com.testgen.generated;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                public class HealthCheckTest {
                    @Test
                    public void healthUp() {
                        assertTrue(true);
                    }
                }
                """;

        var result = validator.validate(TestFramework.REST_ASSURED, "HealthCheckTest.java", java);

        assertNotEquals(ValidationStatus.INVALID, result.status(), result.error());
    }

    @Test
    public void javaWithMissingImportIsRejected() {
        // Canlıda ölçüldü: "symbol: class List" — java.util import'u yok
        String java = """
                package com.testgen.generated;

                import org.junit.jupiter.api.Test;

                public class LoadTest {
                    @Test
                    public void load() {
                        List<String> hedefler = new ArrayList<>();
                        hedefler.add("x");
                    }
                }
                """;

        var result = validator.validate(TestFramework.REST_ASSURED, "LoadTest.java", java);

        assertEquals(ValidationStatus.INVALID, result.status());
        assertTrue(result.error().contains("List") || result.error().contains("symbol"), result.error());
    }

    @Test
    public void javaWithoutClassDeclarationIsRejected() {
        // Canlıda ölçüldü: "(use -source 25 or higher to enable implicitly declared classes)"
        String java = """
                package com.testgen.generated;

                @Test
                public void sadeceMetot() {
                    int x = 1;
                }
                """;

        var result = validator.validate(TestFramework.REST_ASSURED, "GeneratedApiTest.java", java);

        assertEquals(ValidationStatus.INVALID, result.status());
    }

    @Test
    public void seleniumTestCompilesAgainstDriverFactory() {
        // Üretilen Selenium testleri DriverFactory'yi kullanır; doğrulayıcı onu da derlemeli
        String java = """
                package com.testgen.generated;

                import org.junit.jupiter.api.AfterEach;
                import org.junit.jupiter.api.BeforeEach;
                import org.junit.jupiter.api.Test;
                import org.openqa.selenium.WebDriver;

                import static org.junit.jupiter.api.Assertions.assertNotNull;

                public class ObservedSmokeTest {
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
                    public void sayfaAcilmali() {
                        driver.get("http://localhost:8080");
                        assertNotNull(driver.getTitle());
                    }
                }
                """;

        var result = validator.validate(TestFramework.SELENIUM, "ObservedSmokeTest.java", java);

        assertNotEquals(ValidationStatus.INVALID, result.status(), result.error());
    }

    @Test
    public void seleniumTestUsingUndefinedPageObjectIsRejected() {
        // Canlıda ölçüldü: "symbol: class DashboardPage"
        String java = """
                package com.testgen.generated;

                import org.junit.jupiter.api.Test;

                public class DashboardTest {
                    @Test
                    public void t() {
                        DashboardPage page = new DashboardPage();
                        page.open();
                    }
                }
                """;

        var result = validator.validate(TestFramework.SELENIUM, "DashboardTest.java", java);

        assertEquals(ValidationStatus.INVALID, result.status());
        assertTrue(result.error().contains("DashboardPage"), result.error());
    }

    @Test
    public void pageObjectSuppliedAsSupportSourceMakesTestValid() {
        // Canlida olculdu: LLM hem testi hem DashboardPage'i uretiyor. Destek sinifi
        // derlemeye katilmazsa test HAKSIZ yere INVALID sayiliyordu.
        String page = """
                package com.testgen.generated;

                public class DashboardPage {
                    public void loadPage() { }
                }
                """;
        String test = """
                package com.testgen.generated;

                import org.junit.jupiter.api.Test;

                public class BoundaryTest {
                    @Test
                    public void t() {
                        DashboardPage page = new DashboardPage();
                        page.loadPage();
                    }
                }
                """;

        var withoutSupport = validator.validate(TestFramework.SELENIUM, "BoundaryTest.java", test);
        assertEquals(ValidationStatus.INVALID, withoutSupport.status());

        var withSupport = validator.validate(TestFramework.SELENIUM, "BoundaryTest.java", test,
                java.util.List.of(new GeneratedTestValidator.SupportSource("DashboardPage", page)));
        assertNotEquals(ValidationStatus.INVALID, withSupport.status(), withSupport.error());
    }
}
