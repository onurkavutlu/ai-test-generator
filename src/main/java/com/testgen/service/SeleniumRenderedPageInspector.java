package com.testgen.service;

import com.testgen.config.OutboundUrlGuard;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * İzole bir headless tarayıcı ile render edilmiş DOM'u keşfeder.
 *
 * <p>Normal DOM incelemesi salt-okunurdur. Açıkça etkinleştirilen kullanıcı akışı
 * keşfi ise yalnız kullanıcının tarifinde geçen, aynı-origin, form dışı ve yan
 * etkisiz görünen linkleri tıklar. Form doldurmaz, cookie kabul etmez ve DOM'dan
 * input değeri toplamaz. Tarayıcı kullanımı kapalıysa veya başarısızsa çağıran
 * katman ham HTML gözlemine güvenerek üretime devam eder.</p>
 */
@Slf4j
@Service
public class SeleniumRenderedPageInspector implements RenderedPageInspector {

    private static final int ELEMENT_LIMIT = 25;

    private final OutboundUrlGuard urlGuard;

    @Value("${test-generator.observation.rendered-dom.enabled:false}")
    private boolean enabled;

    @Value("${test-generator.observation.rendered-dom.timeout-seconds:15}")
    private long timeoutSeconds;

    @Value("${test-generator.observation.flow-discovery.enabled:false}")
    private boolean flowDiscoveryEnabled;

    @Value("${test-generator.observation.flow-discovery.max-steps:3}")
    private int flowDiscoveryMaxSteps;

    @Value("${test-generator.selenium.remote-url:}")
    private String remoteUrl;

    public SeleniumRenderedPageInspector(OutboundUrlGuard urlGuard) {
        this.urlGuard = urlGuard;
    }

    @Override
    public Optional<RenderedPageObservation> inspect(String url) {
        if (!enabled || url == null || url.isBlank()) {
            return Optional.empty();
        }

        // İlk hedef ve yönlendirme sonrası hedef ayrı ayrı denetlenir. Tarayıcı bir
        // sandbox/Grid içinde çalıştırılmalıdır; bu kontrol uygulama katmanındaki
        // yanlış hedefleri de anlamlı bir hata yerine güvenli bir fallback'e çevirir.
        urlGuard.verify(url);
        WebDriver driver = null;
        try {
            driver = createDriver();
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(timeoutSeconds));
            driver.get(url);
            urlGuard.verify(URI.create(driver.getCurrentUrl()));

            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(timeoutSeconds));
            wait.until(ExpectedConditions.presenceOfElementLocated(org.openqa.selenium.By.tagName("body")));
            wait.until(d -> "complete".equals(((JavascriptExecutor) d)
                    .executeScript("return document.readyState")));

            return Optional.of(readContract(driver));
        } catch (Exception e) {
            log.warn("Render edilmiş DOM gözlemi yapılamadı (ham HTML ile devam edilecek): {}", e.getMessage());
            return Optional.empty();
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                } catch (RuntimeException e) {
                    log.debug("Gözlem tarayıcısı kapatılamadı: {}", e.getMessage());
                }
            }
        }
    }

    private WebDriver createDriver() throws MalformedURLException {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--incognito", "--no-sandbox", "--disable-dev-shm-usage",
                "--disable-extensions", "--disable-background-networking", "--window-size=1440,1000");
        if (remoteUrl != null && !remoteUrl.isBlank()) {
            return new RemoteWebDriver(new URL(remoteUrl), options);
        }
        io.github.bonigarcia.wdm.WebDriverManager.chromedriver().setup();
        return new ChromeDriver(options);
    }

    @SuppressWarnings("unchecked")
    private RenderedPageObservation readContract(WebDriver driver) {
        Map<String, Object> data = (Map<String, Object>) ((JavascriptExecutor) driver).executeScript("""
                const safe = value => /^[A-Za-z0-9_.:-]+$/.test(value || '') ? value : '';
                const compact = value => (value || '').replace(/\\s+/g, ' ').trim().slice(0, 80);
                const visible = element => {
                  const style = getComputedStyle(element);
                  const rect = element.getBoundingClientRect();
                  return !!(rect.width || rect.height) && style.display !== 'none' && style.visibility !== 'hidden';
                };
                const label = element => compact(element.getAttribute('aria-label')
                  || Array.from(element.labels || []).map(item => item.innerText || item.textContent).join(' ')
                  || element.innerText || element.textContent);
                const locator = element => {
                  for (const key of ['data-testid', 'id', 'name']) {
                    const value = safe(element.getAttribute(key));
                    if (value) return { kind: key, value };
                  }
                  return null;
                };
                const elements = Array.from(document.querySelectorAll('input, button, select, textarea, a, [role="button"]'))
                  .filter(visible)
                  .map(element => ({ element, locator: locator(element) }))
                  .filter(item => item.locator)
                  .slice(0, %d)
                  .map(item => ({
                    tag: item.element.tagName.toLowerCase(),
                    locatorKind: item.locator.kind,
                    locatorValue: item.locator.value,
                    label: label(item.element),
                    type: compact(item.element.getAttribute('type')),
                    required: item.element.required === true
                  }));
                return { title: compact(document.title), finalUrl: location.href, elements };
                """.formatted(ELEMENT_LIMIT));

        List<UiElement> elements = new ArrayList<>();
        Object rawElements = data.get("elements");
        if (rawElements instanceof List<?> list) {
            for (Object raw : list) {
                if (!(raw instanceof Map<?, ?> element)) {
                    continue;
                }
                elements.add(new UiElement(string(element.get("tag")), string(element.get("locatorKind")),
                        string(element.get("locatorValue")), string(element.get("label")),
                        string(element.get("type")), Boolean.TRUE.equals(element.get("required"))));
            }
        }
        return new RenderedPageObservation(string(data.get("title")), string(data.get("finalUrl")), elements);
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String compact(String value, int limit) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "…";
    }

    /**
     * Kullanıcının metninde geçen gerçek linkleri sırasıyla yürütür. Bu bir crawler
     * değildir: yalnız aynı origin'deki menü/navigasyon veya JavaScript görünüm
     * açıcı linklere izin verir; başvuru, giriş, ödeme, sepet ve form akışları
     * kesinlikle keşif dışında bırakılır.
     */
    @Override
    public Optional<UserFlowObservation> inspectUserFlow(String url, String userStory) {
        if (!enabled || !flowDiscoveryEnabled || url == null || url.isBlank()
                || userStory == null || userStory.isBlank()) {
            return Optional.empty();
        }
        urlGuard.verify(url);
        WebDriver driver = null;
        try {
            driver = createDriver();
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(timeoutSeconds));
            driver.get(url);
            waitForDocument(driver);

            String normalizedIntent = normalize(userStory);
            String origin = originOf(driver.getCurrentUrl());
            List<FlowStep> steps = new ArrayList<>();
            Set<String> used = new HashSet<>();
            for (int number = 1; number <= Math.max(1, flowDiscoveryMaxSteps); number++) {
                FlowCandidate candidate = nextRequestedSafeLink(driver, normalizedIntent, origin, used);
                if (candidate == null) {
                    break;
                }
                String before = visibleSignature(driver);
                candidate.element().click();
                if (!waitForObservedChange(driver, before)) {
                    // Etkileşimden sonra URL veya görünür DOM değişmediyse sonucu
                    // kanıtlayamayız; bu adımı LLM'e delil olarak vermiyoruz.
                    log.info("Kullanıcı akışı adımında doğrulanabilir değişim yok: {}", candidate.label());
                    break;
                }
                urlGuard.verify(URI.create(driver.getCurrentUrl()));
                used.add(candidate.identity());
                steps.add(new FlowStep(number, "tıkla: " + candidate.label(), candidate.locator(),
                        "URL=" + driver.getCurrentUrl() + "; " + firstVisibleHeading(driver)));
            }
            if (steps.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new UserFlowObservation(driver.getCurrentUrl(), driver.getTitle(), steps,
                    visibleFacts(driver)));
        } catch (Exception e) {
            log.warn("Kullanıcı akışı keşfi tamamlanamadı (başlangıç sayfası gözlemi korunacak): {}", e.getMessage());
            return Optional.empty();
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                } catch (RuntimeException ignored) {
                    // Gözlem tarayıcısı kapanış hatası ana üretim akışını etkilemez.
                }
            }
        }
    }

    private void waitForDocument(WebDriver driver) {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(timeoutSeconds));
        wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")));
        wait.until(d -> "complete".equals(((JavascriptExecutor) d).executeScript("return document.readyState")));
    }

    private boolean waitForObservedChange(WebDriver driver, String before) {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(Math.min(timeoutSeconds, 5))).until(
                    d -> !before.equals(visibleSignature(d)));
            return true;
        } catch (TimeoutException ignored) {
            return false;
        }
    }

    private FlowCandidate nextRequestedSafeLink(WebDriver driver, String normalizedIntent,
                                                String origin, Set<String> used) {
        return driver.findElements(By.cssSelector("a"))
                .stream()
                .filter(WebElement::isDisplayed)
                .map(link -> candidateFor(link, normalizedIntent, origin))
                .flatMap(Optional::stream)
                .filter(candidate -> !used.contains(candidate.identity()))
                .max(Comparator.comparingInt(FlowCandidate::score))
                .orElse(null);
    }

    private Optional<FlowCandidate> candidateFor(WebElement link, String normalizedIntent, String origin) {
        String label = compact(firstNonBlank(link.getAttribute("aria-label"), link.getText()), 120);
        String normalizedLabel = normalize(label);
        if (normalizedLabel.length() < 3 || !normalizedIntent.contains(normalizedLabel)
                || !isSafeFlowLink(link, origin, normalizedLabel)) {
            return Optional.empty();
        }
        String cardText = closestCardText(link);
        int score = normalizedLabel.length();
        if (!cardText.isBlank()) {
            for (String token : normalize(cardText).split(" ")) {
                if (token.length() >= 3 && normalizedIntent.contains(token)) score += token.length();
            }
        }
        String locator = !cardText.isBlank()
                ? "visible .tarife-card containing '" + compact(cardText, 100) + "' -> link text '" + label + "'"
                : locatorFor(link, label);
        return Optional.of(new FlowCandidate(link, label, locator, score, locator));
    }

    private String locatorFor(WebElement link, String label) {
        String ariaLabel = link.getAttribute("aria-label");
        return ariaLabel != null && !ariaLabel.isBlank()
                ? "a[aria-label='" + compact(ariaLabel, 120) + "']"
                : "visible link text '" + label + "'";
    }

    private boolean isSafeFlowLink(WebElement link, String origin, String normalizedLabel) {
        if (normalizedLabel.matches(".*(basvur|basvuru|satin al|odeme|giris|login|sepet|tobi|sohbet).*")) {
            return false;
        }
        String href = firstNonBlank(link.getAttribute("href"), "").trim();
        if (href.startsWith("javascript:")) {
            // JavaScript bağlantıları yalnız ayrıntı açma/kapatma gibi yerel görünüm
            // değişimleri için kabul edilir; başvuru vb. etiketler yukarıda elenir.
            return normalizedLabel.contains("detay");
        }
        try {
            URI destination = URI.create(href);
            return "http".equalsIgnoreCase(destination.getScheme())
                    || "https".equalsIgnoreCase(destination.getScheme())
                    ? origin.equals(originOf(href)) : href.startsWith("/");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String closestCardText(WebElement link) {
        Object card = ((JavascriptExecutor) link).executeScript("return arguments[0].closest('.tarife-card')", link);
        return card instanceof WebElement element ? compact(element.getText(), 180) : "";
    }

    private String visibleSignature(WebDriver driver) {
        return driver.getCurrentUrl() + "|" + String.join("|", visibleFacts(driver));
    }

    private List<String> visibleFacts(WebDriver driver) {
        return driver.findElements(By.cssSelector("h1,h2,h3,a"))
                .stream().filter(WebElement::isDisplayed).map(WebElement::getText)
                .map(value -> compact(value, 120)).filter(value -> !value.isBlank())
                .distinct().limit(12).toList();
    }

    private String firstVisibleHeading(WebDriver driver) {
        return driver.findElements(By.cssSelector("h1,h2,h3")).stream()
                .filter(WebElement::isDisplayed).map(WebElement::getText)
                .map(value -> compact(value, 120)).filter(value -> !value.isBlank())
                .findFirst().orElse("görünür başlık ölçülmedi");
    }

    private static String originOf(String url) {
        URI uri = URI.create(url);
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
    }

    private static String normalize(String value) {
        return compact(value, 2_000).toLowerCase(Locale.forLanguageTag("tr-TR"))
                .replaceAll("[^\\p{L}\\p{N}]+", " ").replaceAll("\\s+", " ").trim();
    }

    private static String firstNonBlank(String first, String fallback) {
        return first != null && !first.isBlank() ? first : fallback;
    }

    private record FlowCandidate(WebElement element, String label, String locator, int score, String identity) { }
}
