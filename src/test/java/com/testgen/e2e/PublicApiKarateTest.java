package com.testgen.e2e;

import com.intuit.karate.Results;
import com.intuit.karate.Runner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Karate testlerini <b>gerçek bir public API'ye</b> karşı koşturur.
 *
 * <p><b>Seçilen kaynak:</b> {@code https://api.restful-api.dev} — public-apis listesinden.
 * Kimlik doğrulama istemez ve tam CRUD sunar; bu ikisi olmadan durum geçişi (create →
 * read → update → delete) ve negatif senaryolar yazılamazdı.
 *
 * <p><b>Ağ yoksa ne olur:</b> Test BAŞARISIZ olmaz, <b>atlanır</b>. Dış servise bağımlı
 * bir testin ağ kesintisinde kırmızı yanması, ekibin kırmızıya duyarsızlaşmasına yol
 * açar — gerçek bir regresyon geldiğinde kimse bakmaz. Erişilebilirlik önce yoklanır,
 * erişilemiyorsa neden atlandığı açıkça yazılır.
 */
@DisplayName("Public API (restful-api.dev) — Karate")
class PublicApiKarateTest {

    private static final String BASE_URL = "https://api.restful-api.dev";
    private static boolean reachable;
    private static String unreachableReason;

    @BeforeAll
    static void probeTargetReachability() {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(BASE_URL + "/objects/1")
                    .toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            int code = conn.getResponseCode();
            conn.disconnect();
            reachable = code == 200;
            if (!reachable) {
                unreachableReason = "Beklenmeyen durum kodu: " + code;
            }
        } catch (Exception e) {
            reachable = false;
            unreachableReason = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /**
     * Bu kontrol ağdan BAĞIMSIZDIR ve bilinçli olarak öyle tasarlandı: feature dosyasının
     * sözdizimi bozulduğunda, ağ erişimi olmayan bir ortamda diğer test sessizce atlanır
     * ve bozukluk fark edilmez. Ayrıştırma her koşulda doğrulanır.
     */
    @Test
    @DisplayName("Feature dosyası Karate ayrıştırıcısından geçer (ağ gerektirmez)")
    void featureFileIsSyntacticallyValid() throws Exception {
        var resource = getClass().getClassLoader()
                .getResource("publicapi/restful-api-objects.feature");
        org.junit.jupiter.api.Assertions.assertNotNull(resource,
                "Feature dosyası classpath'te bulunamadı");

        var feature = com.intuit.karate.core.Feature.read(
                new java.io.File(resource.toURI()));

        org.junit.jupiter.api.Assertions.assertNotNull(feature.getSections(),
                "Feature ayrıştırıldı ama hiç bölüm yok");
        org.junit.jupiter.api.Assertions.assertTrue(feature.getSections().size() >= 10,
                "Beklenenden az senaryo ayrıştırıldı: " + feature.getSections().size());
    }

    @Test
    @DisplayName("Objects API sözleşme senaryoları geçer")
    void runsObjectsApiFeature() {
        assumeTrue(reachable,
                () -> "restful-api.dev erişilemiyor, test atlandı — " + unreachableReason);

        Results results = Runner.path("classpath:publicapi/restful-api-objects.feature")
                .outputCucumberJson(true)
                .parallel(2);

        assertEquals(0, results.getFailCount(),
                () -> "Başarısız senaryolar:\n" + results.getErrorMessages());
    }
}
