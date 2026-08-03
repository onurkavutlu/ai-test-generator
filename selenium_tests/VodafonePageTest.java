import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.TimeUnit;

public class VodafonePageTest {

    private WebDriver driver;

    @BeforeEach
    public void setup() {
        driver = DriverFactory.createDriver();
        driver.manage().timeouts().implicitlyWait(10, TimeUnit.SECONDS);
    }

    @AfterEach
    public void tearDown() {
        driver.quit();
    }

    @Test
    public void smokeShouldLoadPage() {
        VodafonePage page = new VodafonePage(driver);
        page.open();
        assertEquals("Vodafone", page.getTitle());
    }

    @Test
    public void regressionShouldCompleteMainFlow() {
        VodafonePage page = new VodafonePage(driver);
        page.open();
        page.login("username", "password");
        assertEquals("Dashboard", page.getTitle());
    }

    @Test
    public void regressionShouldWorkWithVariousData() {
        VodafonePage page = new VodafonePage(driver);
        page.open();
        page.login("username1", "password1");
        assertEquals("Dashboard", page.getTitle());

        page.logout();
        page.login("username2", "password2");
        assertEquals("Dashboard", page.getTitle());
    }

    @Test
    public void negativeShouldRejectEmptyForm() {
        VodafonePage page = new VodafonePage(driver);
        page.open();
        page.submitLoginForm("", "");
        assertEquals("Error: Username and password are required.", page.getErrorMessage());
    }

    @Test
    public void negativeShouldRejectInvalidInput() {
        VodafonePage page = new VodafonePage(driver);
        page.open();
        page.submitLoginForm("invalid", "invalid");
        assertEquals("Error: Invalid username or password.", page.getErrorMessage());
    }

    @Test
    public void boundaryShouldHandleMaxLength() {
        VodafonePage page = new VodafonePage(driver);
        page.open();
        page.login("a".repeat(50), "password");
        assertEquals("Dashboard", page.getTitle());
    }

    @Test
    public void boundaryShouldHandleMinLength() {
        VodafonePage page = new VodafonePage(driver);
        page.open();
        page.login("a".repeat(1), "password");
        assertEquals("Dashboard", page.getTitle());
    }
}

class VodafonePage {

    private WebDriver driver;

    public VodafonePage(WebDriver driver) {
        this.driver = driver;
    }

    public void open() {
        driver.get("https://vodafone.com.tr");
    }

    public String getTitle() {
        return driver.getTitle();
    }

    public void login(String username, String password) {
        driver.findElement(By.name("username")).sendKeys(username);
        driver.findElement(By.name("password")).sendKeys(password);
        driver.findElement(By.name("login")).click();
    }

    public void logout() {
        driver.findElement(By.linkText("Logout")).click();
    }

    public String getErrorMessage() {
        return driver.findElement(By.cssSelector(".error-message")).getText();
    }
}