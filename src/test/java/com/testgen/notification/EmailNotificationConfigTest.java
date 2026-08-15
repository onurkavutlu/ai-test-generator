package com.testgen.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code notification.email.*} yapılandırmasının bağlanması.
 *
 * <p><b>Neden bu test var:</b> {@code @ConfigurationProperties} bağlanması sessizdir —
 * application.yml'deki bir anahtar yeniden adlandırılır ya da alan adı değişirse Spring
 * hata vermez, alan varsayılan değerinde kalır. Sonuç: {@code attach-report: true} yazılı
 * olmasına rağmen ek gönderilmez, ya da alıcı listesi boş kalıp e-posta kimseye gitmez.
 * Bu testler yml anahtarları ile alan adları arasındaki eşlemeyi kilitler; kebab-case
 * anahtarların camelCase alanlara çözümlendiğini de doğrular.
 */
class EmailNotificationConfigTest {

    private EmailNotificationConfig bind(Map<String, Object> properties) {
        ConfigurationPropertySource source = new MapConfigurationPropertySource(properties);
        return new Binder(source)
                .bind("notification.email", EmailNotificationConfig.class)
                .orElseGet(EmailNotificationConfig::new);
    }

    @Test
    @DisplayName("application.yml'deki tüm anahtarlar ilgili alanlara bağlanır")
    void bindsAllKeysFromYaml() {
        var config = bind(Map.of(
                "notification.email.enabled", "true",
                "notification.email.from", "testgen@local.dev",
                "notification.email.from-name", "AI Test Generator",
                "notification.email.default-recipients", "team@local.dev",
                "notification.email.subject-prefix", "[AI-TestGen]",
                "notification.email.allure-report-url", "http://localhost:8888",
                "notification.email.app-base-url", "http://localhost:8080",
                "notification.email.attach-report", "true"
        ));

        assertTrue(config.isEnabled());
        assertEquals("testgen@local.dev", config.getFrom());
        assertEquals("AI Test Generator", config.getFromName());
        assertEquals("team@local.dev", config.getDefaultRecipients());
        assertEquals("[AI-TestGen]", config.getSubjectPrefix());
        assertEquals("http://localhost:8888", config.getAllureReportUrl());
        assertEquals("http://localhost:8080", config.getAppBaseUrl());
        assertTrue(config.isAttachReport());
    }

    /**
     * Kebab-case → camelCase gevşek eşleme bozulursa {@code from-name} alanı null kalır ve
     * e-postalar görünen ad olmadan gider. Sessiz bozulma; bu yüzden ayrıca kilitleniyor.
     */
    @Test
    @DisplayName("Kebab-case anahtarlar camelCase alanlara çözümlenir")
    void resolvesKebabCaseToCamelCase() {
        var config = bind(Map.of(
                "notification.email.from-name", "Görünen Ad",
                "notification.email.default-recipients", "a@x.dev",
                "notification.email.subject-prefix", "[X]",
                "notification.email.allure-report-url", "http://allure",
                "notification.email.app-base-url", "http://app",
                "notification.email.attach-report", "false"
        ));

        assertEquals("Görünen Ad", config.getFromName());
        assertEquals("a@x.dev", config.getDefaultRecipients());
        assertEquals("[X]", config.getSubjectPrefix());
        assertEquals("http://allure", config.getAllureReportUrl());
        assertEquals("http://app", config.getAppBaseUrl());
        assertFalse(config.isAttachReport());
    }

    @Test
    @DisplayName("Yapılandırma verilmezse güvenli varsayılanlar geçerli olur")
    void appliesSafeDefaultsWhenUnset() {
        var config = new EmailNotificationConfig();

        assertTrue(config.isEnabled(), "Bildirim varsayılan olarak açık olmalı");
        assertTrue(config.isAttachReport(), "Rapor eki varsayılan olarak açık olmalı");
        assertEquals("http://localhost:8080", config.getAppBaseUrl(),
                "app-base-url verilmezse e-postadaki rapor linki kurulamaz");
    }

    @Test
    @DisplayName("enabled=false bildirim katmanını kapatır")
    void canBeDisabled() {
        assertFalse(bind(Map.of("notification.email.enabled", "false")).isEnabled());
    }
}
