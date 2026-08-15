package com.testgen.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * Kullanıcıdan gelen URL'lerle sunucu taraflı istek atan her nokta için SSRF kapısı.
 *
 * <p><b>Neden var:</b> Runner ve gözlem katmanı, kullanıcının yazdığı adrese uygulamanın
 * KENDİ ağından istek atar. Şema/host doğrulaması tek başına yetmez — {@code http://} ile
 * başlayan geçerli bir URL, bulut sağlayıcının metadata servisine (169.254.169.254) işaret
 * edip geçici IAM kimlik bilgilerini döndürebilir. Yanıt gövdesi kullanıcıya aynen
 * gösterildiği için bu, doğrudan kimlik bilgisi sızıntısıdır.
 *
 * <p><b>Tasarım kararı — özel ağlar varsayılan olarak SERBEST:</b> Bu bir test aracı;
 * QA mühendisinin {@code http://localhost:8081} veya {@code http://10.0.3.7} gibi dahili
 * servisleri denemesi normal ve beklenen kullanımdır. Bunları varsayılan olarak engellemek
 * ürünü kullanılamaz hale getirirdi. Bu yüzden:
 * <ul>
 *   <li><b>Her zaman engellenir:</b> link-local aralığı (169.254.0.0/16, fe80::/10) ve
 *       bilinen bulut metadata adresleri. Bu adreslerin meşru test kullanımı yoktur,
 *       saldırının asıl kazancı ise buradadır.</li>
 *   <li><b>Yapılandırılabilir:</b> loopback ve özel (RFC1918) aralıklar. Sertleştirilmiş
 *       ortamlarda {@code test-generator.security.allow-private-networks=false} ile kapatılır.</li>
 * </ul>
 *
 * <p><b>Yönlendirme (redirect):</b> İzin verilen bir host, 302 ile metadata adresine
 * yönlendirebilir. Bu yüzden çağıran taraf yönlendirmeleri otomatik takip etmemeli;
 * her adımı {@link #verify(URI)} ile yeniden doğrulamalıdır.
 */
@Slf4j
@Component
public class OutboundUrlGuard {

    /**
     * Meşru test kullanımı olmayan, saldırının asıl hedefi olan metadata uçları.
     * Aralık kontrolü link-local'i zaten kapsar; bunlar okunabilirlik ve
     * link-local dışı olanlar (Alibaba 100.100.100.200) için ayrıca listelenir.
     */
    private static final Set<String> METADATA_HOSTS = Set.of(
            "169.254.169.254",   // AWS / Azure / GCP / DigitalOcean IMDS
            "169.254.170.2",     // AWS ECS task metadata
            "100.100.100.200",   // Alibaba Cloud
            "metadata.google.internal",
            "metadata.goog"
    );

    /** Sertleştirilmiş dağıtımlarda dahili ağlara erişimi tamamen kapatmak için. */
    @Value("${test-generator.security.allow-private-networks:true}")
    private boolean allowPrivateNetworks = true;

    /**
     * URL'nin dışarı istek atmak için güvenli olduğunu doğrular.
     *
     * @throws BadRequestException şema geçersizse, host çözümlenemiyorsa veya
     *                            hedef engellenen bir aralıktaysa
     */
    public void verify(URI uri) {
        if (uri == null) {
            throw new BadRequestException("url zorunludur.");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new BadRequestException("url http veya https ile başlamalı: " + uri);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BadRequestException("url geçerli bir host içermeli: " + uri);
        }

        String normalizedHost = host.toLowerCase(Locale.ROOT).replaceAll("^\\[|]$", "");
        if (METADATA_HOSTS.contains(normalizedHost)) {
            throw new BadRequestException(
                    "Bulut metadata adreslerine istek atılamaz: " + host);
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(normalizedHost);
        } catch (UnknownHostException e) {
            throw new BadRequestException("url host'u çözümlenemedi: " + host);
        }

        // DNS rebinding'e karşı: bir isim birden fazla adrese çözümlenebilir,
        // HEPSİ güvenli olmalı — tek bir güvenli adres yeterli sayılmaz.
        for (InetAddress address : addresses) {
            if (METADATA_HOSTS.contains(address.getHostAddress())) {
                throw new BadRequestException(
                        "Bulut metadata adreslerine istek atılamaz: " + host);
            }
            if (address.isLinkLocalAddress()) {
                throw new BadRequestException(
                        "Link-local adreslere istek atılamaz: " + host + " → " + address.getHostAddress());
            }
            if (address.isMulticastAddress() || address.isAnyLocalAddress()) {
                throw new BadRequestException(
                        "Bu adres tipine istek atılamaz: " + host + " → " + address.getHostAddress());
            }
            if (!allowPrivateNetworks
                    && (address.isLoopbackAddress() || address.isSiteLocalAddress())) {
                throw new BadRequestException(
                        "Dahili ağ adreslerine istek atılamaz: " + host + " → " + address.getHostAddress());
            }
        }
    }

    /** Metin URL için {@link #verify(URI)}; ayrıştırılamayan girdiyi de anlaşılır hataya çevirir. */
    public URI verify(String url) {
        if (url == null || url.isBlank()) {
            throw new BadRequestException("url zorunludur.");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("url geçerli değil: " + url);
        }
        verify(uri);
        return uri;
    }

    /** Test ve sertleştirilmiş dağıtım senaryoları için. */
    public void setAllowPrivateNetworks(boolean allowPrivateNetworks) {
        this.allowPrivateNetworks = allowPrivateNetworks;
    }
}
