package com.testgen.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSRF kapısı. Bu testler bir güvenlik açığını kilitler:
 * Runner ve gözlem katmanı, kullanıcının yazdığı adrese uygulamanın KENDİ ağından istek
 * atar ve yanıt gövdesini kullanıcıya aynen gösterir. Şema/host doğrulaması tek başına
 * yeterli değildi — {@code http://169.254.169.254/latest/meta-data/iam/security-credentials/}
 * geçerli bir http URL'sidir ve bulutta geçici IAM kimlik bilgilerini döndürür.
 *
 * <p>Tasarım kararı da burada kilitleniyor: localhost ve RFC1918 adresleri VARSAYILAN
 * OLARAK SERBEST kalmalı, çünkü dahili servis test etmek bu ürünün asıl kullanımıdır.
 * Engellenen şey, meşru test kullanımı olmayan link-local/metadata aralığıdır.
 */
class OutboundUrlGuardTest {

    private OutboundUrlGuard guard;

    @BeforeEach
    void setUp() {
        guard = new OutboundUrlGuard();
        guard.setAllowPrivateNetworks(true); // varsayılan üretim davranışı
    }

    @Nested
    @DisplayName("Metadata ve link-local adresleri her zaman engellenir")
    class AlwaysBlocked {

        @ParameterizedTest(name = "{0} engellenir")
        @ValueSource(strings = {
                "http://169.254.169.254/latest/meta-data/iam/security-credentials/",
                "http://169.254.169.254",
                "https://169.254.169.254/computeMetadata/v1/",
                "http://169.254.170.2/v2/credentials",
                "http://100.100.100.200/latest/meta-data/"
        })
        @DisplayName("Bulut metadata uçlarına istek atılamaz")
        void blocksCloudMetadataEndpoints(String url) {
            var ex = assertThrows(BadRequestException.class, () -> guard.verify(url));
            assertTrue(ex.getMessage().toLowerCase().contains("metadata")
                            || ex.getMessage().toLowerCase().contains("link-local"),
                    "Hata mesajı nedeni açıklamalı, gerçek mesaj: " + ex.getMessage());
        }

        @Test
        @DisplayName("Link-local aralığının tamamı engellenir, sadece bilinen metadata IP'si değil")
        void blocksEntireLinkLocalRange() {
            assertThrows(BadRequestException.class, () -> guard.verify("http://169.254.1.1/x"));
            assertThrows(BadRequestException.class, () -> guard.verify("http://169.254.99.42:8080/x"));
        }

        @Test
        @DisplayName("Özel ağlar serbestken bile metadata engelli kalır")
        void metadataStaysBlockedEvenWhenPrivateNetworksAllowed() {
            guard.setAllowPrivateNetworks(true);
            assertThrows(BadRequestException.class,
                    () -> guard.verify("http://169.254.169.254/latest/meta-data/"));
        }

        @Test
        @DisplayName("0.0.0.0 gibi belirsiz adreslere istek atılamaz")
        void blocksAnyLocalAddress() {
            assertThrows(BadRequestException.class, () -> guard.verify("http://0.0.0.0/x"));
        }
    }

    @Nested
    @DisplayName("Meşru test kullanımı bozulmaz")
    class LegitimateUsageAllowed {

        @ParameterizedTest(name = "{0} serbest")
        @ValueSource(strings = {
                "http://localhost:8081/api/pets",
                "http://127.0.0.1:9000/health",
                "http://10.0.3.7/api",
                "http://192.168.1.50:8080/v1",
                "http://172.16.4.4/api"
        })
        @DisplayName("Dahili servisler varsayılan olarak test edilebilir")
        void allowsPrivateAndLoopbackByDefault(String url) {
            assertDoesNotThrow(() -> guard.verify(url));
        }

        @Test
        @DisplayName("Genel internet adresleri serbest")
        void allowsPublicAddresses() {
            assertDoesNotThrow(() -> guard.verify("http://93.184.216.34/x"));
        }
    }

    @Nested
    @DisplayName("Sertleştirilmiş dağıtımda dahili ağ kapatılabilir")
    class HardenedMode {

        @BeforeEach
        void hardenGuard() {
            guard.setAllowPrivateNetworks(false);
        }

        @Test
        @DisplayName("allow-private-networks=false iken loopback engellenir")
        void blocksLoopbackWhenHardened() {
            var ex = assertThrows(BadRequestException.class,
                    () -> guard.verify("http://127.0.0.1:8080/x"));
            assertTrue(ex.getMessage().contains("Dahili ağ"), ex.getMessage());
        }

        @Test
        @DisplayName("allow-private-networks=false iken RFC1918 aralığı engellenir")
        void blocksPrivateRangesWhenHardened() {
            assertThrows(BadRequestException.class, () -> guard.verify("http://10.0.3.7/api"));
            assertThrows(BadRequestException.class, () -> guard.verify("http://192.168.1.50/api"));
        }

        @Test
        @DisplayName("Sertleştirilmiş modda bile genel adresler serbest")
        void stillAllowsPublicAddresses() {
            assertDoesNotThrow(() -> guard.verify("http://93.184.216.34/x"));
        }
    }

    @Nested
    @DisplayName("Girdi doğrulaması")
    class InputValidation {

        @Test
        @DisplayName("Boş ve null url reddedilir")
        void rejectsBlankUrl() {
            assertThrows(BadRequestException.class, () -> guard.verify((String) null));
            assertThrows(BadRequestException.class, () -> guard.verify("   "));
        }

        @Test
        @DisplayName("http/https dışındaki şemalar reddedilir — file: ile yerel dosya okunamaz")
        void rejectsNonHttpSchemes() {
            assertThrows(BadRequestException.class, () -> guard.verify("file:///etc/passwd"));
            assertThrows(BadRequestException.class, () -> guard.verify("ftp://example.com/a"));
            assertThrows(BadRequestException.class, () -> guard.verify("gopher://example.com/a"));
        }

        @Test
        @DisplayName("Host içermeyen url reddedilir")
        void rejectsUrlWithoutHost() {
            assertThrows(BadRequestException.class, () -> guard.verify("not-a-url"));
            assertThrows(BadRequestException.class, () -> guard.verify("http:///path"));
        }

        @Test
        @DisplayName("Çözümlenemeyen host reddedilir — istek atılmadan önce yakalanır")
        void rejectsUnresolvableHost() {
            assertThrows(BadRequestException.class,
                    () -> guard.verify("http://bu-host-kesinlikle-yok-12345.invalid/x"));
        }

        @Test
        @DisplayName("URI aşırı yüklemesi de aynı kuralları uygular")
        void uriOverloadAppliesSameRules() {
            assertThrows(BadRequestException.class,
                    () -> guard.verify(URI.create("http://169.254.169.254/x")));
            assertThrows(BadRequestException.class, () -> guard.verify((URI) null));
        }

        @Test
        @DisplayName("Şema büyük harfle yazılsa da kabul edilir")
        void schemeIsCaseInsensitive() {
            assertDoesNotThrow(() -> guard.verify("HTTP://localhost:8080/x"));
        }
    }
}
