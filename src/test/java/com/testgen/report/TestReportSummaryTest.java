package com.testgen.report;

import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.model.TestRunStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E-posta ve Allure raporunun beslendiği özet nesnesi.
 *
 * <p>Buradaki türetilmiş alanlar doğrudan ekibin gördüğü rapora yazılıyor. Yanlış
 * hesaplanan bir sayı sessiz yanlış bilgilendirmedir: {@code brokenTests} negatife
 * düşerse toplam tutmaz, {@code getOverallStatus} yanlış olursa başarısız koşum
 * "PASSED" görünür ve kimse bakmaz.
 */
class TestReportSummaryTest {

    private GeneratedTestCase testCase(String name, TestRunStatus status, String summary) {
        return GeneratedTestCase.builder()
                .testName(name)
                .framework(TestFramework.KARATE)
                .runStatus(status)
                .testSummary(summary)
                .build();
    }

    @Nested
    @DisplayName("Fabrika — durum sayımı")
    class FactoryCounting {

        @Test
        @DisplayName("Durumlara göre sayımlar doğru dağıtılır")
        void countsByStatus() {
            var summary = TestReportSummary.from("req-1", List.of(
                    testCase("A", TestRunStatus.PASSED, null),
                    testCase("B", TestRunStatus.PASSED, null),
                    testCase("C", TestRunStatus.FAILED, null),
                    testCase("D", TestRunStatus.SKIPPED, null)), "http://allure");

            assertEquals(4, summary.getTotalTests());
            assertEquals(2, summary.getPassedTests());
            assertEquals(1, summary.getFailedTests());
            assertEquals(1, summary.getSkippedTests());
            assertEquals(0, summary.getBrokenTests());
        }

        /**
         * Hiç koşulmamış case'ler (runStatus null) hiçbir sayıma girmez ve "broken"
         * olarak artakalır. Bu sayı NEGATİF olamaz — olursa toplam tutmaz ve rapor
         * matematiksel olarak saçmalar.
         */
        @Test
        @DisplayName("Koşulmamış case'ler broken sayılır ve negatife düşmez")
        void unrunCasesBecomeBrokenNeverNegative() {
            var summary = TestReportSummary.from("req-1", List.of(
                    testCase("A", TestRunStatus.PASSED, null),
                    testCase("B", null, null),
                    testCase("C", null, null)), "http://allure");

            assertEquals(3, summary.getTotalTests());
            assertEquals(2, summary.getBrokenTests());
            assertTrue(summary.getBrokenTests() >= 0);
        }

        @Test
        @DisplayName("Boş case listesi sıfır sayımlarla özet üretir")
        void emptyCaseListProducesZeroes() {
            var summary = TestReportSummary.from("req-1", List.of(), "http://allure");

            assertEquals(0, summary.getTotalTests());
            assertEquals(0.0, summary.getPassRate());
            assertEquals("PASSED", summary.getOverallStatus());
        }

        @Test
        @DisplayName("Framework bazlı özet her framework için ayrı hesaplanır")
        void groupsByFramework() {
            var karate = testCase("A", TestRunStatus.PASSED, null);
            var selenium = GeneratedTestCase.builder().testName("B")
                    .framework(TestFramework.SELENIUM).runStatus(TestRunStatus.FAILED).build();

            var summary = TestReportSummary.from("req-1", List.of(karate, selenium), "http://allure");

            assertEquals(2, summary.getFrameworkSummaries().size());
            assertEquals(1, summary.getFrameworkSummaries().get(TestFramework.KARATE).getPassed());
            assertEquals(1, summary.getFrameworkSummaries().get(TestFramework.SELENIUM).getFailed());
        }
    }

    @Nested
    @DisplayName("Genel durum ve oran")
    class StatusAndRate {

        private TestReportSummary.TestReportSummaryBuilder base() {
            return TestReportSummary.builder().requestId("r").projectName("P");
        }

        @Test
        @DisplayName("Başarısız ve bozuk yoksa PASSED")
        void passedWhenNoFailuresOrBroken() {
            assertEquals("PASSED", base().totalTests(5).passedTests(5).build().getOverallStatus());
        }

        @Test
        @DisplayName("Hiçbiri geçmediyse FAILED")
        void failedWhenNonePassed() {
            assertEquals("FAILED", base().totalTests(5).passedTests(0).failedTests(5)
                    .build().getOverallStatus());
        }

        @Test
        @DisplayName("Kısmi geçiş PARTIAL")
        void partialWhenSomePassed() {
            assertEquals("PARTIAL", base().totalTests(5).passedTests(3).failedTests(2)
                    .build().getOverallStatus());
        }

        /**
         * Bozuk (derlenmemiş/koşulmamış) test varken PASSED demek yanılgı yaratır:
         * "her şey yolunda" görünür ama testlerin bir kısmı hiç koşmamıştır.
         */
        @Test
        @DisplayName("Başarısız yok ama bozuk varsa PASSED denmez")
        void brokenPreventsPassed() {
            assertEquals("PARTIAL", base().totalTests(5).passedTests(4).failedTests(0)
                    .brokenTests(1).build().getOverallStatus());
        }

        @Test
        @DisplayName("Her duruma karşılık bir simge döner")
        void everyStatusHasEmoji() {
            assertFalse(base().totalTests(1).passedTests(1).build().getStatusEmoji().isBlank());
            assertFalse(base().totalTests(1).failedTests(1).build().getStatusEmoji().isBlank());
            assertFalse(base().totalTests(2).passedTests(1).failedTests(1)
                    .build().getStatusEmoji().isBlank());
        }

        @Test
        @DisplayName("Tarih verilmezse şimdiki zamana düşer, null dönmez")
        void dateFallsBackToNow() {
            assertTrue(base().build().getFormattedDate()
                    .matches("\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}"));
        }
    }

    @Nested
    @DisplayName("Üretim özetleri")
    class GenerationHighlights {

        @Test
        @DisplayName("Case yokken açıklayıcı tek satır döner")
        void emptyCasesGiveExplanatoryLine() {
            var summary = TestReportSummary.builder().build();

            assertEquals(1, summary.getGenerationHighlights().size());
            assertTrue(summary.getGenerationHighlights().get(0).contains("uretilmedi"));
        }

        /**
         * AUTO-FIX etiketli case'ler self-healing ürünüdür; "üretim" değil
         * "iyileştirme" bölümüne aittir. Karışırsa rapor aynı testi iki kez sayar.
         */
        @Test
        @DisplayName("AUTO-FIX etiketli case'ler üretim özetine girmez")
        void autoFixCasesAreExcludedFromGeneration() {
            var summary = TestReportSummary.builder().testCases(List.of(
                    testCase("Normal", TestRunStatus.PASSED, "Normal ozet"),
                    testCase("Duzeltilmis", TestRunStatus.PASSED, "[AUTO-FIX] Duzeltildi"))).build();

            var highlights = summary.getGenerationHighlights();

            assertTrue(highlights.stream().anyMatch(h -> h.contains("Normal")));
            assertFalse(highlights.stream().anyMatch(h -> h.contains("Duzeltilmis")),
                    "AUTO-FIX case üretim özetine sızmış: " + highlights);
        }

        @Test
        @DisplayName("Etiketler özet metninden temizlenir")
        void tagsAreStrippedFromSummaryText() {
            var summary = TestReportSummary.builder().testCases(List.of(
                    testCase("A", TestRunStatus.PASSED, "[SMOKE][P0] Gecerli istek"))).build();

            String line = summary.getGenerationHighlights().get(0);

            assertTrue(line.contains("Gecerli istek"));
            assertFalse(line.contains("[SMOKE]"), "Etiket temizlenmemiş: " + line);
        }

        @Test
        @DisplayName("Özeti olmayan case için yer tutucu metin kullanılır")
        void missingSummaryGetsPlaceholder() {
            var summary = TestReportSummary.builder().testCases(List.of(
                    testCase("A", TestRunStatus.PASSED, null))).build();

            assertTrue(summary.getGenerationHighlights().get(0).contains("Ozet bilgisi yok"));
        }

        @Test
        @DisplayName("En fazla 6 satır gösterilir — e-posta gövdesi şişmez")
        void limitsToSixLines() {
            var many = java.util.stream.IntStream.rangeClosed(1, 20)
                    .mapToObj(i -> testCase("Test" + i, TestRunStatus.PASSED, "ozet " + i))
                    .toList();

            assertEquals(6, TestReportSummary.builder().testCases(many)
                    .build().getGenerationHighlights().size());
        }
    }

    @Nested
    @DisplayName("İyileştirme özetleri")
    class ImprovementHighlights {

        @Test
        @DisplayName("AUTO-FIX case'leri iyileştirme bölümünde listelenir")
        void autoFixCasesAppearHere() {
            var summary = TestReportSummary.builder().testCases(List.of(
                    testCase("Duzeltilmis", TestRunStatus.PASSED, "[AUTO-FIX] Duzeltildi"))).build();

            assertTrue(summary.getImprovementHighlights().stream()
                    .anyMatch(h -> h.contains("Duzeltilmis")));
        }

        @Test
        @DisplayName("Başarısız case'ler iyileştirme adayı olarak işaretlenir")
        void failedCasesAreMarkedAsCandidates() {
            var summary = TestReportSummary.builder().testCases(List.of(
                    testCase("Patlayan", TestRunStatus.FAILED, "ozet"))).build();

            assertTrue(summary.getImprovementHighlights().stream()
                    .anyMatch(h -> h.contains("Patlayan") && h.contains("aday")));
        }

        @Test
        @DisplayName("İyileştirilecek bir şey yoksa bunu açıkça söyler")
        void saysNothingToImproveWhenAllGreen() {
            var summary = TestReportSummary.builder().testCases(List.of(
                    testCase("A", TestRunStatus.PASSED, "ozet"))).build();

            assertTrue(summary.getImprovementHighlights().get(0).contains("gerekmiyor"));
        }

        @Test
        @DisplayName("Case yokken açıklayıcı tek satır döner")
        void emptyCasesGiveExplanatoryLine() {
            assertTrue(TestReportSummary.builder().build()
                    .getImprovementHighlights().get(0).contains("henuz yok"));
        }
    }

    @Nested
    @DisplayName("Ajan özetleri")
    class AgentHighlights {

        @Test
        @DisplayName("Ajan bölümündeki başlıklar listelenir")
        void listsAgentSectionHeadings() {
            var summary = TestReportSummary.builder().requestContext("""
                    Bir şeyler
                    ## AI AGENT ANALYSIS
                    ### Product Manager
                    metin
                    ### Test Analyst
                    metin
                    """).build();

            var highlights = summary.getAgentHighlights();

            assertEquals(2, highlights.size());
            assertTrue(highlights.get(0).contains("Product Manager"));
            assertTrue(highlights.get(0).contains("tamamlandı"));
        }

        @Test
        @DisplayName("Ajan bölümü yoksa açıklayıcı satır döner")
        void noAgentSectionGivesExplanatoryLine() {
            assertTrue(TestReportSummary.builder().requestContext("düz metin").build()
                    .getAgentHighlights().get(0).contains("bulunamadı"));
        }

        @Test
        @DisplayName("Bağlam null iken çökmez")
        void nullContextIsSafe() {
            assertTrue(TestReportSummary.builder().build()
                    .getAgentHighlights().get(0).contains("bulunamadı"));
        }
    }

    @Nested
    @DisplayName("Case kırılımı")
    class CaseDetails {

        @Test
        @DisplayName("Case yokken boş liste döner")
        void emptyWhenNoCases() {
            assertTrue(TestReportSummary.builder().build().getCaseDetails().isEmpty());
        }

        @Test
        @DisplayName("Koşulmamış case NOT_RUN olarak raporlanır, null değil")
        void unrunCaseReportsNotRun() {
            var summary = TestReportSummary.builder().testCases(List.of(
                    testCase("A", null, null))).build();

            assertEquals("NOT_RUN", summary.getCaseDetails().get(0).getRunStatus());
        }

        @Test
        @DisplayName("Framework'ü olmayan case '-' ile raporlanır")
        void missingFrameworkIsDashed() {
            var summary = TestReportSummary.builder().testCases(List.of(
                    GeneratedTestCase.builder().testName("A").build())).build();

            assertEquals("-", summary.getCaseDetails().get(0).getFramework());
        }
    }
}
