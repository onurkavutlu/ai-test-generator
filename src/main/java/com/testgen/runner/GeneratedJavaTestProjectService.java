package com.testgen.runner;

import com.testgen.model.TestFramework;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

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
            copyMavenWrapper(projectDir);
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
    static final String DRIVER_FACTORY_SOURCE = """
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

    /**
     * Tek bir test case'i için İZOLE bir Maven projesi hazırlar.
     *
     * Ortak projede `mvn test -Dtest=X` çalıştırmak TÜM kaynakları derler; LLM'in ürettiği
     * tek bir bozuk sınıf (örn. import'u eksik bir LoadTest) diğer tüm case'leri de
     * "BUILD FAILURE" yapıyordu. Burada yalnızca koşulacak test sınıfı + destek sınıfları
     * (DriverFactory, Page Object'ler) kopyalanır; case'ler birbirini etkilemez.
     *
     * @return izole proje dizini
     */
    public Path prepareIsolatedRun(TestFramework framework, String runKey,
                                   String testFileName, String testContent) {
        Path shared = ensureProject(framework);
        Path runDir = shared.resolve("runs").resolve(sanitizeKey(runKey));
        try {
            Path runSrc = runDir.resolve("src/test/java");
            Files.createDirectories(runSrc);
            Files.writeString(runDir.resolve("pom.xml"), pomFor(framework), StandardCharsets.UTF_8);
            copyMavenWrapper(runDir);

            // Destek sınıflarından YALNIZCA bu testin gerçekten kullandıkları kopyalanır.
            // Hepsini kopyalamak, LLM'in ürettiği bozuk bir Page Object'in (örn. DashboardPage)
            // alakasız ve geçerli testleri de derlenemez hâle getirmesine yol açıyordu.
            Path sharedSrc = sourceDir(shared);
            if (Files.exists(sharedSrc)) {
                try (var stream = Files.list(sharedSrc)) {
                    stream.filter(p -> {
                        String n = p.getFileName().toString();
                        if (!n.endsWith(".java") || n.endsWith("Test.java") || n.endsWith("Tests.java")) {
                            return false;
                        }
                        String simpleName = n.substring(0, n.length() - ".java".length());
                        // DriverFactory bizim yazdığımız sabit sınıf — her Selenium koşumunda gerekli
                        return "DriverFactory".equals(simpleName)
                                || referencesType(testContent, simpleName);
                    }).forEach(p -> {
                        try {
                            Files.copy(p, runSrc.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException e) {
                            log.warn("Destek sınıfı kopyalanamadı: {}", p.getFileName());
                        }
                    });
                }
            }

            Files.writeString(runSrc.resolve(testFileName), testContent, StandardCharsets.UTF_8);
            return runDir;
        } catch (IOException e) {
            throw new IllegalStateException("İzole koşum projesi hazırlanamadı: " + runDir, e);
        }
    }

    /** İzole koşum dizinini siler; başarısız olursa koşum sonucunu etkilemez. */
    public void cleanupIsolatedRun(Path runDir) {
        if (runDir == null || !Files.exists(runDir)) {
            return;
        }
        try (var paths = Files.walk(runDir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // geçici dosya kalması koşumu etkilemez
                }
            });
        } catch (IOException e) {
            log.debug("İzole koşum dizini temizlenemedi: {}", runDir);
        }
    }

    /** Üretilen bir destek sınıfı (Page Object vb.): adı + kaynağı. */
    public record SupportSource(String className, String content) { }

    /**
     * Test içeriğinin kullandığı destek sınıflarını (Page Object'ler) diskten okur.
     *
     * Doğrulama (derleme) sırasında gereklidir: LLM hem testi hem de kullandığı Page
     * Object'i üretiyor; yalnızca test sınıfı derlenirse "cannot find symbol: class
     * XPage" alınır ve içerik HAKSIZ yere geçersiz sayılır.
     */
    public List<SupportSource> supportSourcesFor(TestFramework framework, String testContent) {
        if (framework != TestFramework.SELENIUM && framework != TestFramework.REST_ASSURED) {
            return List.of();
        }
        Path dir = sourceDir(projectDir(framework));
        if (!Files.exists(dir)) {
            return List.of();
        }
        List<SupportSource> sources = new java.util.ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".java") || name.endsWith("Test.java") || name.endsWith("Tests.java")) {
                    continue;
                }
                String simpleName = name.substring(0, name.length() - ".java".length());
                if ("DriverFactory".equals(simpleName) || !referencesType(testContent, simpleName)) {
                    continue; // DriverFactory doğrulayıcı tarafından ayrıca ekleniyor
                }
                sources.add(new SupportSource(simpleName, Files.readString(p, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            log.warn("Destek sınıfları doğrulama için okunamadı ({}): {}", dir, e.getMessage());
        }
        return sources;
    }

    /** Test kaynağı verilen tipi (tam kelime olarak) kullanıyor mu? */
    static boolean referencesType(String testContent, String simpleName) {
        if (testContent == null || simpleName == null || simpleName.isBlank()) {
            return false;
        }
        return java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(simpleName) + "\\b")
                .matcher(testContent).find();
    }

    private static String sanitizeKey(String key) {
        return key == null || key.isBlank()
                ? "run"
                : key.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * DriverFactory kaynağı — üretilen Selenium testleri derleme doğrulamasından geçerken
     * bu sınıf da derleyiciye verilmelidir (testler {@code DriverFactory.createDriver()} çağırır).
     */
    public static String driverFactorySource() {
        return DRIVER_FACTORY_SOURCE;
    }

    public Path writeTestSource(TestFramework framework, String fileName, String content) {
        Path projectDir = ensureProject(framework);
        Path sourceFile = sourceDir(projectDir).resolve(fileName);
        try {
            Files.createDirectories(sourceFile.getParent());
            // Java, public sınıf adının dosya adıyla aynı olmasını ŞART koşar. Elle
            // eklenen/yeniden adlandırılan case'lerde bu ikisi ayrışabiliyor ve derleme
            // "class X is public, should be declared in a file named X.java" ile kırılıyordu.
            Files.writeString(sourceFile, alignPublicClassName(fileName, content), StandardCharsets.UTF_8);
            return sourceFile;
        } catch (IOException e) {
            throw new IllegalStateException("Generated Java test dosyasi yazilamadi: " + sourceFile, e);
        }
    }

    /**
     * İçerikteki public sınıf adını dosya adıyla hizalar.
     *
     * Üretici akışta ikisi zaten aynıdır; ancak API'den elle case eklendiğinde
     * (ör. bir case'i düzeltip yeni adla kaydetmek) sınıf adı eski kalıyor ve
     * javac tüm sınıfı reddediyordu. Ad zaten uyuyorsa içerik aynen döner.
     */
    public static String alignPublicClassName(String fileName, String content) {
        if (fileName == null || !fileName.endsWith(".java") || content == null || content.isBlank()) {
            return content;
        }
        String expected = fileName.substring(0, fileName.length() - ".java".length());
        String actual = com.testgen.generator.CodeCleaner.publicClassName(content);
        if (actual == null || actual.isBlank() || actual.equals(expected)) {
            return content;
        }
        log.warn("Public sınıf adı dosya adıyla uyuşmuyordu ({} ≠ {}) — içerikteki ad hizalandı.",
                actual, expected);
        return content.replaceAll("\\bclass\\s+" + java.util.regex.Pattern.quote(actual) + "\\b",
                "class " + expected);
    }

    /**
     * Bayat test sınıflarını siler.
     *
     * YALNIZCA koşulabilir test sınıfları (*Test.java / *Tests.java) silinir; destek
     * sınıfları (DriverFactory ve LLM'in ürettiği Page Object'ler) korunur. Aksi hâlde
     * koşumdan hemen önce Page Object silindiği için üretilen testler
     * "cannot find symbol: class XPage" ile derlenemiyordu — destek sınıfları case
     * olarak saklanmadığından koşum sırasında yeniden yazılmıyor.
     */
    public void cleanTestFiles(TestFramework framework) {
        if (framework != TestFramework.SELENIUM && framework != TestFramework.REST_ASSURED) return;
        Path dir = sourceDir(projectDir(framework));
        if (Files.exists(dir)) {
            try (var stream = Files.list(dir)) {
                stream.forEach(path -> {
                    String name = path.getFileName().toString();
                    if (name.endsWith("Test.java") || name.endsWith("Tests.java")) {
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

    /**
     * Uygulamanın kendi Maven wrapper'ını üretilen projeye kopyalar.
     * Böylece hedef makinede `mvn` kurulu olmasa da üretilen testler koşabilir —
     * `resolveMavenCommand` önce bu wrapper'ı bulur.
     */
    private void copyMavenWrapper(Path projectDir) {
        Path appDir = Path.of(System.getProperty("user.dir"));
        Path wrapper = appDir.resolve("mvnw");
        if (!Files.exists(wrapper)) {
            return; // wrapper yoksa sessizce geç; resolveMavenCommand diğer adaylara düşer
        }
        try {
            for (String name : List.of("mvnw", "mvnw.cmd")) {
                Path src = appDir.resolve(name);
                if (Files.exists(src)) {
                    Path target = projectDir.resolve(name);
                    Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
                    target.toFile().setExecutable(true);
                }
            }
            Path wrapperProps = appDir.resolve(".mvn/wrapper/maven-wrapper.properties");
            if (Files.exists(wrapperProps)) {
                Path targetDir = projectDir.resolve(".mvn/wrapper");
                Files.createDirectories(targetDir);
                Files.copy(wrapperProps, targetDir.resolve("maven-wrapper.properties"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Maven wrapper üretilen projeye kopyalanamadı ({}): {}", projectDir, e.getMessage());
        }
    }

    /**
     * Üretilen proje için çalıştırılacak maven komutunu çözer.
     * Sıra: projedeki wrapper → uygulamanın kendi wrapper'ı → bilinen kurulum
     * konumları → MAVEN_HOME/M2_HOME → PATH.
     */
    public String resolveMavenCommand(Path projectDir) {
        Path localWrapper = projectDir.resolve("mvnw");
        if (Files.exists(localWrapper)) {
            localWrapper.toFile().setExecutable(true);
            return localWrapper.toAbsolutePath().toString();
        }

        Path appWrapper = Path.of(System.getProperty("user.dir")).resolve("mvnw");
        if (Files.exists(appWrapper)) {
            appWrapper.toFile().setExecutable(true);
            return appWrapper.toAbsolutePath().toString();
        }

        for (String candidate : List.of(
                "/usr/local/bin/mvn",
                "/usr/bin/mvn",
                "/opt/homebrew/bin/mvn",
                System.getProperty("user.home") + "/.sdkman/candidates/maven/current/bin/mvn")) {
            if (Files.exists(Path.of(candidate))) {
                return candidate;
            }
        }

        for (String envVar : List.of("MAVEN_HOME", "M2_HOME")) {
            String home = System.getenv(envVar);
            if (home != null && !home.isBlank()) {
                Path fromEnv = Path.of(home, "bin", "mvn");
                if (Files.exists(fromEnv)) {
                    return fromEnv.toAbsolutePath().toString();
                }
            }
        }

        return "mvn"; // son çare: PATH
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

    private static final String HAMCREST_DEPENDENCY = """
                        <dependency>
                            <groupId>org.hamcrest</groupId>
                            <artifactId>hamcrest</artifactId>
                            <version>2.2</version>
                            <scope>test</scope>
                        </dependency>
            """;

    private static final String REST_ASSURED_DEPENDENCY = """
                        <dependency>
                            <groupId>io.rest-assured</groupId>
                            <artifactId>rest-assured</artifactId>
                            <version>5.4.0</version>
                            <scope>test</scope>
                        </dependency>
            """;

    private String pomFor(TestFramework framework) {
        // Hamcrest + REST Assured her iki Java framework'ünde de bulunur:
        //  - Hamcrest: LLM assertThat/containsString/notNullValue kullandığında gerekli.
        //  - REST Assured: UI testi içinde API doğrulaması (hibrit senaryo) yaygın; bağımlılık
        //    yoksa üretilen sınıf hiç derlenmiyordu.
        String extraDependencies = REST_ASSURED_DEPENDENCY + HAMCREST_DEPENDENCY;

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
                // Locale.ROOT şart: Türkçe locale'de "SELENIUM".toLowerCase() → "selenıum"
                // (noktasız ı) üretir ve Maven artifactId pattern'ine uymadığı için
                // proje hiç okunamaz.
                .formatted(framework.name().toLowerCase(Locale.ROOT), seleniumVersion, extraDependencies);
    }
}
