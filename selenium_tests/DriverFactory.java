import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;

/** Test kodu tarafından kullanılır — elle düzenlemeyin, her koşumda yeniden yazılır. */
public final class DriverFactory {

    private DriverFactory() {
    }

    public static WebDriver createDriver() {
        ChromeOptions options = new ChromeOptions();
        if (!"false".equalsIgnoreCase(System.getenv("SELENIUM_HEADLESS"))) {
            options.addArguments("--headless=new");
        }
        options.addArguments("--no-sandbox", "--disable-dev-shm-usage", "--window-size=1920,1080");

        String remoteUrl = System.getenv("SELENIUM_REMOTE_URL");
        WebDriver driver;
        if (remoteUrl != null && !remoteUrl.isBlank()) {
            try {
                driver = new RemoteWebDriver(new URL(remoteUrl), options);
            } catch (MalformedURLException e) {
                throw new IllegalStateException("SELENIUM_REMOTE_URL gecersiz: " + remoteUrl, e);
            }
        } else {
            io.github.bonigarcia.wdm.WebDriverManager.chromedriver().setup();
            driver = new org.openqa.selenium.chrome.ChromeDriver(options);
        }

        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
        return driver;
    }
}
