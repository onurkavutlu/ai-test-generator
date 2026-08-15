package com.testgen.report;

import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestRunStatus;
import com.testgen.model.TestType;
import com.testgen.notification.EmailNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Raporlama orkestrasyonu — Allure + Cucumber + e-posta zincirini birleştirir.
 *
 * <p>Bu sınıf koşum sonrası akışın son halkası ve <b>%1 kapsamdaydı</b>. Buradaki
 * kırılganlık şu: zincirdeki herhangi bir adım (Allure CLI yok, Cucumber üretimi
 * başarısız, e-posta servisi kapalı) diğerlerini düşürmemeli. Allure kurulu olmayan
 * bir makinede e-posta hiç gitmezse, ekip koşum sonucunu hiç öğrenmez.
 */
class ReportOrchestratorTest {

    private AllureReportService allureReportService;
    private CucumberReportService cucumberReportService;
    private EmailNotificationService emailNotificationService;
    private ReportOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        allureReportService = mock(AllureReportService.class);
        cucumberReportService = mock(CucumberReportService.class);
        emailNotificationService = mock(EmailNotificationService.class);

        when(allureReportService.generateHtmlReport(anyString()))
                .thenReturn(AllureReportResult.success("/tmp/allure", "http://localhost:8888/r"));
        when(cucumberReportService.generateReport(anyString(), any()))
                .thenReturn(Path.of("/tmp/cucumber/rapor.html"));

        orchestrator = new ReportOrchestrator(allureReportService, cucumberReportService);
        ReflectionTestUtils.setField(orchestrator, "appBaseUrl", "http://localhost:8080");
        ReflectionTestUtils.setField(orchestrator, "emailNotificationService", emailNotificationService);
    }

    private List<GeneratedTestCase> cases() {
        return List.of(
                GeneratedTestCase.builder().testName("A").framework(TestFramework.KARATE)
                        .runStatus(TestRunStatus.PASSED).build(),
                GeneratedTestCase.builder().testName("B").framework(TestFramework.KARATE)
                        .runStatus(TestRunStatus.FAILED).build());
    }

    private TestReportSummary captureSentSummary() {
        ArgumentCaptor<TestReportSummary> captor = ArgumentCaptor.forClass(TestReportSummary.class);
        verify(emailNotificationService).sendTestReport(captor.capture(), any());
        return captor.getValue();
    }

    @Nested
    @DisplayName("Zincirin tamamı")
    class FullChain {

        @Test
        @DisplayName("Allure sonuçları yazılır, HTML üretilir ve e-posta gönderilir")
        void runsEveryStageInOrder() {
            orchestrator.generateAndSend("req-1", null, cases(), List.of("qa@local.dev"));

            verify(allureReportService).writeAllResults(any(), eq("req-1"));
            verify(allureReportService).generateHtmlReport("req-1");
            verify(cucumberReportService).generateReport(eq("req-1"), any());
            verify(emailNotificationService).sendTestReport(any(), eq(List.of("qa@local.dev")));
        }

        @Test
        @DisplayName("Özet sayımları case durumlarından türetilir")
        void summaryReflectsCaseStatuses() {
            orchestrator.generateAndSend("req-1", null, cases(), null);

            TestReportSummary summary = captureSentSummary();
            assertEquals(2, summary.getTotalTests());
            assertEquals(1, summary.getPassedTests());
            assertEquals(1, summary.getFailedTests());
            assertEquals("PARTIAL", summary.getOverallStatus());
        }

        @Test
        @DisplayName("Ajan bağlamı özete taşınır")
        void agentContextIsCarriedIntoSummary() {
            orchestrator.generateAndSend("req-1",
                    "## AI AGENT ANALYSIS\n### Product Manager\nmetin", cases(), null);

            assertTrue(captureSentSummary().getAgentHighlights().get(0).contains("Product Manager"));
        }

        @Test
        @DisplayName("Üretim isteği üzerinden çağrı da aynı zinciri koşar")
        void requestOverloadRunsSameChain() {
            TestGenerationRequest request = TestGenerationRequest.builder()
                    .testType(TestType.BACKEND_API).framework(TestFramework.KARATE).build();
            request.setId("req-9");

            orchestrator.generateAndSend(request, cases());

            verify(allureReportService).generateHtmlReport("req-9");
            verify(emailNotificationService).sendTestReport(any(), eq(null));
        }
    }

    @Nested
    @DisplayName("Rapor bağlantıları")
    class ReportLinks {

        @Test
        @DisplayName("Cucumber rapor yolu ve URL'si özete yazılır")
        void cucumberPathAndUrlAreSet() {
            orchestrator.generateAndSend("req-1", null, cases(), null);

            TestReportSummary summary = captureSentSummary();
            assertTrue(summary.getCucumberReportPath().endsWith("rapor.html"),
                    summary.getCucumberReportPath());
            assertEquals("http://localhost:8080/reports/cucumber/req-1",
                    summary.getCucumberReportUrl());
        }

        /**
         * Taban adreste sondaki eğik çizgi temizlenmezse link
         * "http://host//reports/..." olur; bazı proxy'lerde 404 verir.
         */
        @Test
        @DisplayName("Taban adresteki sondaki eğik çizgi çift slash üretmez")
        void trailingSlashInBaseUrlIsNormalised() {
            ReflectionTestUtils.setField(orchestrator, "appBaseUrl", "http://testgen.local/");

            orchestrator.generateAndSend("req-1", null, cases(), null);

            assertEquals("http://testgen.local/reports/cucumber/req-1",
                    captureSentSummary().getCucumberReportUrl());
        }

        @Test
        @DisplayName("Taban adres boşsa varsayılana düşer, link bozulmaz")
        void blankBaseUrlFallsBackToDefault() {
            ReflectionTestUtils.setField(orchestrator, "appBaseUrl", "  ");

            orchestrator.generateAndSend("req-1", null, cases(), null);

            assertEquals("http://localhost:8080/reports/cucumber/req-1",
                    captureSentSummary().getCucumberReportUrl());
        }

        @Test
        @DisplayName("Allure başarılıysa rapor URL'si özete girer")
        void successfulAllureUrlReachesSummary() {
            orchestrator.generateAndSend("req-1", null, cases(), null);

            assertEquals("http://localhost:8888/r", captureSentSummary().getAllureReportUrl());
        }

        /**
         * Allure CLI kurulu olmayan makinelerde rapor üretilemez. Bu durumda URL null
         * kalmalı — başarısız bir rapora link vermek kullanıcıyı boş sayfaya götürür.
         */
        @Test
        @DisplayName("Allure başarısızsa URL null kalır, ölü link verilmez")
        void failedAllureLeavesUrlNull() {
            when(allureReportService.generateHtmlReport(anyString()))
                    .thenReturn(AllureReportResult.failed("allure CLI bulunamadı"));

            var result = orchestrator.generateAndSend("req-1", null, cases(), null);

            assertNull(captureSentSummary().getAllureReportUrl());
            assertFalse(result.isSuccess());
        }
    }

    @Nested
    @DisplayName("Kısmi başarısızlık dayanıklılığı")
    class PartialFailureResilience {

        /**
         * Allure kurulu olmayan bir makinede e-posta hiç gitmezse ekip koşum sonucunu
         * hiç öğrenmez. Zincirin bir halkası düşse de diğerleri koşmalı.
         */
        @Test
        @DisplayName("Allure başarısız olsa da e-posta yine gönderilir")
        void emailStillSentWhenAllureFails() {
            when(allureReportService.generateHtmlReport(anyString()))
                    .thenReturn(AllureReportResult.failed("allure yok"));

            orchestrator.generateAndSend("req-1", null, cases(), null);

            verify(emailNotificationService).sendTestReport(any(), any());
        }

        @Test
        @DisplayName("Cucumber raporu üretilemezse yol boş kalır ama akış sürer")
        void missingCucumberReportDoesNotBreakChain() {
            when(cucumberReportService.generateReport(anyString(), any())).thenReturn(null);

            orchestrator.generateAndSend("req-1", null, cases(), null);

            assertNull(captureSentSummary().getCucumberReportPath());
            verify(emailNotificationService).sendTestReport(any(), any());
        }

        /**
         * E-posta servisi conditional bean: {@code notification.email.enabled=false}
         * iken hiç oluşturulmaz. Orkestratör null referansla çökmemeli.
         */
        @Test
        @DisplayName("E-posta servisi devre dışıyken raporlama yine tamamlanır")
        void worksWithEmailServiceDisabled() {
            ReflectionTestUtils.setField(orchestrator, "emailNotificationService", null);

            var result = assertDoesNotThrow(() ->
                    orchestrator.generateAndSend("req-1", null, cases(), null));

            assertTrue(result.isSuccess());
            verify(cucumberReportService).generateReport(anyString(), any());
        }

        @Test
        @DisplayName("Hiç case yokken de rapor akışı çalışır")
        void emptyCaseListStillProducesReport() {
            var result = assertDoesNotThrow(() ->
                    orchestrator.generateAndSend("req-1", null, List.of(), null));

            assertEquals(0, result.summary().getTotalTests());
        }
    }

    @Nested
    @DisplayName("Sonuç nesnesi")
    class ResultObject {

        @Test
        @DisplayName("Başarılı koşumda rapor URL'si sonuçtan okunabilir")
        void exposesReportUrlOnSuccess() {
            var result = orchestrator.generateAndSend("req-1", null, cases(), null);

            assertTrue(result.isSuccess());
            assertEquals("http://localhost:8888/r", result.reportUrl());
        }

        @Test
        @DisplayName("Sonuç özeti de taşır")
        void carriesSummary() {
            var result = orchestrator.generateAndSend("req-1", null, cases(), null);

            assertEquals(2, result.summary().getTotalTests());
        }
    }
}
