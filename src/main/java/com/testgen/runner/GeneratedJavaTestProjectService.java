package com.testgen.runner;

import com.testgen.model.TestFramework;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
public class GeneratedJavaTestProjectService {

    @Value("${test-generator.output.selenium-path}")
    private String seleniumOutputPath;

    @Value("${test-generator.output.restassured-path:/tmp/generated-tests/restassured}")
    private String restassuredOutputPath;

    @Value("${selenium.version:4.18.1}")
    private String seleniumVersion;

    public Path ensureProject(TestFramework framework) {
        Path projectDir = projectDir(framework);
        try {
            Files.createDirectories(sourceDir(projectDir));
            Files.writeString(projectDir.resolve("pom.xml"), pomFor(framework), StandardCharsets.UTF_8);
            if (framework == TestFramework.SELENIUM) {
                // Driver bootstrap'ı LLM'e bırakmıyoruz: sabit factory sınıfı projede hazır durur,
                // üretilen testler sadece DriverFactory.createDriver() çağırır.
                Files.writeString(sourceDir(projectDir).resolve("DriverFactory.java"),
                        DRIVER_FACTORY_SOURCE, StandardCharsets.UTF_8);
            }
            return projectDir;
        } catch (IOException e) {
            throw new IllegalStateException("Generated test Maven projesi hazirlanamadi: " + projectDir, e);
        }
    }

    /**
     * Üretilen Selenium testlerinin tek driver giriş noktası.
     * SELENIUM_REMOTE_URL doluysa Grid'e (RemoteWebDriver), boşsa lokal headless Chrome'a bağlanır.
     */
    private static final String DRIVER_FACTORY_SOURCE = """
            package com.testgen.generated;

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
            """;

    public Path writeTestSource(TestFramework framework, String fileName, String content) {
        Path projectDir = ensureProject(framework);
        Path sourceFile = sourceDir(projectDir).resolve(fileName);
        try {
            Files.createDirectories(sourceFile.getParent());
            Files.writeString(sourceFile, content, StandardCharsets.UTF_8);
            return sourceFile;
        } catch (IOException e) {
            throw new IllegalStateException("Generated Java test dosyasi yazilamadi: " + sourceFile, e);
        }
    }

    public void cleanTestFiles(TestFramework framework) {
        if (framework != TestFramework.SELENIUM && framework != TestFramework.REST_ASSURED) return;
        Path dir = sourceDir(projectDir(framework));
        if (Files.exists(dir)) {
            try (var stream = Files.list(dir)) {
                stream.forEach(path -> {
                    String name = path.getFileName().toString();
                    if (name.endsWith(".java") && !name.equals("DriverFactory.java")) {
                        try {
                            Files.delete(path);
                            log.info("Temizlenen eski Java test dosyası: {}", name);
                        } catch (IOException e) {
                            log.warn("Eski Java test dosyası silinemedi: {}", path, e);
                        }
                    }
                });
            } catch (IOException e) {
                log.error("Java test dosyaları temizlenirken hata oluştu: {}", dir, e);
            }
        }
    }

    public Path projectDir(TestFramework framework) {
        return switch (framework) {
            case SELENIUM -> Path.of(seleniumOutputPath);
            case REST_ASSURED -> Path.of(restassuredOutputPath);
            default -> throw new IllegalArgumentException("Java test projesi desteklenmiyor: " + framework);
        };
    }

    private Path sourceDir(Path projectDir) {
        return projectDir.resolve("src/test/java");
    }

    private String pomFor(TestFramework framework) {
        String extraDependencies = "";
        if (framework == TestFramework.REST_ASSURED) {
            extraDependencies = """
                        <dependency>
                            <groupId>io.rest-assured</groupId>
                            <artifactId>rest-assured</artifactId>
                            <version>5.4.0</version>
                            <scope>test</scope>
                        </dependency>
                        <dependency>
                            <groupId>org.hamcrest</groupId>
                            <artifactId>hamcrest</artifactId>
                            <version>2.2</version>
                            <scope>test</scope>
                        </dependency>
                        """;
        }

        return """
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.testgen.generated</groupId>
    <artifactId>%s-generated-tests</artifactId>
    <version>1.0.0</version>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <junit.version>5.10.2</junit.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.seleniumhq.selenium</groupId>
            <artifactId>selenium-java</artifactId>
            <version>%s</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.github.bonigarcia</groupId>
            <artifactId>webdrivermanager</artifactId>
            <version>5.8.0</version>
            <scope>test</scope>
        </dependency>
%s
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
                <configuration>
                    <useModulePath>false</useModulePath>
                    <failIfNoSpecifiedTests>false</failIfNoSpecifiedTests>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
"""
                .formatted(framework.name().toLowerCase(), seleniumVersion, extraDependencies);
    }
}
