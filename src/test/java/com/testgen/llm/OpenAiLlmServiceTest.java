package com.testgen.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OpenAI istemcisi — <b>mock yedeği kaldırıldıktan sonraki sözleşme</b>.
 *
 * <p>Önceden OpenAI erişilemediğinde {@code MockGenerator} devreye girip sahte bir
 * feature/sınıf üretiyor ve bunu normal çıktı gibi döndürüyordu. Sonuç: üretim
 * "başarılı" görünüyor, testler koşuyor, hatta bazıları geçiyordu — ama doğruladıkları
 * şey gerçek API değil, uydurma bir şablondu. Kök neden (LLM kapalı) yalnızca bir WARN
 * logunda kalıyor, kullanıcı asla görmüyordu.
 *
 * <p>Bu testler o davranışın geri gelmediğini kilitler: LLM hatası <b>yüzeye çıkmalı</b>.
 */
class OpenAiLlmServiceTest {

    private ChatLanguageModel chatModel;
    private OpenAiLlmService service;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatLanguageModel.class);
        service = new OpenAiLlmService(chatModel);
    }

    @Test
    @DisplayName("Model yanıtı olduğu gibi döner")
    void returnsModelResponse() {
        when(chatModel.generate(any(ChatMessage[].class)))
                .thenReturn(Response.from(AiMessage.from("Feature: Pet API")));

        assertEquals("Feature: Pet API", service.generateTestCase("prompt"));
    }

    /**
     * Kritik: sahte içerik ÜRETİLMEZ. Bu test geçmezse mock yedeği geri gelmiş demektir.
     */
    @Test
    @DisplayName("LLM erişilemezse istisna fırlatılır, sahte içerik üretilmez")
    void failureThrowsInsteadOfReturningFakeContent() {
        when(chatModel.generate(any(ChatMessage[].class)))
                .thenThrow(new RuntimeException("Connection refused"));

        var ex = assertThrows(LlmException.class, () -> service.generateTestCase("prompt"));

        assertTrue(ex.getMessage().contains("OpenAI"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Connection refused"), ex.getMessage());
    }

    @Test
    @DisplayName("Swagger üretimi de hata durumunda sahte içerik döndürmez")
    void swaggerGenerationAlsoThrowsOnFailure() {
        when(chatModel.generate(any(ChatMessage[].class)))
                .thenThrow(new RuntimeException("401 Unauthorized"));

        assertThrows(LlmException.class,
                () -> service.generateFromSwagger("spec", "/api/pets", "GET", "bağlam"));
    }

    @Test
    @DisplayName("Selenium üretimi de hata durumunda sahte içerik döndürmez")
    void seleniumGenerationAlsoThrowsOnFailure() {
        when(chatModel.generate(any(ChatMessage[].class)))
                .thenThrow(new RuntimeException("rate limit"));

        assertThrows(LlmException.class,
                () -> service.generateSeleniumTest("http://x/login", "hikaye", "<input>"));
    }

    @Test
    @DisplayName("Ham yük, GraphQL ve SOAP yolları da hatayı yüzeye çıkarır")
    void allGenerationPathsPropagateFailure() {
        when(chatModel.generate(any(ChatMessage[].class)))
                .thenThrow(new RuntimeException("kapalı"));

        assertThrows(LlmException.class,
                () -> service.generateFromRawPayload("curl ...", "CAPTURED", ""));
        assertThrows(LlmException.class,
                () -> service.generateFromGraphQL("query { x }", ""));
        assertThrows(LlmException.class,
                () -> service.generateFromSoap("<soap:Envelope/>", ""));
    }

    @Test
    @DisplayName("OpenAI seçildiğinde anahtar secret olarak zorunludur")
    void blankApiKeyFailsFast() {
        var ex = assertThrows(IllegalStateException.class,
                () -> new OpenAiLlmService(" ", "gpt-4", "https://api.openai.com/v1", 0.2, 128));

        assertTrue(ex.getMessage().contains("OPENAI_API_KEY"));
    }
}
