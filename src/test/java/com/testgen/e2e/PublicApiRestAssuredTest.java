package com.testgen.e2e;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Aynı public API'nin REST Assured karşılığı.
 *
 * <p><b>Neden iki framework aynı hedefe:</b> Ürün her ikisini de üretiyor. Aynı API'yi
 * ikisiyle birden test etmek, üretilen kodun hangi framework'te nasıl göründüğünü
 * karşılaştırılabilir kılar — Karate okunabilirlikte, REST Assured Java ekosistemine
 * entegrasyonda güçlü. Senaryo kümesi bilinçli olarak eşleniktir.
 *
 * <p>Ağ yoksa testler atlanır, kırmızı yanmaz — gerekçe için bkz. {@link PublicApiKarateTest}.
 */
@DisplayName("Public API (restful-api.dev) — REST Assured")
class PublicApiRestAssuredTest {

    private static final String BASE_URL = "https://api.restful-api.dev";
    private static boolean reachable;
    private static String unreachableReason;

    @BeforeAll
    static void setUpAndProbe() {
        RestAssured.baseURI = BASE_URL;
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(BASE_URL + "/objects/1")
                    .toURL().openConnection();
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

    private void requireTarget() {
        assumeTrue(reachable,
                () -> "restful-api.dev erişilemiyor, test atlandı — " + unreachableReason);
    }

    private String uniqueName() {
        return "AI-TestGen-RA-" + System.currentTimeMillis();
    }

    @Nested
    @DisplayName("Okuma senaryoları")
    class ReadScenarios {

        @Test
        @DisplayName("[SMOKE][P0_BLOCKER][EP] Tüm nesneler listelenir")
        void listsAllObjects() {
            requireTarget();

            given()
                .when().get("/objects")
                .then()
                    .statusCode(200)
                    .contentType(ContentType.JSON)
                    .body("size()", greaterThan(0))
                    .body("[0].id", notNullValue())
                    .body("[0].name", notNullValue());
        }

        @Test
        @DisplayName("[SMOKE][P0_BLOCKER][EP] Tek nesne kimliğiyle getirilir")
        void fetchesSingleObjectById() {
            requireTarget();

            given()
                .pathParam("id", 1)
                .when().get("/objects/{id}")
                .then()
                    .statusCode(200)
                    .body("id", equalTo("1"))
                    .body("name", notNullValue());
        }

        @Test
        @DisplayName("[REGRESSION][P1_CRITICAL][EP] Birden fazla nesne tek istekte getirilir")
        void fetchesMultipleObjectsInOneCall() {
            requireTarget();

            given()
                .queryParam("id", 1)
                .queryParam("id", 2)
                .when().get("/objects")
                .then()
                    .statusCode(200)
                    .body("size()", equalTo(2));
        }

        @Test
        @DisplayName("[PERFORMANCE][P2_MAJOR][BVA] Liste ucu kabul edilebilir sürede yanıtlar")
        void listEndpointRespondsWithinSla() {
            requireTarget();

            given()
                .when().get("/objects")
                .then()
                    .statusCode(200)
                    // Dış servis: amaç mutlak hız değil, zaman aşımı regresyonunu yakalamak
                    .time(lessThan(10_000L));
        }
    }

    @Nested
    @DisplayName("Negatif senaryolar")
    class NegativeScenarios {

        private static final String MISSING_ID = "bu-kimlik-yok-999999";

        @Test
        @DisplayName("[NEGATIVE][P1_CRITICAL][EG] Var olmayan kimlik 404 döner")
        void missingIdReturnsNotFound() {
            requireTarget();

            given()
                .when().get("/objects/" + MISSING_ID)
                .then().statusCode(404);
        }

        @Test
        @DisplayName("[NEGATIVE][P2_MAJOR][ST] Var olmayan kaynağı silmek 404 döner")
        void deletingMissingResourceReturnsNotFound() {
            requireTarget();

            given()
                .when().delete("/objects/" + MISSING_ID)
                .then().statusCode(404);
        }

        @Test
        @DisplayName("[NEGATIVE][P2_MAJOR][EG] Var olmayan kaynağı güncellemek 404 döner")
        void updatingMissingResourceReturnsNotFound() {
            requireTarget();

            given()
                .contentType(ContentType.JSON)
                .body(Map.of("name", "olmayan"))
                .when().put("/objects/" + MISSING_ID)
                .then().statusCode(404);
        }
    }

    @Nested
    @DisplayName("Yaşam döngüsü ve sınır değerler")
    class LifecycleScenarios {

        /**
         * Durum geçişi zinciri: oluştur → oku → güncelle → sil → silindiğini doğrula.
         * Son adım kritik: silme çağrısının 200 dönmesi kaynağın GERÇEKTEN silindiğini
         * kanıtlamaz; ancak sonraki okumanın 404 vermesi kanıtlar.
         */
        @Test
        @DisplayName("[E2E][P1_CRITICAL][ST] Nesne yaşam döngüsü uçtan uca doğrulanır")
        void objectLifecycleEndToEnd() {
            requireTarget();
            String name = uniqueName();

            // 1) Oluştur
            Response created = given()
                    .contentType(ContentType.JSON)
                    .body(Map.of("name", name,
                            "data", Map.of("yil", 2026, "fiyat", 1499.99, "uretici", "AI Test Generator")))
                    .when().post("/objects")
                    .then()
                        .statusCode(200)
                        .body("name", equalTo(name))
                        .body("id", notNullValue())
                        .extract().response();

            String id = created.path("id");

            try {
                // 2) Oku — yazılan veri okunabilmeli
                given()
                    .when().get("/objects/" + id)
                    .then()
                        .statusCode(200)
                        .body("name", equalTo(name))
                        .body("data.yil", equalTo(2026));

                // 3) Tam güncelle
                given()
                    .contentType(ContentType.JSON)
                    .body(Map.of("name", name + "-guncel",
                            "data", Map.of("yil", 2027, "fiyat", 1599.99, "uretici", "AI Test Generator")))
                    .when().put("/objects/" + id)
                    .then()
                        .statusCode(200)
                        .body("name", equalTo(name + "-guncel"))
                        .body("data.yil", equalTo(2027));

                // 4) Kısmi güncelle
                given()
                    .contentType(ContentType.JSON)
                    .body(Map.of("name", name + "-patch"))
                    .when().patch("/objects/" + id)
                    .then()
                        .statusCode(200)
                        .body("name", equalTo(name + "-patch"));
            } finally {
                // 5) Sil — test verisi bırakma (finally: ara adım patlasa da temizlik yapılır)
                given().when().delete("/objects/" + id).then().statusCode(200);
            }

            // 6) Silinen kaynak artık okunamamalı
            given()
                .when().get("/objects/" + id)
                .then().statusCode(404);
        }

        @Test
        @DisplayName("[BOUNDARY][P2_MAJOR][BVA] Çok uzun isimle nesne oluşturulur")
        void createsObjectWithVeryLongName() {
            requireTarget();
            String longName = uniqueName() + "-" + "x".repeat(500);

            String id = given()
                    .contentType(ContentType.JSON)
                    .body(Map.of("name", longName, "data", Map.of("not", "sinir degeri testi")))
                    .when().post("/objects")
                    .then()
                        .statusCode(200)
                        .body("name", equalTo(longName))
                        .extract().path("id");

            given().when().delete("/objects/" + id).then().statusCode(200);
        }

        @Test
        @DisplayName("[BOUNDARY][P3_MINOR][BVA] Boş data nesnesiyle kayıt oluşturulur")
        void createsObjectWithEmptyData() {
            requireTarget();
            String name = uniqueName() + "-bos-data";

            String id = given()
                    .contentType(ContentType.JSON)
                    .body(Map.of("name", name, "data", Map.of()))
                    .when().post("/objects")
                    .then()
                        .statusCode(200)
                        .body("name", equalTo(name))
                        .extract().path("id");

            given().when().delete("/objects/" + id).then().statusCode(200);
        }

        @Test
        @DisplayName("[REGRESSION][P2_MAJOR][EP] Yanıt JSON içerik tipiyle döner")
        void responseCarriesJsonContentType() {
            requireTarget();

            given()
                .when().get("/objects/1")
                .then()
                    .statusCode(200)
                    .header("Content-Type", containsString("application/json"));
        }
    }
}
