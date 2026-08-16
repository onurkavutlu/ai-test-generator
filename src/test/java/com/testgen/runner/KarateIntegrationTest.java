package com.testgen.runner;

import com.intuit.karate.junit5.Karate;
import com.testgen.llm.LlmService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

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

    /** Sistem testi gerçek provider'a ve provider süresine bağlı kalmamalı. */
    @MockitoBean
    private LlmService llmService;

    @MockitoBean
    private ChatLanguageModel chatLanguageModel;

    @BeforeEach
    public void setUp() {
        // Karate'nin feature dosyaları içerisinden okuması için baseUrl parametresini set ediyoruz
        System.setProperty("baseUrl", "http://localhost:" + port);

        // Üretim endpoint'i tasarım gereği async üretimi her zaman başlatır. Deterministik
        // fixture, test bittikten sonra gerçek Ollama çağrılarının askıda kalmasını önler.
        when(llmService.generateTestCase(anyString())).thenReturn("""
                Feature: deterministic integration fixture
                Scenario: generated fixture
                * assert true
                """);
        when(llmService.generateTestCase(anyString(), anyString()))
                .thenReturn("Mevcut entegrasyon girdisi dışında ek kanıt yok.");

        Response<AiMessage> supervisorText = Response.from(
                AiMessage.from("Tool çağrısı içermeyen deterministik supervisor fixture çıktısı."),
                new TokenUsage(1, 1));
        when(chatLanguageModel.generate(anyList())).thenReturn(supervisorText);
        when(chatLanguageModel.generate(anyList(), anyList())).thenReturn(supervisorText);
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
