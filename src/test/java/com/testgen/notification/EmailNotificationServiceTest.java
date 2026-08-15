package com.testgen.notification;

import com.testgen.report.TestReportSummary;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E-posta bildirim servisi — kapsam ölçümünde %1,3 ile projenin en zayıf paketiydi.
 *
 * <p>Buradaki testlerin hiçbiri "SMTP çalışıyor mu" sorusuyla ilgilenmiyor; hepsi
 * <b>sessiz kayıp</b> senaryolarını kilitliyor: alıcı çözümlemesi bozuksa e-posta
 * kimseye gitmez, şablon seçimi yanlışsa başarısız koşum "her şey yolunda" görünür,
 * SMTP hatası yayılırsa koşum sonrası akış yarıda kalır. Üçü de sessizce olur.
 *
 * <p><b>Not — neden gerçek TemplateEngine:</b> Thymeleaf'te {@code process(String, IContext)}
 * final'dır ve proje {@code mock-maker-subclass} kullandığı için mock'lanamaz. Bunun yerine
 * {@link StringTemplateResolver} ile gerçek motor kuruluyor: şablon ADI şablon İÇERİĞİ olarak
 * işlendiği için üretilen gövde şablon adını taşır. Böylece şablon seçimi, mock çağrısı
 * üzerinden değil, e-postaya GERÇEKTEN yazılan gövde üzerinden doğrulanır — daha güçlü.
 */
class EmailNotificationServiceTest {

    private JavaMailSender mailSender;
    private EmailNotificationService service;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage())
                .thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));

        service = new EmailNotificationService(mailSender, stringTemplateEngine());
        ReflectionTestUtils.setField(service, "from", "testgen@local.dev");
        ReflectionTestUtils.setField(service, "fromName", "AI Test Generator");
        ReflectionTestUtils.setField(service, "defaultRecipients", "team@local.dev");
        ReflectionTestUtils.setField(service, "subjectPrefix", "[AI-TestGen]");
        ReflectionTestUtils.setField(service, "attachReport", false);
    }

    /** Şablon adını şablon içeriği olarak işleyen motor — bkz. sınıf yorumu. */
    private static TemplateEngine stringTemplateEngine() {
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.TEXT);
        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    private TestReportSummary.TestReportSummaryBuilder summary() {
        return TestReportSummary.builder()
                .requestId("req-1")
                .projectName("Kitaplik API")
                .totalTests(10)
                .passedTests(10)
                .failedTests(0);
    }

    /**
     * Gönderilen tek MimeMessage'ı yakalar.
     *
     * <p>{@code saveChanges()} bilinçli çağrılıyor: MimeMessage'ın Content-Type başlıkları
     * bu çağrıya kadar kesinleşmez ve gerçek gönderimde bunu JavaMailSenderImpl yapar.
     * Çağrılmazsa mesaj, gövdesi HTML olmasına rağmen text/plain görünür.
     */
    private MimeMessage captureSent() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage message = captor.getValue();
        try {
            message.saveChanges();
        } catch (Exception e) {
            throw new IllegalStateException("MimeMessage kesinleştirilemedi", e);
        }
        return message;
    }

    /** Multipart gövdeyi gezip tüm metin parçalarını birleştirir. */
    private static String textOf(Part part) throws Exception {
        Object content = part.getContent();
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof Multipart mp) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mp.getCount(); i++) {
                sb.append(textOf(mp.getBodyPart(i)));
            }
            return sb.toString();
        }
        return "";
    }

    /** Multipart gövdedeki ek dosya adlarını toplar. */
    private static List<String> attachmentNames(Part part) throws Exception {
        List<String> names = new ArrayList<>();
        Object content = part.getContent();
        if (content instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                Part child = mp.getBodyPart(i);
                if (child.getFileName() != null) {
                    names.add(child.getFileName());
                }
                names.addAll(attachmentNames(child));
            }
        }
        return names;
    }

    @Nested
    @DisplayName("Alıcı çözümleme")
    class RecipientResolution {

        @Test
        @DisplayName("Alıcı verilmezse varsayılan alıcılara düşülür")
        void fallsBackToDefaultRecipients() throws Exception {
            service.sendTestReport(summary().build(), null);

            assertEquals("team@local.dev", captureSent().getAllRecipients()[0].toString());
        }

        @Test
        @DisplayName("Boş liste de varsayılan alıcı sayılır")
        void emptyListFallsBackToDefaults() throws Exception {
            service.sendTestReport(summary().build(), List.of());

            assertEquals("team@local.dev", captureSent().getAllRecipients()[0].toString());
        }

        @Test
        @DisplayName("Açıkça verilen alıcılar varsayılanı ezer")
        void explicitRecipientsOverrideDefaults() throws Exception {
            service.sendTestReport(summary().build(), List.of("qa@local.dev", "lead@local.dev"));

            var recipients = captureSent().getAllRecipients();
            assertEquals(2, recipients.length);
            assertEquals("qa@local.dev", recipients[0].toString());
            assertEquals("lead@local.dev", recipients[1].toString());
        }

        /**
         * Alıcı yoksa e-posta GÖNDERİLMEMELİ: boş adres dizisiyle send çağırmak SMTP
         * tarafında hata üretir ve akışın ortasında beklenmedik istisnaya yol açar.
         */
        @Test
        @DisplayName("Hiç alıcı yoksa gönderim denenmez")
        void sendsNothingWhenNoRecipientsAtAll() {
            ReflectionTestUtils.setField(service, "defaultRecipients", "");

            service.sendTestReport(summary().build(), null);

            verify(mailSender, never()).send(any(MimeMessage.class));
        }

        @Test
        @DisplayName("Varsayılan alıcılar virgül, noktalı virgül ve boşlukla ayrılabilir")
        void parsesRecipientsWithMixedSeparators() throws Exception {
            ReflectionTestUtils.setField(service, "defaultRecipients",
                    "a@x.dev, b@x.dev;c@x.dev  d@x.dev");

            service.sendTestReport(summary().build(), null);

            assertEquals(4, captureSent().getAllRecipients().length);
        }

        @Test
        @DisplayName("Yalnızca ayraçlardan oluşan alıcı listesi boş sayılır")
        void separatorsOnlyMeansNoRecipients() {
            ReflectionTestUtils.setField(service, "defaultRecipients", " , ; ");

            service.sendTestReport(summary().build(), null);

            verify(mailSender, never()).send(any(MimeMessage.class));
        }
    }

    @Nested
    @DisplayName("Şablon seçimi")
    class TemplateSelection {

        /**
         * Yanlış şablon seçimi sessiz yanlış bilgilendirmedir: başarısız koşum "başarılı"
         * şablonuyla gönderilirse ekip sorunu hiç görmez.
         */
        @Test
        @DisplayName("Başarısız test yoksa success şablonu kullanılır")
        void usesSuccessTemplateWhenNoFailures() throws Exception {
            service.sendTestReport(summary().failedTests(0).build(), null);

            String body = textOf(captureSent());
            assertTrue(body.contains("test-report-success"), "Gerçek gövde: " + body);
            assertFalse(body.contains("test-report-failure"), "Gerçek gövde: " + body);
        }

        @Test
        @DisplayName("Tek bir başarısız test bile failure şablonuna geçirir")
        void usesFailureTemplateWhenAnyFailure() throws Exception {
            service.sendTestReport(summary().passedTests(9).failedTests(1).build(), null);

            String body = textOf(captureSent());
            assertTrue(body.contains("test-report-failure"), "Gerçek gövde: " + body);
        }

        /**
         * Gövde düz metin olarak işaretlenirse alıcı HTML kaynağını ham görür —
         * rapor okunamaz hale gelir. İçerik tipi bilinçli olarak text/html olmalı.
         */
        @Test
        @DisplayName("Gövde text/html olarak işaretlenir, düz metin olarak değil")
        void bodyIsMarkedAsHtml() throws Exception {
            service.sendTestReport(summary().build(), null);

            assertTrue(hasHtmlPart(captureSent()),
                    "E-postada text/html içerik parçası bulunamadı");
        }

        private boolean hasHtmlPart(Part part) throws Exception {
            if (part.isMimeType("text/html")) {
                return true;
            }
            if (part.getContent() instanceof Multipart mp) {
                for (int i = 0; i < mp.getCount(); i++) {
                    if (hasHtmlPart(mp.getBodyPart(i))) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    @Nested
    @DisplayName("Konu satırı")
    class SubjectLine {

        @Test
        @DisplayName("Konu ön ek, proje adı ve geçen/toplam sayısını içerir")
        void subjectCarriesKeyFacts() throws Exception {
            service.sendTestReport(summary().totalTests(10).passedTests(7).failedTests(3).build(), null);

            String subject = captureSent().getSubject();
            assertTrue(subject.startsWith("[AI-TestGen]"), subject);
            assertTrue(subject.contains("Kitaplik API"), subject);
            assertTrue(subject.contains("7/10"), subject);
        }

        @Test
        @DisplayName("Konuda tarih gg.aa.yyyy ss:dd biçiminde yer alır")
        void subjectContainsFormattedDate() throws Exception {
            service.sendTestReport(summary().build(), null);

            assertTrue(captureSent().getSubject().matches(".*\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}.*"),
                    captureSent().getSubject());
        }
    }

    @Nested
    @DisplayName("Rapor eki")
    class ReportAttachment {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("attach-report kapalıyken dosya var olsa da ek gönderilmez")
        void doesNotAttachWhenDisabled() throws Exception {
            Path report = Files.writeString(tempDir.resolve("rapor.html"), "<html>ok</html>");
            ReflectionTestUtils.setField(service, "attachReport", false);

            service.sendTestReport(summary().cucumberReportPath(report.toString()).build(), null);

            assertTrue(attachmentNames(captureSent()).isEmpty());
        }

        @Test
        @DisplayName("attach-report açıkken var olan rapor requestId'li adla eklenir")
        void attachesExistingReportWithRequestIdInName() throws Exception {
            Path report = Files.writeString(tempDir.resolve("rapor.html"), "<html>ok</html>");
            ReflectionTestUtils.setField(service, "attachReport", true);

            service.sendTestReport(
                    summary().requestId("req-42").cucumberReportPath(report.toString()).build(), null);

            assertTrue(attachmentNames(captureSent()).contains("cucumber-report-req-42.html"),
                    "Ekler: " + attachmentNames(captureSent()));
        }

        /**
         * Ek dosyası bulunamadığında e-postanın TAMAMEN düşmesi kabul edilemez —
         * rapor linki gövdede zaten var, ek yalnızca kolaylık.
         */
        @Test
        @DisplayName("Rapor dosyası yoksa e-posta yine de gönderilir, sadece ek düşer")
        void missingReportFileDoesNotBlockEmail() throws Exception {
            ReflectionTestUtils.setField(service, "attachReport", true);

            service.sendTestReport(
                    summary().cucumberReportPath("/olmayan/dizin/rapor.html").build(), null);

            assertTrue(attachmentNames(captureSent()).isEmpty());
        }

        @Test
        @DisplayName("Rapor yolu boş veya null iken ek adımı atlanır")
        void blankReportPathSkipsAttachment() {
            ReflectionTestUtils.setField(service, "attachReport", true);

            service.sendTestReport(summary().cucumberReportPath("  ").build(), null);
            service.sendTestReport(summary().cucumberReportPath(null).build(), null);

            verify(mailSender, times(2)).send(any(MimeMessage.class));
        }
    }

    @Nested
    @DisplayName("Hata dayanıklılığı")
    class FailureResilience {

        /**
         * Bildirim yan etkidir. SMTP çöktüğünde istisna yayılırsa koşum sonrası akış
         * (rapor kaydı, durum güncellemesi) yarıda kalır — testler geçmiş olsa bile
         * sistem başarısız görünür.
         */
        @Test
        @DisplayName("SMTP hatası koşum akışını çökertmez")
        void smtpFailureDoesNotPropagate() {
            doThrow(new MailSendException("SMTP kapalı"))
                    .when(mailSender).send(any(MimeMessage.class));

            assertDoesNotThrow(() -> service.sendTestReport(summary().build(), null));
        }

        @Test
        @DisplayName("Hızlı bildirimde SMTP hatası da yutulur")
        void quickNotificationSmtpFailureDoesNotPropagate() {
            doThrow(new MailSendException("SMTP kapalı"))
                    .when(mailSender).send(any(MimeMessage.class));

            assertDoesNotThrow(() ->
                    service.sendQuickNotification("req-1", "koşum bitti", List.of("qa@local.dev")));
        }
    }

    @Nested
    @DisplayName("Hızlı bildirim")
    class QuickNotification {

        @Test
        @DisplayName("Verilen alıcılara gönderilir ve mesaj gövdeye girer")
        void sendsToGivenRecipientsWithMessage() throws Exception {
            service.sendQuickNotification("req-9", "koşum başladı", List.of("qa@local.dev"));

            MimeMessage sent = captureSent();
            assertEquals("qa@local.dev", sent.getAllRecipients()[0].toString());
            assertTrue(textOf(sent).contains("koşum başladı"));
        }

        @Test
        @DisplayName("Konu satırı ön ek ve requestId içerir")
        void subjectCarriesRequestId() throws Exception {
            service.sendQuickNotification("req-9", "mesaj", List.of("qa@local.dev"));

            String subject = captureSent().getSubject();
            assertTrue(subject.contains("[AI-TestGen]"), subject);
            assertTrue(subject.contains("req-9"), subject);
        }

        @Test
        @DisplayName("Alıcı verilmezse varsayılana düşer")
        void fallsBackToDefaults() throws Exception {
            service.sendQuickNotification("req-9", "mesaj", null);

            assertEquals("team@local.dev", captureSent().getAllRecipients()[0].toString());
        }
    }

    @Nested
    @DisplayName("TestReportSummary türetilmiş alanları")
    class DerivedSummaryFields {

        @Test
        @DisplayName("Tümü geçtiğinde durum PASSED")
        void allPassedMeansPassedStatus() {
            assertEquals("PASSED", summary().totalTests(10).passedTests(10).failedTests(0)
                    .build().getOverallStatus());
        }

        @Test
        @DisplayName("Hiçbiri geçmediğinde durum FAILED")
        void nonePassedMeansFailed() {
            assertEquals("FAILED", summary().totalTests(10).passedTests(0).failedTests(10)
                    .build().getOverallStatus());
        }

        @Test
        @DisplayName("Kısmi geçişte durum PARTIAL")
        void partialPassMeansPartial() {
            assertEquals("PARTIAL", summary().totalTests(10).passedTests(6).failedTests(4)
                    .build().getOverallStatus());
        }

        @Test
        @DisplayName("Başarısız yok ama broken varsa PASSED sayılmaz")
        void brokenTestsPreventPassedStatus() {
            assertEquals("PARTIAL", summary().totalTests(10).passedTests(9)
                    .failedTests(0).brokenTests(1).build().getOverallStatus());
        }

        @Test
        @DisplayName("Sıfır teste bölme hatası olmaz")
        void zeroTestsDoesNotDivideByZero() {
            assertEquals(0.0, summary().totalTests(0).passedTests(0).failedTests(0)
                    .build().getPassRate());
        }

        @Test
        @DisplayName("Geçiş oranı yüzde olarak biçimlenir")
        void passRateIsFormattedAsPercentage() {
            assertTrue(summary().totalTests(4).passedTests(3).build()
                    .getPassRateFormatted().contains("75"));
        }
    }
}
