package com.testgen.report;

import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Üretilen test içeriğinden GERÇEK senaryo ve adımları çıkarır.
 *
 * Önceden Cucumber raporu ve e-posta yalnızca "N senaryo çalıştı" bilgisini
 * gösteriyordu; senaryo adları "TestAdi [1/6]" gibi uydurma, adım ise her zaman
 * tek bir "test senaryosu çalıştırıldı" satırıydı. Burada:
 *
 *  - KARATE          → Gherkin ayrıştırılır: Scenario/Scenario Outline adları,
 *                      tag'ler ve Given/When/Then/And/But/* adımları.
 *  - SELENIUM /      → @Test metotları senaryo, metot gövdesindeki anlamlı
 *    REST_ASSURED      ifadeler adım olarak alınır.
 *
 * Ayrıştırma başarısız olursa boş liste döner; çağıran taraf eski özet
 * davranışına düşer.
 */
public final class ScenarioExtractor {

    private static final int MAX_STEPS_PER_SCENARIO = 40;

    private static final Pattern GHERKIN_SCENARIO =
            Pattern.compile("^(Scenario Outline|Scenario|Örnek|Senaryo):\\s*(.*)$");
    private static final Pattern GHERKIN_STEP =
            Pattern.compile("^(Given|When|Then|And|But|\\*)\\s+(.*)$");

    private static final Pattern JAVA_TEST_METHOD =
            Pattern.compile("(?s)@Test\\b.*?\\b(?:public|protected|private)?\\s*\\w[\\w<>\\[\\], ]*\\s+"
                    + "(\\w+)\\s*\\([^)]*\\)\\s*(?:throws [^{]+)?\\{");

    private ScenarioExtractor() {
    }

    /** Senaryo adı + adımları taşıyan salt-okunur kayıt. */
    public record Scenario(String name, List<String> tags, List<String> steps) {
        public Scenario {
            tags = tags == null ? List.of() : List.copyOf(tags);
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    public static List<Scenario> extract(GeneratedTestCase testCase) {
        if (testCase == null || testCase.getTestContent() == null || testCase.getTestContent().isBlank()) {
            return List.of();
        }
        return testCase.getFramework() == TestFramework.KARATE
                ? extractGherkin(testCase.getTestContent())
                : extractJavaTests(testCase.getTestContent());
    }

    // ─────────────────────────────────────────────────────────
    // Karate / Gherkin
    // ─────────────────────────────────────────────────────────
    static List<Scenario> extractGherkin(String content) {
        List<Scenario> scenarios = new ArrayList<>();

        String currentName = null;
        List<String> pendingTags = new ArrayList<>();
        List<String> currentTags = new ArrayList<>();
        List<String> currentSteps = new ArrayList<>();
        List<String> backgroundSteps = new ArrayList<>();
        boolean inBackground = false;

        for (String rawLine : content.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            if (line.startsWith("@")) {
                pendingTags.addAll(List.of(line.split("\\s+")));
                continue;
            }

            if (line.startsWith("Feature:")) {
                pendingTags.clear();
                continue;
            }

            if (line.startsWith("Background:")) {
                flush(scenarios, currentName, currentTags, currentSteps);
                currentName = null;
                inBackground = true;
                backgroundSteps.clear();
                pendingTags.clear();
                continue;
            }

            Matcher scenario = GHERKIN_SCENARIO.matcher(line);
            if (scenario.matches()) {
                flush(scenarios, currentName, currentTags, currentSteps);
                inBackground = false;
                String title = scenario.group(2).trim();
                currentName = title.isEmpty() ? scenario.group(1) : title;
                currentTags = new ArrayList<>(pendingTags);
                pendingTags.clear();
                // Background adımları her senaryonun başında koşar — raporda da öyle görünsün
                currentSteps = new ArrayList<>(backgroundSteps);
                continue;
            }

            Matcher step = GHERKIN_STEP.matcher(line);
            if (step.matches()) {
                String text = normalizeStep(step.group(1), step.group(2));
                if (inBackground) {
                    addStep(backgroundSteps, text);
                } else if (currentName != null) {
                    addStep(currentSteps, text);
                }
            }
        }

        flush(scenarios, currentName, currentTags, currentSteps);
        return List.copyOf(scenarios);
    }

    private static String normalizeStep(String keyword, String body) {
        String kw = "*".equals(keyword) ? "*" : keyword;
        return (kw + " " + body.trim()).trim();
    }

    private static void flush(List<Scenario> target, String name,
                              List<String> tags, List<String> steps) {
        if (name != null && !name.isBlank()) {
            target.add(new Scenario(name, tags, steps));
        }
    }

    // ─────────────────────────────────────────────────────────
    // Selenium / REST Assured (JUnit 5)
    // ─────────────────────────────────────────────────────────
    static List<Scenario> extractJavaTests(String content) {
        List<Scenario> scenarios = new ArrayList<>();
        Matcher m = JAVA_TEST_METHOD.matcher(content);

        while (m.find()) {
            String methodName = m.group(1);
            String body = readMethodBody(content, m.end() - 1);
            scenarios.add(new Scenario(humanize(methodName), List.of("@Test"), javaSteps(body)));
        }
        return List.copyOf(scenarios);
    }

    /** Açılış süslü parantezinden başlayarak dengeli kapanışa kadar gövdeyi okur. */
    private static String readMethodBody(String content, int openBraceIndex) {
        int depth = 0;
        for (int i = openBraceIndex; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return content.substring(openBraceIndex + 1, i);
                }
            }
        }
        return content.substring(Math.min(openBraceIndex + 1, content.length()));
    }

    private static List<String> javaSteps(String body) {
        List<String> steps = new ArrayList<>();
        for (String rawLine : body.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("/*")
                    || line.startsWith("*") || line.equals("{") || line.equals("}")) {
                continue;
            }
            addStep(steps, line);
        }
        return steps;
    }

    private static void addStep(List<String> steps, String text) {
        if (steps.size() < MAX_STEPS_PER_SCENARIO && !text.isBlank()) {
            steps.add(text);
        }
    }

    /** "dashboardBasligiGorunmeli" → "Dashboard basligi gorunmeli" */
    static String humanize(String methodName) {
        String spaced = methodName
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replace('_', ' ')
                .replaceAll("\\s+", " ")
                .trim()
                // Locale.ROOT şart: Türkçe locale'de 'I' → 'ı' olur
                .toLowerCase(java.util.Locale.ROOT);
        return spaced.isEmpty() ? methodName
                : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
