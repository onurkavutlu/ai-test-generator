package com.testgen.generator;

import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestCategory;
import com.testgen.model.TestDesignTechnique;
import com.testgen.model.TestLevel;
import com.testgen.model.TestType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ISTQB sınıflandırması — alanlar modelde tanımlıydı ama üretimde hiç doldurulmuyordu.
 * Sınıflandırma olmadan "hangi test sınıfı düşüyor" ölçülemiyor.
 */
class TestCaseClassifierTest {

    private final TestCaseClassifier classifier = new TestCaseClassifier();

    private GeneratedTestCase caseWith(String content) {
        return GeneratedTestCase.builder().testName("Test").testContent(content).build();
    }

    @Test
    @DisplayName("Prompt'un yazdığı ISTQB etiketinden kategori okunur")
    void readsCategoryFromIstqbTag() {
        var tc = caseWith("Scenario: [SECURITY][P0_BLOCKER][EG] Yetkisiz erisim");
        classifier.classify(tc, TestType.BACKEND_API);

        assertEquals(TestCategory.SECURITY, tc.getTestCategory());
        assertEquals(TestDesignTechnique.ERROR_GUESSING, tc.getTestDesignTechnique());
    }

    @Test
    @DisplayName("Sınır değer etiketi tekniğe çevrilir")
    void boundaryTagMapsToBva() {
        var tc = caseWith("Scenario: [BOUNDARY][P2_MAJOR][BVA] Sinir degerleri");
        classifier.classify(tc, TestType.BACKEND_API);

        assertEquals(TestCategory.BOUNDARY, tc.getTestCategory());
        assertEquals(TestDesignTechnique.BOUNDARY_VALUE_ANALYSIS, tc.getTestDesignTechnique());
    }

    @Test
    @DisplayName("Etiket yoksa test adından çıkarılır")
    void fallsBackToTestName() {
        var tc = GeneratedTestCase.builder()
                .testName("performanceResponseTime").testContent("given().get()").build();
        classifier.classify(tc, TestType.BACKEND_API);

        assertEquals(TestCategory.PERFORMANCE, tc.getTestCategory());
    }

    @Test
    @DisplayName("Hiçbir ipucu yoksa SMOKE varsayılır")
    void defaultsToSmoke() {
        var tc = caseWith("Then status 200");
        classifier.classify(tc, TestType.BACKEND_API);

        assertEquals(TestCategory.SMOKE, tc.getTestCategory());
    }

    @Test
    @DisplayName("Deterministik içerik kontrol listesi tekniğidir, AI değil")
    void deterministicIsChecklistNotAi() {
        var tc = caseWith("Then status 200");
        tc.setDeterministic(true);
        classifier.classify(tc, TestType.BACKEND_API);

        assertEquals(TestDesignTechnique.CHECKLIST_BASED, tc.getTestDesignTechnique());
    }

    @Test
    @DisplayName("İyileştirilmiş case self-heal tekniğiyle işaretlenir")
    void healedCaseIsMarked() {
        var tc = caseWith("Then status 200");
        tc.setParentCaseId("parent-1");
        classifier.classify(tc, TestType.BACKEND_API);

        assertEquals(TestDesignTechnique.AI_SELF_HEALED, tc.getTestDesignTechnique());
    }

    @Test
    @DisplayName("Seviye test tipinden gelir")
    void levelComesFromTestType() {
        var api = caseWith("x");
        classifier.classify(api, TestType.BACKEND_API);
        assertEquals(TestLevel.INTEGRATION, api.getTestLevel());

        var web = caseWith("x");
        classifier.classify(web, TestType.FRONTEND_WEB);
        assertEquals(TestLevel.SYSTEM, web.getTestLevel());
    }

    @Test
    @DisplayName("Fonksiyonel / fonksiyonel olmayan ayrımı ISO 25010'a göre yapılır")
    void nonFunctionalSplitFollowsIso25010() {
        assertTrue(TestCaseClassifier.isNonFunctional(TestCategory.PERFORMANCE));
        assertTrue(TestCaseClassifier.isNonFunctional(TestCategory.SECURITY));
        assertTrue(TestCaseClassifier.isNonFunctional(TestCategory.RELIABILITY));

        assertFalse(TestCaseClassifier.isNonFunctional(TestCategory.SMOKE));
        assertFalse(TestCaseClassifier.isNonFunctional(TestCategory.REGRESSION));
        assertFalse(TestCaseClassifier.isNonFunctional(TestCategory.BOUNDARY));
        assertFalse(TestCaseClassifier.isNonFunctional(null));
    }
}
