package com.testgen.notification;

import com.testgen.report.TestReportSummary;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Email bildirim servisi.
 *
 * Şablonlar:
 *  - test-report-success.html  → tüm testler geçti
 *  - test-report-failure.html  → başarısız testler var
 *  - test-report-summary.html  → ortak özet şablonu (her iki durum)
 *
 * @Async ile Spring Mail blocking değil.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "notification.email.enabled", havingValue = "true", matchIfMissing = true)
public class EmailNotificationService {

    private final JavaMailSender    mailSender;
    private final TemplateEngine    templateEngine;

    @Value("${notification.email.from}")
    private String from;

    @Value("${notification.email.from-name}")
    private String fromName;

    @Value("${notification.email.default-recipients}")
    private String defaultRecipients;

    @Value("${notification.email.subject-prefix}")
    private String subjectPrefix;

    @Value("${notification.email.attach-report:false}")
    private boolean attachReport;

    // ─────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────

    /**
     * Test tamamlandıktan sonra özet raporu email olarak gönder.
     * recipients null/boş ise default alıcılar kullanılır.
     */
    @Async
    public void sendTestReport(TestReportSummary summary, List<String> recipients) {
        List<String> toList = (recipients == null || recipients.isEmpty())
                ? parseRecipients(defaultRecipients)
                : recipients;

        if (toList.isEmpty()) {
            log.warn("Email alıcısı bulunamadı, email gönderilmedi.");
            return;
        }

        try {
            String subject = buildSubject(summary);
            String htmlBody = renderTemplate(summary);

            sendHtmlEmail(toList, subject, htmlBody, summary);
            log.info("Test raporu emaili gönderildi → {} kişi | requestId: {}",
                    toList.size(), summary.getRequestId());

        } catch (Exception e) {
            log.error("Email gönderilemedi - requestId: {}", summary.getRequestId(), e);
        }
    }

    /**
     * Kısa bildirim emaili – detaylı rapor olmadan.
     */
    @Async
    public void sendQuickNotification(String requestId, String message, List<String> recipients) {
        List<String> toList = (recipients == null || recipients.isEmpty())
                ? parseRecipients(defaultRecipients)
                : recipients;
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, false, "UTF-8");
            helper.setFrom(from, fromName);
            helper.setTo(toList.toArray(new String[0]));
            helper.setSubject(subjectPrefix + " " + requestId + " – Bildirim");
            helper.setText("<html><body><p>" + message + "</p></body></html>", true);
            mailSender.send(mime);
        } catch (Exception e) {
            log.error("Bildirim emaili gönderilemedi", e);
        }
    }

    // ─────────────────────────────────────────────────────────
    // Internal
    // ─────────────────────────────────────────────────────────

    private void sendHtmlEmail(List<String> toList, String subject,
                                String htmlBody, TestReportSummary summary)
            throws Exception {

        MimeMessage mime = mailSender.createMimeMessage();
        // multipart = true → attachment ekleyebilmek için
        MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");

        helper.setFrom(from, fromName);
        helper.setTo(toList.toArray(new String[0]));
        helper.setSubject(subject);
        helper.setText(htmlBody, true);   // true = HTML

        if (attachReport) {
            attachCucumberReport(helper, summary);
        }

        mailSender.send(mime);
    }

    /**
     * Üretilen Cucumber HTML raporunu e-postaya ekler.
     *
     * Önceden burada yalnızca TODO'lu bir Allure stub'ı vardı; attach-report açık olsa
     * bile hiçbir zaman ek gönderilmiyordu.
     */
    private void attachCucumberReport(MimeMessageHelper helper, TestReportSummary summary) {
        String path = summary.getCucumberReportPath();
        if (path == null || path.isBlank()) {
            log.debug("Cucumber rapor yolu yok, ek atlanıyor - requestId: {}", summary.getRequestId());
            return;
        }
        try {
            Path reportFile = Path.of(path);
            if (!Files.exists(reportFile)) {
                log.warn("Cucumber rapor dosyası bulunamadı, ek atlanıyor: {}", reportFile);
                return;
            }
            String attachmentName = "cucumber-report-" + summary.getRequestId() + ".html";
            helper.addAttachment(attachmentName, new File(reportFile.toString()));
            log.info("Cucumber raporu e-postaya eklendi: {} ({} byte)",
                    attachmentName, Files.size(reportFile));
        } catch (Exception e) {
            // Ek eklenemezse e-posta yine de gitsin
            log.warn("Cucumber raporu eklenemedi - requestId: {}: {}",
                    summary.getRequestId(), e.getMessage());
        }
    }

    private String renderTemplate(TestReportSummary summary) {
        Context ctx = new Context(Locale.forLanguageTag("tr"));
        ctx.setVariable("summary", summary);
        ctx.setVariable("testCases", summary.getTestCases());
        ctx.setVariable("frameworkSummaries", summary.getFrameworkSummaries());
        ctx.setVariable("caseDetails", summary.getCaseDetails());

        // Durum bazlı şablon seç
        String template = summary.getFailedTests() == 0
                ? "email/test-report-success"
                : "email/test-report-failure";

        return templateEngine.process(template, ctx);
    }

    private String buildSubject(TestReportSummary summary) {
        String status = summary.getOverallStatus();
        String emoji  = summary.getStatusEmoji();
        return "%s %s Test Raporu – %s (%d/%d geçti) – %s"
                .formatted(subjectPrefix, emoji, summary.getProjectName(),
                        summary.getPassedTests(), summary.getTotalTests(),
                        summary.getFormattedDate());
    }

    private List<String> parseRecipients(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split("[,;\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
