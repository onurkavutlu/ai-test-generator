package com.testgen.generator;

import com.testgen.model.TestFramework;
import com.testgen.model.ValidationStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.tools.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Üretilen test içeriğini ÜRETİM ANINDA makine ile doğrular.
 *
 * Bu bir "kalite yorumu" değil, deterministik bir kapıdır:
 *  - KARATE                    → Karate'nin kendi parser'ı ile feature okunur
 *  - SELENIUM / REST_ASSURED   → bellek içi {@link JavaCompiler} ile derlenir
 *
 * Böylece sözdizimi/derleme hataları koşumda "0/0 FAILED" olarak görünmek yerine
 * üretildiği anda yakalanır ve deterministik onarım / yeniden üretim tetiklenebilir.
 *
 * ÖNEMLİ: Doğrulayıcının kendi kısıtı (JDK derleyicisi yok, classpath eksik) asla
 * içeriği geçersiz saymaz — bu durumda {@link ValidationStatus#SKIPPED} döner.
 */
@Slf4j
@Component
public class GeneratedTestValidator {

    private static final int MAX_ERROR_CHARS = 1_500;

    /** Doğrulayıcının classpath'inde bulunamadığında "içerik hatalı" sayılmayacak paketler. */
    private static final List<String> FRAMEWORK_PACKAGES = List.of(
            "org.openqa.selenium", "io.restassured", "org.junit", "org.hamcrest",
            "io.github.bonigarcia");

    public record ValidationResult(ValidationStatus status, String error) {

        public boolean isInvalid() {
            return status == ValidationStatus.INVALID;
        }

        static ValidationResult valid() {
            return new ValidationResult(ValidationStatus.VALID, null);
        }

        static ValidationResult invalid(String error) {
            return new ValidationResult(ValidationStatus.INVALID, truncate(error));
        }

        static ValidationResult skipped(String reason) {
            return new ValidationResult(ValidationStatus.SKIPPED, truncate(reason));
        }

        private static String truncate(String text) {
            if (text == null) return null;
            String clean = text.strip();
            return clean.length() > MAX_ERROR_CHARS ? clean.substring(0, MAX_ERROR_CHARS) + "…[kısaltıldı]" : clean;
        }
    }

    public ValidationResult validate(TestFramework framework, String fileName, String content) {
        return validate(framework, fileName, content, List.of());
    }

    /**
     * @param supportSources testin kullandığı destek sınıfları (Page Object vb.).
     *                       Bunlar derlemeye dahil edilmezse test HAKSIZ yere geçersiz sayılır.
     */
    public ValidationResult validate(TestFramework framework, String fileName, String content,
                                     List<SupportSource> supportSources) {
        if (content == null || content.isBlank()) {
            return ValidationResult.invalid("Üretilen içerik boş.");
        }
        return framework == TestFramework.KARATE
                ? validateFeature(content)
                : validateJava(framework, fileName, content, supportSources);
    }

    /** Derlemeye eklenecek yardımcı kaynak. */
    public record SupportSource(String className, String content) { }

    // ─────────────────────────────────────────────────────────
    // Karate: gerçek Gherkin parser'ı
    // ─────────────────────────────────────────────────────────
    ValidationResult validateFeature(String content) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("validate-", ".feature");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);

            var feature = com.intuit.karate.core.Feature.read(tmp.toFile());
            long scenarioCount = feature.getSections() == null ? 0 : feature.getSections().size();
            if (scenarioCount == 0) {
                return ValidationResult.invalid("Feature ayrıştırıldı ama hiç senaryo içermiyor.");
            }

            // Gherkin parser'ı YALNIZCA sözdizimine bakar; adımın bir step-definition'a
            // karşılık gelip gelmediğini görmez. "* header = {...}" gibi satırlar bu yüzden
            // VALID damgası alıp koşumda "no step-definition method match found" ile
            // feature'ın tamamını düşürüyordu. Aşağıdaki denetim o boşluğu kapatır.
            String stepError = findUnmatchableStep(content);
            if (stepError != null) {
                return ValidationResult.invalid(stepError);
            }
            return ValidationResult.valid();

        } catch (IOException e) {
            // Geçici dosya yazılamadı → doğrulayıcının kendi sorunu, içerik suçlanmaz
            return ValidationResult.skipped("Doğrulama için geçici dosya yazılamadı: " + e.getMessage());
        } catch (Throwable e) {
            return ValidationResult.invalid("Karate parse hatası: " + rootMessage(e));
        } finally {
            deleteQuietly(tmp);
        }
    }

    /**
     * Karate'de hiçbir step-definition'a eşleşmeyecek adımları bulur.
     *
     * BİLEREK DAR TUTULDU: Karate bir adım olarak serbest JS ifadesi de kabul eder,
     * bu yüzden "bilinmeyen her adım hatalıdır" demek yanlış pozitif üretirdi.
     * Yalnızca gözlemlenmiş, kesin geçersiz iki biçim denetlenir:
     *   - ad isteyen adım adsız yazılmış:      "* header = {...}"
     *   - değer alan adıma "=" konmuş:          "* url = 'x'", "* status = 200"
     * (İkisini de {@link CodeCleaner#repairFeatureSyntax} onarır; bu denetim
     *  onarımın kaçırdığı durumlar için son kapıdır.)
     */
    public static String findUnmatchableStep(String content) {
        String[] lines = content.split("\\r?\\n");
        boolean inDocString = false;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("\"\"\"")) {
                inDocString = !inDocString;
                continue;
            }
            if (inDocString) {
                continue;
            }
            String body = stepBody(trimmed);
            if (body == null) {
                continue;
            }
            java.util.regex.Matcher m = INVALID_STEP.matcher(body);
            if (m.find()) {
                return ("satır %d: Karate adımı hiçbir step-definition'a eşleşmez — \"%s\". "
                        + "Doğrusu: ad isteyen adımlar \"* header Accept = 'application/json'\", "
                        + "map alan adımlar \"* headers { Accept: 'application/json' }\", "
                        + "değer alan adımlar \"* url 'http://...'\" biçimindedir.")
                        .formatted(i + 1, trimmed);
            }
        }
        return null;
    }

    /** Adım satırının gövdesi ("* ", "Given ", "When " … sonrası); adım değilse null. */
    private static String stepBody(String trimmed) {
        for (String prefix : List.of("* ", "Given ", "When ", "Then ", "And ", "But ")) {
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    /** Ad isteyen adım adsız, ya da değer alan adım "=" ile yazılmış. */
    private static final java.util.regex.Pattern INVALID_STEP = java.util.regex.Pattern.compile(
            "^(header|param|cookie|url|path|method|status|request|headers|params|cookies)\\s*=",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    // ─────────────────────────────────────────────────────────
    // Java: bellek içi derleme
    // ─────────────────────────────────────────────────────────
    ValidationResult validateJava(TestFramework framework, String fileName, String content,
                                  List<SupportSource> supportSources) {
        // LLM bazen kod yerine açıklama metni döndürüyor; bu metin sınıf gövdesine
        // sarıldığında derleyici alakasız bir hata veriyor ("cannot find symbol: ...")
        // ve asıl sorun — içerikte hiç test olmaması — görünmez oluyordu.
        if (!CodeCleaner.looksRunnableJavaTest(content)) {
            return ValidationResult.invalid(
                    "Üretilen içerik test kodu değil: hiç @Test metodu yok "
                            + "(LLM büyük olasılıkla kod yerine açıklama metni döndürdü).");
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return ValidationResult.skipped("JDK derleyicisi bulunamadı (JRE ile çalışılıyor).");
        }

        String className = fileName != null && fileName.endsWith(".java")
                ? fileName.substring(0, fileName.length() - ".java".length())
                : CodeCleaner.publicClassName(content);

        List<JavaFileObject> sources = new ArrayList<>();
        sources.add(new InMemorySource(className, content));
        if (framework == TestFramework.SELENIUM) {
            // Üretilen testler DriverFactory'yi kullanır; derleme için o da verilmeli
            sources.add(new InMemorySource("DriverFactory",
                    com.testgen.runner.GeneratedJavaTestProjectService.driverFactorySource()));
        }
        // LLM'in aynı üretimde çıkardığı Page Object'ler — bunlar olmadan test haksız yere
        // "cannot find symbol: class XPage" ile geçersiz sayılırdı
        for (SupportSource support : supportSources) {
            if (!"DriverFactory".equals(support.className()) && !className.equals(support.className())) {
                sources.add(new InMemorySource(support.className(), support.content()));
            }
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager standard = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {

            Path out = Files.createTempDirectory("validate-classes-");
            try {
                standard.setLocation(StandardLocation.CLASS_OUTPUT, List.of(out.toFile()));
                List<String> options = List.of("-classpath", System.getProperty("java.class.path"),
                        "-proc:none", "-nowarn");

                boolean ok = compiler.getTask(null, standard, diagnostics, options, null, sources).call();
                if (ok) {
                    return ValidationResult.valid();
                }

                List<Diagnostic<? extends JavaFileObject>> errors = diagnostics.getDiagnostics().stream()
                        .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                        .toList();

                if (errors.stream().anyMatch(GeneratedTestValidator::isMissingFrameworkPackage)) {
                    // Doğrulayıcının classpath'i eksik (örn. fat-jar içinde koşuluyor) —
                    // bu bir içerik hatası değildir.
                    return ValidationResult.skipped(
                            "Doğrulayıcı classpath'inde test framework'ü bulunamadı; derleme doğrulaması atlandı.");
                }
                return ValidationResult.invalid(format(errors));

            } finally {
                deleteRecursively(out);
            }
        } catch (IOException e) {
            return ValidationResult.skipped("Derleme doğrulaması yapılamadı: " + e.getMessage());
        }
    }

    private static boolean isMissingFrameworkPackage(Diagnostic<? extends JavaFileObject> d) {
        String msg = String.valueOf(d.getMessage(Locale.ENGLISH));
        if (!msg.contains("package") || !msg.contains("does not exist")) {
            return false;
        }
        return FRAMEWORK_PACKAGES.stream().anyMatch(msg::contains);
    }

    private static String format(List<Diagnostic<? extends JavaFileObject>> errors) {
        return errors.stream()
                .limit(8)
                .map(d -> "satır " + d.getLineNumber() + ": " + d.getMessage(Locale.ENGLISH))
                .collect(Collectors.joining("\n"));
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        return msg == null || msg.isBlank() ? root.getClass().getSimpleName() : msg;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // geçici dosya kalması doğrulamayı etkilemez
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null) return;
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(GeneratedTestValidator::deleteQuietly);
        } catch (IOException ignored) {
            // geçici dizin kalması doğrulamayı etkilemez
        }
    }

    /** Derleyiciye bellekten kaynak veren basit JavaFileObject. */
    private static final class InMemorySource extends SimpleJavaFileObject {
        private final String code;

        InMemorySource(String className, String code) {
            super(URI.create("string:///" + className + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
