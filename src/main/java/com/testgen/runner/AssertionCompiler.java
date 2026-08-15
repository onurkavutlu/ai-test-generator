package com.testgen.runner;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link HttpAssertion} listesini koşulabilir test koduna çevirir.
 *
 * NEDEN VAR: Runner'da gözlenen yanıttan türetilen doğrulamalar bugüne kadar yalnızca
 * ekranda kalıyordu; üretilen deterministik test ise sadece iki şey doğruluyordu
 * (status + sabit 10 sn süre sınırı). Bu sınıf ikisini birleştirir — Runner'da
 * gördüğünüz doğrulama listesi, üretilen testin İÇERİĞİ olur.
 *
 * Postman'de yazdığınız {@code pm.test(...)} yalnızca Postman/Newman içinde koşar;
 * buradaki aynı liste hem Karate feature'ına hem REST Assured sınıfına derlenir.
 *
 * Değerler gözlemden geldiği için üretilen kod yakalama anında geçer.
 */
public final class AssertionCompiler {

    private AssertionCompiler() {}

    // ─────────────────────────────────────────────────────────
    // Karate
    // ─────────────────────────────────────────────────────────

    /**
     * Karate adım satırları (girintisiz). Çağıran senaryo gövdesine yerleştirir.
     * İlk satır {@code Then}, kalanlar {@code And} ile başlar — Gherkin bunu gerektirir.
     */
    public static List<String> toKarateSteps(List<HttpAssertion> assertions) {
        List<String> steps = new ArrayList<>();
        if (assertions == null) {
            return steps;
        }
        for (HttpAssertion a : assertions) {
            if (!a.enabled()) {
                continue;
            }
            String step = switch (a.type()) {
                case STATUS -> "status " + a.expected();
                case HEADER -> "match header " + a.path() + " contains '" + a.expected() + "'";
                case RESPONSE_TIME -> "assert responseTime < " + a.expected();
                case JSON_ARRAY_SIZE -> "match " + karatePath(a.path()) + " == '#[" + a.expected() + "]'";
                case JSON_FIELD_TYPE -> "match " + karatePath(a.path()) + " == '" + a.expected() + "'";
                case JSON_FIELD_EXISTS -> "match " + karatePath(a.path()) + " == '#notnull'";
            };
            steps.add((steps.isEmpty() ? "Then " : "And ") + step);
        }
        return steps;
    }

    /** {@code $.items[0].id} → {@code response.items[0].id}; kök {@code $} → {@code response}. */
    static String karatePath(String jsonPath) {
        if (jsonPath == null || jsonPath.isBlank() || "$".equals(jsonPath)) {
            return "response";
        }
        return jsonPath.startsWith("$.")
                ? "response." + jsonPath.substring(2)
                : "response" + jsonPath.substring(1);
    }

    // ─────────────────────────────────────────────────────────
    // REST Assured
    // ─────────────────────────────────────────────────────────

    /**
     * {@code .then()} zincirine eklenecek ifadeler (başında nokta, sonunda noktalı virgül yok).
     * Hamcrest matcher'ları {@code org.hamcrest.Matchers.*} statik import'uyla çözülür.
     */
    public static List<String> toRestAssuredStatements(List<HttpAssertion> assertions) {
        List<String> statements = new ArrayList<>();
        if (assertions == null) {
            return statements;
        }
        for (HttpAssertion a : assertions) {
            if (!a.enabled()) {
                continue;
            }
            switch (a.type()) {
                case STATUS -> statements.add(".statusCode(" + a.expected() + ")");
                case HEADER -> statements.add(".header(\"" + a.path() + "\", containsString(\""
                        + a.expected() + "\"))");
                // ValidatableResponse.time(Matcher<Long>) — timeLessThan diye bir metot YOKTUR
                case RESPONSE_TIME -> statements.add(".time(lessThan(" + a.expected() + "L))");
                case JSON_ARRAY_SIZE -> {
                    String g = gpath(a.path());
                    statements.add(".body(\"" + (g.isEmpty() ? "" : g + ".") + "size()\", equalTo("
                            + a.expected() + "))");
                }
                case JSON_FIELD_TYPE -> statements.add(".body(\"" + gpath(a.path()) + "\", "
                        + hamcrestForType(a.expected()) + ")");
                case JSON_FIELD_EXISTS -> statements.add(".body(\"" + gpath(a.path())
                        + "\", notNullValue())");
            }
        }
        return statements;
    }

    /** {@code $.items[0].id} → {@code items[0].id} (REST Assured GPath kökü yazmaz). */
    static String gpath(String jsonPath) {
        // Kok icin GPath ifadesi BOSTUR: ".body(\"size()\", ...)" dogru,
        // ".body(\"$.size()\", ...)" gecersizdir.
        if (jsonPath == null || jsonPath.isBlank() || "$".equals(jsonPath)) {
            return "";
        }
        return jsonPath.startsWith("$.") ? jsonPath.substring(2) : jsonPath.substring(1);
    }

    /** Karate fuzzy tipini Hamcrest matcher'ına çevirir. */
    private static String hamcrestForType(String karateType) {
        return switch (karateType == null ? "" : karateType) {
            case "#string" -> "instanceOf(String.class)";
            case "#number" -> "instanceOf(Number.class)";
            case "#boolean" -> "instanceOf(Boolean.class)";
            case "#null" -> "nullValue()";
            default -> "notNullValue()";
        };
    }

    // ─────────────────────────────────────────────────────────
    // Prompt bloğundan geri okuma
    // ─────────────────────────────────────────────────────────

    private static final Pattern FACT_STATUS = Pattern.compile("(?m)^status:\\s*(\\d{3})$");
    private static final Pattern FACT_HEADER = Pattern.compile("(?m)^header\\s+(\\S+):\\s*(.+)$");
    private static final Pattern FACT_TIME = Pattern.compile("(?m)^responseTime\\s*<\\s*(\\d+)\\s*ms$");
    private static final Pattern FACT_ARRAY = Pattern.compile("(?m)^(\\$\\S*)\\s*:\\s*array\\[(\\d+)]$");
    private static final Pattern FACT_FIELD = Pattern.compile("(?m)^(\\$\\S*)\\s*:\\s*(#\\w+)$");

    /**
     * {@link ResponseAssertionDeriver#toPromptFacts} bloğunu geri okur.
     *
     * NEDEN GEREKLİ: Üretim akışında gözlem, üreticiye {@code additionalContext} METNİ olarak
     * taşınıyor (mevcut mimari). Türetilen gerçekleri deterministik teste çevirebilmek için
     * bu metinden geri okumak gerekiyor. Biçim bizim ürettiğimiz biçimdir; gidiş-dönüş test
     * altındadır.
     */
    public static List<HttpAssertion> fromPromptFacts(String context) {
        List<HttpAssertion> out = new ArrayList<>();
        if (context == null || !context.contains("## OBSERVED FACTS")) {
            return out;
        }
        String block = context.substring(context.indexOf("## OBSERVED FACTS"));

        Matcher m = FACT_STATUS.matcher(block);
        if (m.find()) {
            out.add(HttpAssertion.of(HttpAssertion.Type.STATUS, null,
                    HttpAssertion.Operator.EQUALS, m.group(1), "Durum kodu " + m.group(1)));
        }
        m = FACT_HEADER.matcher(block);
        while (m.find()) {
            out.add(HttpAssertion.of(HttpAssertion.Type.HEADER, m.group(1),
                    HttpAssertion.Operator.CONTAINS, m.group(2).trim(), "Header " + m.group(1)));
        }
        m = FACT_TIME.matcher(block);
        if (m.find()) {
            out.add(HttpAssertion.of(HttpAssertion.Type.RESPONSE_TIME, null,
                    HttpAssertion.Operator.LESS_THAN, m.group(1), "Yanıt süresi"));
        }
        m = FACT_ARRAY.matcher(block);
        while (m.find()) {
            out.add(HttpAssertion.of(HttpAssertion.Type.JSON_ARRAY_SIZE, m.group(1),
                    HttpAssertion.Operator.EQUALS, m.group(2), "Dizi boyutu " + m.group(1)));
        }
        m = FACT_FIELD.matcher(block);
        while (m.find()) {
            out.add(HttpAssertion.of(HttpAssertion.Type.JSON_FIELD_TYPE, m.group(1),
                    HttpAssertion.Operator.TYPE_IS, m.group(2), "Alan tipi " + m.group(1)));
        }
        return out;
    }
}
