package com.testgen.generator;

import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestCategory;
import com.testgen.model.TestDesignTechnique;
import com.testgen.model.TestLevel;
import com.testgen.model.TestType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Üretilen her case'e ISTQB sınıflandırması yazar.
 *
 * NEDEN GEREKLİ: {@link TestCategory}, {@link TestLevel} ve {@link TestDesignTechnique}
 * alanları veri modelinde tanımlıydı ama üretim hattında HİÇ DOLDURULMUYORDU. Sonuç:
 * "fonksiyonel testler mi düşüyor, fonksiyonel olmayanlar mı" sorusunun ölçülmüş bir
 * cevabı yoktu. Sınıflandırma olmadan hangi üretim stratejisinin işe yaradığı da
 * kanıtlanamaz.
 *
 * Sınıflandırma TAHMİN DEĞİL, çıkarımdır: prompt zaten senaryo başlıklarına
 * {@code [SMOKE][P0_BLOCKER][EP]} biçiminde etiket yazdırıyor; burada o etiketler okunur.
 * Etiket yoksa içeriğin kaynağına göre güvenli bir varsayılana düşülür.
 */
@Slf4j
@Component
public class TestCaseClassifier {

    /** Prompt'un ürettiği ISTQB etiketi: [SMOKE][P0_BLOCKER][EP] */
    private static final Pattern ISTQB_TAG = Pattern.compile("\\[([A-Z_0-9]+)]");

    /**
     * ISO/IEC 25010 kalite karakteristiklerine karşılık gelen kategoriler.
     * Fonksiyonel / fonksiyonel olmayan ayrımı bu kümeyle yapılır.
     */
    private static final java.util.Set<TestCategory> NON_FUNCTIONAL = java.util.Set.of(
            TestCategory.PERFORMANCE, TestCategory.SECURITY, TestCategory.RELIABILITY,
            TestCategory.COMPATIBILITY, TestCategory.USABILITY);

    /** Case'in kategorisini, seviyesini ve tasarım tekniğini yazar. */
    public void classify(GeneratedTestCase testCase, TestType testType) {
        if (testCase == null) {
            return;
        }
        String content = testCase.getTestContent() == null ? "" : testCase.getTestContent();
        String name = testCase.getTestName() == null ? "" : testCase.getTestName();

        testCase.setTestCategory(category(content, name));
        testCase.setTestLevel(level(testType));
        testCase.setTestDesignTechnique(technique(testCase, content));
    }

    /** Fonksiyonel olmayan mı? Metrik etiketlerinde ve raporlamada kullanılır. */
    public static boolean isNonFunctional(TestCategory category) {
        return category != null && NON_FUNCTIONAL.contains(category);
    }

    // ─────────────────────────────────────────────────────────

    private TestCategory category(String content, String name) {
        // 1) Prompt'un yazdırdığı etiketten oku — en güvenilir kaynak
        Matcher m = ISTQB_TAG.matcher(content);
        while (m.find()) {
            TestCategory hit = matchCategory(m.group(1));
            if (hit != null) {
                return hit;
            }
        }
        // 2) Etiket yoksa test adından çıkar (prompt metot adlarını da yönlendiriyor)
        TestCategory byName = matchCategory(name);
        if (byName != null) {
            return byName;
        }
        // 3) Hiçbiri yoksa: gözlenen davranışı doğrulayan temel test
        return TestCategory.SMOKE;
    }

    private TestCategory matchCategory(String token) {
        String t = token == null ? "" : token.toUpperCase(Locale.ROOT);
        for (TestCategory c : TestCategory.values()) {
            if (t.contains(c.name())) {
                return c;
            }
        }
        // Prompt'ta kullanılan ama enum adıyla birebir örtüşmeyen yaygın yazımlar
        if (t.contains("NEGATIF")) return TestCategory.NEGATIVE;
        if (t.contains("PERF")) return TestCategory.PERFORMANCE;
        if (t.contains("AUTH") || t.contains("UNAUTHORIZED")) return TestCategory.SECURITY;
        if (t.contains("BOUNDARY") || t.contains("EDGE")) return TestCategory.BOUNDARY;
        return null;
    }

    /**
     * Seviye test tipinden gelir: API testi bileşenler arası arayüzü doğrular
     * (INTEGRATION), web testi tüm sistemi uçtan uca kullanır (SYSTEM).
     */
    private TestLevel level(TestType testType) {
        return testType == TestType.FRONTEND_WEB ? TestLevel.SYSTEM : TestLevel.INTEGRATION;
    }

    private TestDesignTechnique technique(GeneratedTestCase testCase, String content) {
        // Gözlemden deterministik üretilen içerik: değerler gerçek yanıttan okunan bir
        // kontrol listesidir — LLM tasarımı değildir.
        if (testCase.isDeterministic()) {
            return TestDesignTechnique.CHECKLIST_BASED;
        }
        if (testCase.getParentCaseId() != null) {
            return TestDesignTechnique.AI_SELF_HEALED;
        }
        // Prompt teknik kısaltmasını da yazdırıyor: [EP] [BVA] [DT] [ST] [EG]
        if (content.contains("[BVA]")) return TestDesignTechnique.BOUNDARY_VALUE_ANALYSIS;
        if (content.contains("[EP]")) return TestDesignTechnique.EQUIVALENCE_PARTITIONING;
        if (content.contains("[DT]")) return TestDesignTechnique.DECISION_TABLE;
        if (content.contains("[ST]")) return TestDesignTechnique.STATE_TRANSITION;
        if (content.contains("[EG]")) return TestDesignTechnique.ERROR_GUESSING;
        return TestDesignTechnique.AI_GENERATED;
    }
}
