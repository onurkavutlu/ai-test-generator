package com.testgen.runner;

import com.intuit.karate.junit5.Karate;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Bu sınıf ISTQB Entegrasyon ve API seviyelerindeki testleri koşturur.
 * Spring Boot context'ini rastgele bir portta (RANDOM_PORT) ayağa kaldırır.
 * Karate testlerine "baseUrl" sistem değişkenini bu rastgele port olarak besler.
 * "Test" son ekiyle bittiği için "mvn test" esnasında otomatik olarak çalıştırılır.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:karate_integration_db;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
})
@ActiveProfiles("local")
public class KarateIntegrationTest {

    @LocalServerPort
    private int port;

    @BeforeEach
    public void setUp() {
        // Karate'nin feature dosyaları içerisinden okuması için baseUrl parametresini set ediyoruz
        System.setProperty("baseUrl", "http://localhost:" + port);
    }

    @Karate.Test
    public Karate runSmoke() {
        return Karate.run("classpath:smoke/smoke.feature");
    }

    @Karate.Test
    public Karate runApi() {
        return Karate.run("classpath:api/api.feature");
    }
}
