package com.testgen.generator;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM çıktısını temizleyen ve parse eden yardımcı sınıf.
 * LLM bazen markdown code fence, açıklama metni ekler – bunları temizler.
 */
@Slf4j
public final class CodeCleaner {

    private static final Pattern FEATURE_BLOCK = Pattern.compile(
            "```(?:gherkin|feature|karate)?\\s*\\n?(Feature:.+?)```",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final Pattern JAVA_BLOCK = Pattern.compile(
            "```(?:java)?\\s*\\n?((?:package|import|public|//)[\\s\\S]+?)```",
            Pattern.DOTALL);

    private static final Pattern CLASS_NAME = Pattern.compile(
            "(?:public\\s+class|public\\s+abstract\\s+class)\\s+(\\w+)");

    private static final Pattern CLASS_START = Pattern.compile(
            "(?m)^\\s*public\\s+(?:abstract\\s+)?class\\s+\\w+");

    /** Karate'nin kendi step anahtar kelimeleri — bunlardan sonra gelen "=" atama değildir. */
    private static final Set<String> KARATE_STEP_KEYWORDS = Set.of(
            "def", "set", "text", "json", "xml", "xmlstring", "string", "bytes", "copy",
            "table", "csv", "yaml", "configure", "header", "headers", "param", "params",
            "cookie", "cookies", "path", "url", "request", "method", "status", "match",
            "assert", "print", "eval", "call", "callonce", "karate", "replace", "soap",
            "action", "form", "multipart", "retry", "read", "listen", "doc", "remove");

    /** "* <ad> = ..." biçimindeki (def'i unutulmuş) atama adımı. */
    private static final Pattern BARE_ASSIGNMENT = Pattern.compile(
            "^([A-Za-z_$][\\w$]*)\\s*=\\s*[^=].*$");

    /**
     * Değeri "=" olmadan alan Karate adımları. LLM sık sık "* url = 'x'" yazıyor;
     * doğrusu "* url 'x'" — aradaki "=" adımı tanınmaz yapıyor.
     */
    private static final Set<String> KARATE_VALUE_ONLY_KEYWORDS =
            Set.of("url", "path", "request", "method", "status", "soap",
                    "headers", "params", "cookies", "form");

    /**
     * Karate'de AD İSTEYEN adımlar: "* header Accept = 'application/json'".
     * LLM bunları adsız, map değeriyle yazıyor ("* header = { Accept: '...' }") ve
     * Karate adımı hiç tanımıyor: "no step-definition method match found".
     *
     * Bu, gözlenen en yıkıcı üretim hatasıydı: satır Background'da olduğu için
     * feature'daki TÜM senaryolar HTTP çağrısına ulaşamadan düşüyordu. Onarım,
     * adsız map biçimini çoğul (map alan) karşılığına çevirir:
     *   "* header  = { A: 'b' }" → "* headers { A: 'b' }"
     *   "* param   = { q: 1 }"   → "* params { q: 1 }"
     */
    private static final Set<String> KARATE_NAME_REQUIRED_KEYWORDS =
            Set.of("header", "param", "cookie");

    /** "<keyword> = { ... }" — adsız, map değerli adım. */
    private static final Pattern KARATE_NAMELESS_MAP_STEP = Pattern.compile(
            "^(header|param|cookie)\\s*=\\s*(\\{.*)$", Pattern.CASE_INSENSITIVE);

    /** Karate dosya başına yalnızca tek Feature kabul eder. */
    private static final Pattern FEATURE_LINE = Pattern.compile("(?m)^\\s*Feature:");

    private CodeCleaner() {}

    /**
     * Feature dosyası içeriğini markdown fence'lerden temizler ve @testCaseLLM tag'lerini ekler.
     */
    public static String cleanFeatureContent(String raw) {
        if (raw == null) return "";

        String cleaned;
        // Markdown code block var mı?
        Matcher m = FEATURE_BLOCK.matcher(raw);
        if (m.find()) {
            cleaned = m.group(1).strip();
        } else {
            // Fence yoksa: fence kalıntılarını sil, LLM'in kod öncesi açıklama metnini at
            cleaned = stripToFeatureStart(raw.replaceAll("```[a-z]*\\n?", ""));
        }
        return injectTestCaseLlmTag(repairFeatureSyntax(cleaned));
    }

    /**
     * LLM'in ürettiği Karate DSL'deki iki yaygın ve koşumu tamamen engelleyen
     * sözdizimi hatasını deterministik olarak onarır:
     *
     *  1. "* baseUrl = 'x'"  → "* def baseUrl = 'x'"
     *     def unutulduğunda Karate adımı tanımaz: "no step-definition method match found".
     *  2. Çok satırlı JS bloğu ("* def f = function() {\n  return ...\n}") tek satıra
     *     indirgenir. Kapanış parantezi adımla aynı girintideyse Gherkin parser'ı kırılır:
     *     "mismatched input 'r' expecting &lt;EOF&gt;".
     *
     * Onarım yalnızca "*" ile başlayan adım satırlarına uygulanır; Given/When/Then
     * satırlarına ve tablo/docstring içeriğine dokunulmaz.
     */
    public static String repairFeatureSyntax(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }

        String[] lines = content.split("\\r?\\n");
        List<String> out = new ArrayList<>();
        boolean inDocString = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // Docstring ("""...""") içine dokunma
            if (trimmed.startsWith("\"\"\"")) {
                inDocString = !inDocString;
                out.add(line);
                continue;
            }
            if (inDocString || !trimmed.startsWith("*")) {
                out.add(line);
                continue;
            }

            String indent = line.substring(0, line.length() - line.stripLeading().length());
            String body = trimmed.substring(1).trim();

            // 2) Dengesiz açık parantez varsa sonraki satırları bu adıma katla
            if (isUnbalanced(body)) {
                StringBuilder joined = new StringBuilder(body);
                int j = i + 1;
                while (j < lines.length && isUnbalanced(joined.toString())) {
                    String next = lines[j].trim();
                    if (startsNewGherkinConstruct(next)) {
                        break;
                    }
                    joined.append(' ').append(next);
                    j++;
                }
                if (!isUnbalanced(joined.toString())) {
                    body = joined.toString().replaceAll("\\s+", " ");
                    i = j - 1;
                }
            }

            // 1a) Adsız map biçimi: "* header = { A: 'b' }" → "* headers { A: 'b' }"
            Matcher namelessMap = KARATE_NAMELESS_MAP_STEP.matcher(body);
            if (namelessMap.matches()) {
                body = namelessMap.group(1).toLowerCase(Locale.ROOT) + "s " + namelessMap.group(2);
            }

            // 1b) def'i unutulmuş atama / değer alan adımlara yanlışlıkla konmuş "="
            Matcher assign = BARE_ASSIGNMENT.matcher(body);
            if (assign.matches()) {
                String keyword = assign.group(1).toLowerCase(Locale.ROOT);
                if (KARATE_VALUE_ONLY_KEYWORDS.contains(keyword)) {
                    // "* url = 'x'" → "* url 'x'"
                    body = body.replaceFirst("^([A-Za-z_$][\\w$]*)\\s*=\\s*", "$1 ");
                } else if (KARATE_NAME_REQUIRED_KEYWORDS.contains(keyword)) {
                    // "* header = 'x'" — ad yok, map de değil: onarılamaz, adım düşürülür.
                    // Bırakılırsa Background'da kalıp tüm senaryoları düşürür.
                    log.warn("Onarılamayan Karate adımı atlandı (ad bekleniyordu): * {}", body);
                    continue;
                } else if (!KARATE_STEP_KEYWORDS.contains(keyword)) {
                    body = "def " + body;
                }
            }

            out.add(indent + "* " + body);
        }

        return dropExtraFeatureBlocks(stripNonGherkinLines(String.join("\n", out)));
    }

    /**
     * Feature satırından sonra gelen ve Gherkin'e ait OLMAYAN satırları atar.
     *
     * LLM (özellikle self-healing yanıtlarında) feature'ın SONUNA açıklama paragrafı
     * ekleyebiliyor ("Bu düzeltilmiş kodda ..."). Karate bunu ayrıştıramıyor ve tüm dosya
     * "mismatched input 'B' expecting &lt;EOF&gt;" ile koşulamaz hâle geliyordu.
     *
     * Yalnızca bilinen Gherkin yapıları korunur: tag, Feature/Background/Scenario/Examples,
     * adım satırları, tablo satırları, docstring blokları, yorumlar ve boş satırlar.
     * (Senaryo açıklama metinleri de atılır; koşumu etkilemez.)
     */
    private static String stripNonGherkinLines(String content) {
        String[] lines = content.split("\\r?\\n");
        List<String> out = new ArrayList<>();
        boolean seenFeature = false;
        boolean inDocString = false;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("\"\"\"")) {
                inDocString = !inDocString;
                out.add(line);
                continue;
            }
            if (inDocString) {
                out.add(line);
                continue;
            }
            if (!seenFeature) {
                out.add(line);
                if (trimmed.startsWith("Feature:")) {
                    seenFeature = true;
                }
                continue;
            }

            boolean gherkin = trimmed.isEmpty()
                    || trimmed.startsWith("#")
                    || trimmed.startsWith("@")
                    || trimmed.startsWith("|")
                    || trimmed.startsWith("Feature:")
                    || trimmed.startsWith("Background:")
                    || trimmed.startsWith("Scenario:")
                    || trimmed.startsWith("Scenario Outline:")
                    || trimmed.startsWith("Examples:")
                    || startsWithStepKeyword(trimmed);
            if (gherkin) {
                out.add(line);
            }
        }

        return String.join("\n", out).stripTrailing();
    }

    private static boolean startsWithStepKeyword(String trimmed) {
        for (String kw : new String[]{"Given ", "When ", "Then ", "And ", "But ", "* "}) {
            if (trimmed.startsWith(kw)) {
                return true;
            }
        }
        return trimmed.equals("*");
    }

    /**
     * Bir Karate dosyasında yalnızca TEK Feature bulunabilir. LLM bazen aynı yanıtta
     * birden çok Feature bloğu (araya markdown başlıkları serpiştirerek) döndürüyor;
     * ikinci Feature satırından itibaren geri kalanı atılır, aksi hâlde dosya parse edilemez.
     */
    private static String dropExtraFeatureBlocks(String content) {
        Matcher m = FEATURE_LINE.matcher(content);
        if (!m.find()) {
            return content;
        }
        if (!m.find()) {
            return content; // tek Feature — dokunma
        }
        return content.substring(0, m.start()).stripTrailing();
    }

    private static boolean isUnbalanced(String text) {
        int curly = 0, paren = 0, square = 0;
        boolean inSingle = false, inDouble = false;
        for (char c : text.toCharArray()) {
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;
            else if (!inSingle && !inDouble) {
                switch (c) {
                    case '{' -> curly++;
                    case '}' -> curly--;
                    case '(' -> paren++;
                    case ')' -> paren--;
                    case '[' -> square++;
                    case ']' -> square--;
                    default -> { }
                }
            }
        }
        return curly > 0 || paren > 0 || square > 0;
    }

    private static boolean startsNewGherkinConstruct(String trimmed) {
        return trimmed.startsWith("@")
                || trimmed.startsWith("*")
                || trimmed.startsWith("Feature:")
                || trimmed.startsWith("Background:")
                || trimmed.startsWith("Scenario:")
                || trimmed.startsWith("Scenario Outline:")
                || trimmed.startsWith("Examples:")
                || trimmed.startsWith("Given ")
                || trimmed.startsWith("When ")
                || trimmed.startsWith("Then ")
                || trimmed.startsWith("And ")
                || trimmed.startsWith("But ");
    }

    /**
     * Feature içeriğinin Karate tarafından koşulabilir görünüp görünmediğini kontrol eder.
     * Amaç: hatalı üretimi sessizce DB'ye yazıp "0/0 FAILED" olarak görmek yerine
     * üretim anında uyarmak.
     */
    /**
     * Üretilen Java içeriğinin gerçekten TEST KODU olup olmadığını söyler.
     *
     * Ölçülen bir koşumda LLM, kod yerine Türkçe bir analiz metni döndürdü;
     * {@link #normalizeGeneratedJavaTest} bunu sınıf gövdesine sardığı için ortaya
     * "derlenemeyen sınıf" çıktı ve hata mesajı ("cannot find symbol: assertThrows")
     * asıl sorunu — içerikte hiç kod olmamasını — gizledi. Bu kontrol o durumu
     * doğrudan adlandırır: en az bir @Test metodu yoksa içerik test kodu değildir.
     */
    public static boolean looksRunnableJavaTest(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        boolean hasTestAnnotation = Pattern.compile("(?<![\\w.])@Test\\b").matcher(content).find();
        boolean hasMethod = Pattern.compile("(?m)^\\s*(public|protected|private)?\\s*(static\\s+)?"
                + "(void|[A-Z][\\w<>\\[\\]]*)\\s+\\w+\\s*\\(").matcher(content).find();
        return hasTestAnnotation && hasMethod;
    }

    public static boolean looksRunnableFeature(String content) {
        if (content == null || content.isBlank() || !content.contains("Feature:")) {
            return false;
        }
        if (!content.contains("Scenario:") && !content.contains("Scenario Outline:")) {
            return false;
        }
        Matcher features = FEATURE_LINE.matcher(content);
        if (features.find() && features.find()) {
            return false; // dosya başına birden çok Feature → parse edilemez
        }
        for (String line : content.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("*")) {
                continue;
            }
            String body = trimmed.substring(1).trim();
            Matcher assign = BARE_ASSIGNMENT.matcher(body);
            if (!assign.matches()) {
                continue;
            }
            String keyword = assign.group(1).toLowerCase(Locale.ROOT);
            // "* url = 'x'" gibi değer alan adımlarda "=" olmamalı; diğerlerinde def şart
            if (KARATE_VALUE_ONLY_KEYWORDS.contains(keyword)
                    || !KARATE_STEP_KEYWORDS.contains(keyword)) {
                return false;
            }
        }
        return true;
    }

    /**
     * LLM'in "İşte düzeltilmiş kod:" gibi açıklama metinlerini atar —
     * içerik ilk Feature satırından (hemen üstündeki bitişik tag satırları dahil) başlar.
     * Feature satırı yoksa içerik olduğu gibi döner.
     */
    private static String stripToFeatureStart(String text) {
        String[] lines = text.split("\\r?\\n");
        int featureIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().startsWith("Feature:")) {
                featureIdx = i;
                break;
            }
        }
        if (featureIdx < 0) {
            return text.strip();
        }
        int start = featureIdx;
        while (start > 0 && lines[start - 1].trim().startsWith("@")) {
            start--;
        }
        return String.join("\n",
                java.util.Arrays.copyOfRange(lines, start, lines.length)).strip();
    }

    /**
     * Feature dosyasındaki Feature ve Scenario satırlarına @testCaseLLM tag'i enjekte eder.
     */
    public static String injectTestCaseLlmTag(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }

        List<String> lines = new ArrayList<>(java.util.Arrays.asList(content.split("\\r?\\n")));

        // 0. Dangling tag temizliği: Gherkin'de tag satırının HEMEN altında Feature/Scenario/
        //    Scenario Outline/Examples (veya başka bir tag satırı) olmalı. LLM bazen tag'i
        //    senaryonun sonuna koyar — bu Karate'de parse hatası üretir ("no viable alternative").
        for (int i = lines.size() - 1; i >= 0; i--) {
            String trimmed = lines.get(i).trim();
            if (!trimmed.startsWith("@")) {
                continue;
            }
            String next = i + 1 < lines.size() ? lines.get(i + 1).trim() : "";
            boolean valid = next.startsWith("@")
                    || next.startsWith("Feature:")
                    || next.startsWith("Scenario:")
                    || next.startsWith("Scenario Outline:")
                    || next.startsWith("Examples:");
            if (!valid) {
                lines.remove(i);
            }
        }

        // 1. Feature seviyesinde @testCaseLLM tag'i var mı? Yoksa ekle (lokal kontrol:
        //    Feature satırının hemen üstündeki bitişik tag bloğuna bakılır).
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).trim().startsWith("Feature:")) {
                continue;
            }
            boolean hasFeatureTag = false;
            int insertAt = i;
            for (int j = i - 1; j >= 0 && lines.get(j).trim().startsWith("@"); j--) {
                insertAt = j;
                if (lines.get(j).contains("@testCaseLLM")) {
                    hasFeatureTag = true;
                }
            }
            if (!hasFeatureTag) {
                lines.add(insertAt, "@testCaseLLM");
            }
            break;
        }

        // 2. Her Scenario veya Scenario Outline öncesinde @testCaseLLM tag'ini garanti altına al.

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.startsWith("Scenario:") || trimmed.startsWith("Scenario Outline:")) {
                boolean hasTag = false;
                int insertIndex = i;
                for (int j = i - 1; j >= 0; j--) {
                    String prevLine = lines.get(j).trim();
                    if (prevLine.startsWith("Scenario:") || prevLine.startsWith("Scenario Outline:") || prevLine.startsWith("Feature:") || prevLine.startsWith("Background:")) {
                        break;
                    }
                    if (prevLine.contains("@testCaseLLM")) {
                        hasTag = true;
                        break;
                    }
                    if (prevLine.startsWith("@")) {
                        insertIndex = j;
                    }
                }
                if (!hasTag) {
                    // Scenario satırının girintisini alıp etikete uygulayalım
                    String indent = line.substring(0, line.indexOf(trimmed));
                    lines.add(insertIndex, indent + "@testCaseLLM");
                    i++; // Eleman eklendiği için endeksi kaydırıyoruz
                }
            }
        }

        return String.join("\n", lines).strip();
    }

    /**
     * Java kaynak içeriğini markdown fence'lerden ve açıklama metinlerinden temizler.
     */
    public static String cleanJavaContent(String raw) {
        if (raw == null) return "";

        // 1. Markdown block formatı varsa (```java ... ```) orayı ayıkla
        Matcher m = JAVA_BLOCK.matcher(raw);
        if (m.find()) {
            return m.group(1).strip();
        }

        // 2. Yoksa, ilk kod satırından (package, import, public class, //) itibaren temizle
        String stripped = raw.strip();
        int firstKeyword = -1;
        for (String kw : new String[]{"package ", "import ", "public class", "public abstract", "public interface", "//"}) {
            int idx = stripped.indexOf(kw);
            if (idx >= 0 && (firstKeyword == -1 || idx < firstKeyword)) {
                firstKeyword = idx;
            }
        }
        if (firstKeyword >= 0) {
            stripped = stripped.substring(firstKeyword).strip();
        }

        return stripped.replaceAll("```[a-z]*\\n?", "").strip();
    }

    /**
     * Üretilen Java testini derlenebilirlik için deterministik normalize eder.
     * LLM prompt'a rağmen bunları sık atlar — koda güvenmek yerine burada garanti edilir:
     *  1. JUnit 4 import/annotation'ları JUnit 5'e çevrilir (pom'da yalnızca JUnit 5 var)
     *  2. "package com.testgen.generated;" satırı yoksa eklenir
     *  3. expectedClassName verilirse public class adı dosya adıyla eşitlenir
     *     (javac: public class adı dosya adıyla aynı olmak zorunda)
     */
    public static String normalizeGeneratedJavaTest(String content, String expectedClassName) {
        if (content == null || content.isBlank()) {
            return content;
        }
        String result = content
                .replace("import org.junit.Test;", "import org.junit.jupiter.api.Test;")
                .replace("import org.junit.Before;", "import org.junit.jupiter.api.BeforeEach;")
                .replace("import org.junit.After;", "import org.junit.jupiter.api.AfterEach;")
                .replace("import org.junit.BeforeClass;", "import org.junit.jupiter.api.BeforeAll;")
                .replace("import org.junit.AfterClass;", "import org.junit.jupiter.api.AfterAll;")
                .replace("import static org.junit.Assert.", "import static org.junit.jupiter.api.Assertions.")
                .replaceAll("(?m)^(\\s*)@Before(\\s*)$", "$1@BeforeEach$2")
                .replaceAll("(?m)^(\\s*)@After(\\s*)$", "$1@AfterEach$2")
                .replaceAll("(?m)^(\\s*)@BeforeClass(\\s*)$", "$1@BeforeAll$2")
                .replaceAll("(?m)^(\\s*)@AfterClass(\\s*)$", "$1@AfterAll$2")
                // JUnit 4 çağrı biçimi: "Assert.assertEquals(...)" → statik import'lu JUnit 5 biçimi.
                // Import'u çevirmek yetmiyordu; çağrı yerleri "cannot find symbol: variable Assert"
                // hatası veriyordu.
                .replaceAll("\\bAssert(?:ions)?\\.(assert\\w+|fail)\\s*\\(", "$1(");

        if (!result.stripLeading().startsWith("package ")) {
            result = "package com.testgen.generated;\n\n" + result;
        }

        result = ensureJUnitImports(result);
        result = ensureClassDeclaration(result, expectedClassName);

        if (expectedClassName != null && !expectedClassName.isBlank()) {
            String actual = extractClassName(result);
            if (!actual.equals(expectedClassName)) {
                result = result.replaceAll("\\b" + Pattern.quote(actual) + "\\b", expectedClassName);
            }
        }
        return result;
    }

    /**
     * Selenium testine özgü normalizasyon: JUnit normalizasyonuna ek olarak
     * kullanılan Selenium tiplerinin import'larını ve eksikse WebDriver
     * kurulum/kapanış iskeletini ekler.
     *
     * LLM sık sık `driver` değişkenini hiç tanımlamadan kullanıyor ("cannot find symbol:
     * variable driver") — üretilen projede DriverFactory hazır durduğu için bu iskelet
     * deterministik olarak eklenebilir.
     */
    public static String normalizeSeleniumTest(String content, String expectedClassName) {
        String result = normalizeGeneratedJavaTest(content, expectedClassName);
        if (result == null || result.isBlank()) {
            return result;
        }
        // Önce iskelet: WebDriver alanı eklendikten sonra import taraması onu da görsün
        result = ensureDriverScaffolding(result);
        result = ensureSeleniumImports(result);
        // İskelet @BeforeEach/@AfterEach eklemiş olabilir; JUnit import'larını tamamla
        return ensureJUnitImports(result);
    }

    /**
     * REST Assured testine özgü normalizasyon: JUnit normalizasyonuna ek olarak
     * given()/when()/then() ve RestAssured/Matchers tiplerinin import'larını ekler.
     * LLM bu statik import'ları sık atlıyor ve sınıf hiç derlenmiyordu.
     */
    public static String normalizeRestAssuredTest(String content, String expectedClassName) {
        String result = normalizeGeneratedJavaTest(content, expectedClassName);
        if (result == null || result.isBlank()) {
            return result;
        }
        result = repairRestAssuredHallucinations(result);

        List<String> missing = new ArrayList<>();
        boolean usesDsl = Pattern.compile("(?<![\\w.])(given|when|then)\\s*\\(").matcher(result).find();
        if (usesDsl && !result.contains("import static io.restassured.RestAssured.")) {
            missing.add("import static io.restassured.RestAssured.*;");
        }
        if (Pattern.compile("\\bRestAssured\\s*[.]").matcher(result).find()
                && !result.contains("import io.restassured.RestAssured;")) {
            missing.add("import io.restassured.RestAssured;");
        }
        if (Pattern.compile("\\bResponse\\s+\\w+").matcher(result).find()
                && !result.contains("import io.restassured.response.Response;")) {
            missing.add("import io.restassured.response.Response;");
        }
        if (Pattern.compile("\\bContentType\\s*[.]").matcher(result).find()
                && !result.contains("import io.restassured.http.ContentType;")) {
            missing.add("import io.restassured.http.ContentType;");
        }
        return missing.isEmpty() ? result : insertImports(result, missing);
    }

    /**
     * LLM'in uydurduğu, var olmayan REST Assured API'lerini gerçek karşılıklarına çevirir.
     *
     * Gözlenen iki hata tek başına derlemeyi kırıyor ve sınıftaki TÜM testleri
     * koşulamaz hâle getiriyordu:
     *  1. {@code io.restassured.matcher.Matchers} — böyle bir sınıf yok; kastedilen
     *     {@code org.hamcrest.Matchers}.
     *  2. {@code .then().timeLessThan(2000L)} — ValidatableResponse'ta böyle bir metot yok;
     *     doğrusu {@code .then().time(lessThan(2000L))}.
     */
    static String repairRestAssuredHallucinations(String content) {
        String result = content;

        // 1) Var olmayan Matchers sınıfı → hamcrest
        if (result.contains("io.restassured.matcher.Matchers")) {
            log.warn("Uydurma import onarıldı: io.restassured.matcher.Matchers → org.hamcrest.Matchers");
            result = result.replace("io.restassured.matcher.Matchers", "org.hamcrest.Matchers");
        }

        // 2) Var olmayan timeLessThan(...) → time(lessThan(...))
        Matcher timeCall = Pattern.compile("\\.timeLessThan\\s*\\(([^)]*)\\)").matcher(result);
        if (timeCall.find()) {
            log.warn("Uydurma metot onarıldı: timeLessThan(x) → time(lessThan(x))");
            result = timeCall.reset()
                    .replaceAll(".time(org.hamcrest.Matchers.lessThan($1))");
        }
        return result;
    }

    private static String ensureSeleniumImports(String content) {
        record Symbol(String name, String importLine) { }
        List<Symbol> symbols = List.of(
                new Symbol("WebDriver", "import org.openqa.selenium.WebDriver;"),
                new Symbol("WebElement", "import org.openqa.selenium.WebElement;"),
                new Symbol("By", "import org.openqa.selenium.By;"),
                new Symbol("Keys", "import org.openqa.selenium.Keys;"),
                new Symbol("WebDriverWait", "import org.openqa.selenium.support.ui.WebDriverWait;"),
                new Symbol("ExpectedConditions", "import org.openqa.selenium.support.ui.ExpectedConditions;"),
                new Symbol("FindBy", "import org.openqa.selenium.support.FindBy;"),
                new Symbol("PageFactory", "import org.openqa.selenium.support.PageFactory;"),
                new Symbol("Duration", "import java.time.Duration;"));

        List<String> missing = new ArrayList<>();
        for (Symbol s : symbols) {
            boolean used = Pattern.compile("\\b" + s.name() + "\\s*[.(<]").matcher(content).find()
                    || Pattern.compile("\\b" + s.name() + "\\s+\\w+").matcher(content).find()
                    || Pattern.compile("(?<![\\w.])@" + s.name() + "\\b").matcher(content).find();
            if (used && !content.contains(s.importLine())) {
                missing.add(s.importLine());
            }
        }
        return missing.isEmpty() ? content : insertImports(content, missing);
    }

    private static final Pattern DRIVER_FIELD = Pattern.compile("(?m)^\\s*(?:private|protected|public)?\\s*WebDriver\\s+driver\\b");

    private static String ensureDriverScaffolding(String content) {
        boolean usesDriver = Pattern.compile("\\bdriver\\s*\\.").matcher(content).find();
        if (!usesDriver || DRIVER_FIELD.matcher(content).find()) {
            return content;
        }

        // [ \t]* kullanılmalı: \s* satır sonlarını da yutup girintiye newline karıştırıyor
        Matcher classStart = Pattern.compile("(?m)^([ \\t]*)public\\s+(?:abstract\\s+)?class\\s+\\w+[^{]*\\{")
                .matcher(content);
        if (!classStart.find()) {
            return content;
        }

        String indent = classStart.group(1) + "    ";
        // @FindBy kullanıldıysa PageFactory init'i şart; aksi hâlde alanlar null kalır
        String pageFactoryInit = Pattern.compile("(?<![\\w.])@FindBy\\b").matcher(content).find()
                ? indent + "    PageFactory.initElements(driver, this);\n"
                : "";
        String scaffolding = "\n"
                + indent + "private WebDriver driver;\n\n"
                + indent + "@BeforeEach\n"
                + indent + "public void setUpDriver() {\n"
                + indent + "    driver = DriverFactory.createDriver();\n"
                + pageFactoryInit
                + indent + "}\n\n"
                + indent + "@AfterEach\n"
                + indent + "public void tearDownDriver() {\n"
                + indent + "    if (driver != null) {\n"
                + indent + "        driver.quit();\n"
                + indent + "    }\n"
                + indent + "}\n";

        int insertAt = classStart.end();
        return content.substring(0, insertAt) + scaffolding + content.substring(insertAt);
    }

    /**
     * Kullanılan JUnit 5 annotation ve assertion'ları için eksik import'ları ekler.
     * LLM sık sık @Test/@BeforeEach yazıp import'unu atlar; bu durumda üretilen sınıf
     * hiç derlenmez ("cannot find symbol: class Test"). Import'lar package satırının
     * hemen altına, mevcut import bloğunun başına eklenir.
     */
    private static String ensureJUnitImports(String content) {
        List<String> missing = new ArrayList<>();

        for (String annotation : new String[]{
                "Test", "BeforeEach", "AfterEach", "BeforeAll", "AfterAll", "Disabled", "DisplayName"}) {
            // Satır başı şartı yok: LLM "@AfterEach public void ..." gibi aynı satıra da yazabiliyor
            boolean used = Pattern.compile("(?<![\\w.])@" + annotation + "\\b").matcher(content).find();
            boolean imported = content.contains("import org.junit.jupiter.api." + annotation + ";")
                    || content.contains("import org.junit.jupiter.api.*;");
            if (used && !imported) {
                missing.add("import org.junit.jupiter.api." + annotation + ";");
            }
        }

        boolean usesAssertion = Pattern.compile(
                "\\b(assertEquals|assertNotEquals|assertTrue|assertFalse|assertNull|assertNotNull"
                        + "|assertThrows|assertArrayEquals|assertSame|assertAll|fail)\\s*\\(")
                .matcher(content).find();
        boolean assertionImported = content.contains("import static org.junit.jupiter.api.Assertions.");
        if (usesAssertion && !assertionImported) {
            missing.add("import static org.junit.jupiter.api.Assertions.*;");
        }

        // java.util: LLM List/Map/ArrayList kullanıp import'unu atlayınca sınıf derlenmiyordu
        record JavaUtil(String name, String importLine) { }
        for (JavaUtil u : List.of(
                new JavaUtil("List", "import java.util.List;"),
                new JavaUtil("ArrayList", "import java.util.ArrayList;"),
                new JavaUtil("Map", "import java.util.Map;"),
                new JavaUtil("HashMap", "import java.util.HashMap;"),
                new JavaUtil("Set", "import java.util.Set;"),
                new JavaUtil("HashSet", "import java.util.HashSet;"),
                new JavaUtil("Arrays", "import java.util.Arrays;"),
                new JavaUtil("Optional", "import java.util.Optional;"),
                new JavaUtil("Duration", "import java.time.Duration;"),
                // Ölçülen koşumda üretilen bir sınıf yalnızca bu import eksik olduğu için derlenmedi
                new JavaUtil("TimeUnit", "import java.util.concurrent.TimeUnit;"))) {
            boolean used = Pattern.compile("\\b" + u.name() + "\\s*[.<(]").matcher(content).find()
                    || Pattern.compile("\\b" + u.name() + "\\s+\\w+\\s*[=;)]").matcher(content).find();
            if (used && !content.contains(u.importLine())) {
                missing.add(u.importLine());
            }
        }

        // Hamcrest: LLM assertThat + matcher'ları sık kullanıyor ama import'unu atlıyor
        if (Pattern.compile("\\bassertThat\\s*\\(").matcher(content).find()
                && !content.contains("import static org.hamcrest.MatcherAssert.")) {
            missing.add("import static org.hamcrest.MatcherAssert.assertThat;");
        }
        boolean usesMatcher = Pattern.compile(
                "\\b(containsString|notNullValue|nullValue|equalTo|hasItem|hasSize|greaterThan"
                        + "|lessThan|instanceOf|startsWith|endsWith|isA)\\s*\\(")
                .matcher(content).find();
        if (usesMatcher && !content.contains("import static org.hamcrest.Matchers.")) {
            missing.add("import static org.hamcrest.Matchers.*;");
        }

        return missing.isEmpty() ? content : insertImports(content, missing);
    }

    /**
     * Sınıf bildirimi olmayan çıktıyı bir sınıf gövdesine sarar.
     *
     * LLM bazen yalnızca metot parçaları döndürüyor; javac bunu "implicitly declared class"
     * sanıp {@code (use -source 25 or higher)} hatasıyla derlemeyi kırıyordu.
     * package/import satırları dışarıda bırakılır, kalan gövde sınıfa alınır.
     */
    private static String ensureClassDeclaration(String content, String expectedClassName) {
        if (content == null || content.isBlank()
                || Pattern.compile("(?m)^\\s*(public\\s+|abstract\\s+|final\\s+)*(class|interface|enum|record)\\s+\\w+")
                        .matcher(content).find()) {
            return content;
        }

        String className = expectedClassName == null || expectedClassName.isBlank()
                ? "GeneratedApiTest" : expectedClassName;

        String[] lines = content.split("\\r?\\n", -1);
        List<String> header = new ArrayList<>();
        List<String> body = new ArrayList<>();
        boolean inHeader = true;
        for (String line : lines) {
            String trimmed = line.trim();
            if (inHeader && (trimmed.isEmpty() || trimmed.startsWith("package ") || trimmed.startsWith("import "))) {
                header.add(line);
            } else {
                inHeader = false;
                body.add(line);
            }
        }

        StringBuilder sb = new StringBuilder(String.join("\n", header).stripTrailing());
        sb.append("\n\npublic class ").append(className).append(" {\n");
        for (String line : body) {
            sb.append(line.isBlank() ? "" : "    " + line).append("\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    /** Import satırlarını package'ın altına / mevcut import bloğunun başına ekler. */
    private static String insertImports(String content, List<String> imports) {
        String[] lines = content.split("\\r?\\n", -1);
        int insertAt = 0;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("package ")) {
                insertAt = i + 1;
            } else if (trimmed.startsWith("import ")) {
                insertAt = i;
                break;
            } else if (!trimmed.isEmpty() && !trimmed.startsWith("//")) {
                break;
            }
        }

        List<String> out = new ArrayList<>(java.util.Arrays.asList(lines).subList(0, insertAt));
        if (insertAt > 0 && !out.isEmpty() && !out.get(out.size() - 1).isBlank()) {
            out.add("");
        }
        out.addAll(imports);
        out.addAll(java.util.Arrays.asList(lines).subList(insertAt, lines.length));
        return String.join("\n", out);
    }

    /**
     * Endpoint'ten dosya/sınıf adı üretir: "GET" + "/api/v1/tests" → "Getapi_v1_testsTest".
     *
     * Girdi tam URL de olabilir (HAR/Collection akışları url alanını geçiriyor); bu durumda
     * şema ve host atılır. Aksi hâlde "Gethttp:_localhost:8080_..." gibi ':' içeren —
     * bazı dosya sistemlerinde ve Java sınıf adlarında geçersiz — adlar üretiliyordu.
     */
    public static String buildTestName(String pathOrUrl, String method) {
        String m = method == null || method.isBlank() ? "Get" : method.trim();
        m = m.substring(0, 1).toUpperCase(Locale.ROOT) + m.substring(1).toLowerCase(Locale.ROOT);

        String path = pathOrUrl == null ? "" : pathOrUrl.trim();
        if (path.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            try {
                java.net.URI uri = java.net.URI.create(path);
                String p = uri.getPath();
                path = (p == null || p.isBlank()) ? uri.getHost() : p;
            } catch (IllegalArgumentException e) {
                path = path.replaceFirst("(?i)^[a-z][a-z0-9+.-]*://", "");
            }
        }

        String slug = (path == null ? "" : path)
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");

        return m + slug + "Test";
    }

    /** İçerikteki public class adını döner (bulunamazsa zaman damgalı fallback). */
    public static String publicClassName(String javaContent) {
        return extractClassName(javaContent);
    }

    /**
     * LLM çıktısından birden fazla Java sınıfını ayıklar.
     * Her ```java ... ``` bloğunu ayrı bir sınıf olarak döndürür.
     */
    public static List<JavaClassContent> splitJavaClasses(String raw) {
        List<JavaClassContent> result = new ArrayList<>();

        Matcher m = JAVA_BLOCK.matcher(raw);
        while (m.find()) {
            String classContent = m.group(1).strip();
            result.addAll(splitJavaBlock(classContent));
        }

        return result;
    }

    private static List<JavaClassContent> splitJavaBlock(String block) {
        List<JavaClassContent> result = new ArrayList<>();
        Matcher starts = CLASS_START.matcher(block);
        List<Integer> positions = new ArrayList<>();
        while (starts.find()) {
            positions.add(starts.start());
        }

        if (positions.size() <= 1) {
            result.add(new JavaClassContent(extractClassName(block), block));
            return result;
        }

        String sharedPrefix = block.substring(0, positions.get(0)).stripTrailing();
        for (int i = 0; i < positions.size(); i++) {
            int start = positions.get(i);
            int end = i + 1 < positions.size() ? positions.get(i + 1) : block.length();
            String content = block.substring(start, end).strip();
            if (!sharedPrefix.isBlank()) {
                content = sharedPrefix + "\n\n" + content;
            }
            result.add(new JavaClassContent(extractClassName(content), content));
        }
        return result;
    }

    private static String extractClassName(String javaContent) {
        Matcher m = CLASS_NAME.matcher(javaContent);
        if (m.find()) {
            return m.group(1);
        }
        return "GeneratedTest_" + System.currentTimeMillis();
    }
}
