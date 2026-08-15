package com.testgen.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ollama LLM istemcisi — gerçek ağ çağrısı yapılmaz, model arayüzü mock'lanır.
 *
 * <p>Test edilen şey model çıktısının kalitesi değil, <b>istemcinin sözleşmesi</b>:
 * her çağrı doğru tiple raporlanmalı (aksi halde maliyet takibi ve dashboard sekmesi
 * yanlış olur), ve LLM kesintisi <b>sessizce yutulmamalı</b> — proje geçmişinde mock
 * çıktılarının "başarı" gibi gösterilmesi bilinçli olarak geri alınmış; hata yüzeye
 * çıkmazsa üretim boş içerikle "başarılı" tamamlanır.
 */
class OllamaLlmServiceTest {

    private ChatLanguageModel chatModel;
    private LlmReportStore reportStore;
    private OllamaLlmService service;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatLanguageModel.class);
        reportStore = mock(LlmReportStore.class);
        service = new OllamaLlmService(chatModel, "llama3.1", 16384, reportStore);
    }

    private void stubResponse(String text) {
        when(chatModel.generate(any(ChatMessage[].class)))
                .thenReturn(Response.from(AiMessage.from(text)));
    }

    private ArgumentCaptor<LlmCallReport> captureReport() {
        ArgumentCaptor<LlmCallReport> captor = ArgumentCaptor.forClass(LlmCallReport.class);
        verify(reportStore).record(captor.capture());
        return captor;
    }

    @Nested
    @DisplayName("Başarılı çağrı")
    class SuccessfulCall {

        @Test
        @DisplayName("Model yanıtı olduğu gibi döner")
        void returnsModelResponse() {
            stubResponse("Feature: Pet API");

            assertEquals("Feature: Pet API", service.generateTestCase("prompt"));
        }

        @Test
        @DisplayName("Sistem mesajı ve kullanıcı mesajı birlikte gönderilir")
        void sendsSystemAndUserMessages() {
            stubResponse("çıktı");

            service.generateTestCase("benim prompt'um");

            ArgumentCaptor<ChatMessage[]> captor = ArgumentCaptor.forClass(ChatMessage[].class);
            verify(chatModel).generate(captor.capture());
            List<ChatMessage> messages = List.of(captor.getValue());
            assertTrue(messages.stream().anyMatch(m -> m instanceof SystemMessage),
                    "Sistem mesajı gönderilmemiş");
            assertTrue(messages.stream().anyMatch(m -> m instanceof UserMessage),
                    "Kullanıcı mesajı gönderilmemiş");
        }

        @Test
        @DisplayName("Başarılı çağrı model adı ve süresiyle raporlanır")
        void recordsSuccessfulCall() {
            stubResponse("çıktı");

            service.generateTestCase("prompt");

            LlmCallReport report = captureReport().getValue();
            assertTrue(report.success());
            assertEquals("llama3.1", report.model());
            assertTrue(report.durationMs() >= 0);
        }
    }

    @Nested
    @DisplayName("Çağrı tipi etiketleme")
    class CallTypeLabelling {

        /**
         * Çağrı tipi yanlış etiketlenirse maliyet takibi ve dashboard filtresi bozulur;
         * "Karate üretimi kaç çağrı harcadı" sorusu cevapsız kalır.
         */
        @Test
        @DisplayName("Swagger üretimi KARATE olarak etiketlenir")
        void swaggerGenerationIsLabelledKarate() {
            stubResponse("Feature: x");

            service.generateFromSwagger("spec", "/api/pets", "GET", "bağlam");

            assertEquals("KARATE", captureReport().getValue().callType());
        }

        @Test
        @DisplayName("Selenium üretimi SELENIUM olarak etiketlenir")
        void seleniumGenerationIsLabelledSelenium() {
            stubResponse("class X {}");

            service.generateSeleniumTest("http://x/login", "hikaye", "<input>");

            assertEquals("SELENIUM", captureReport().getValue().callType());
        }

        @Test
        @DisplayName("GraphQL ve SOAP üretimleri de KARATE olarak etiketlenir")
        void graphqlAndSoapAreLabelledKarate() {
            stubResponse("Feature: x");
            service.generateFromGraphQL("query { x }", "bağlam");
            assertEquals("KARATE", captureReport().getValue().callType());
        }

        @Test
        @DisplayName("Tip verilmezse GENERIC kullanılır")
        void defaultsToGenericCallType() {
            stubResponse("çıktı");

            service.generateTestCase("prompt", null);

            assertEquals("GENERIC", captureReport().getValue().callType());
        }

        @Test
        @DisplayName("Verilen çağrı tipi korunur")
        void explicitCallTypeIsKept() {
            stubResponse("çıktı");

            service.generateTestCase("prompt", "FAILURE_ANALYSIS");

            assertEquals("FAILURE_ANALYSIS", captureReport().getValue().callType());
        }
    }

    @Nested
    @DisplayName("Hata davranışı")
    class FailureBehaviour {

        /**
         * LLM kesintisi SESSİZCE yutulmamalı. Proje "mock fallback'i başarı gibi
         * gösterme" kararını bilinçli olarak geri almış; hata yüzeye çıkmazsa üretim
         * boş içerikle "başarılı" tamamlanır ve kök neden kaybolur.
         */
        @Test
        @DisplayName("Model erişilemezse istisna yüzeye çıkar, sessizce yutulmaz")
        void modelFailurePropagates() {
            when(chatModel.generate(any(ChatMessage[].class)))
                    .thenThrow(new RuntimeException("Connection refused"));

            var ex = assertThrows(RuntimeException.class, () -> service.generateTestCase("prompt"));
            assertTrue(ex.getMessage().contains("LLM bağlantı hatası"), ex.getMessage());
        }

        @Test
        @DisplayName("Başarısız çağrı da raporlanır — maliyet ve tanı için")
        void failedCallIsStillRecorded() {
            when(chatModel.generate(any(ChatMessage[].class)))
                    .thenThrow(new RuntimeException("timeout"));

            assertThrows(RuntimeException.class, () -> service.generateTestCase("prompt", "KARATE"));

            LlmCallReport report = captureReport().getValue();
            assertTrue(!report.success(), "Başarısız çağrı başarılı olarak kaydedilmiş");
            assertEquals("KARATE", report.callType());
            assertTrue(report.errorMessage().contains("timeout"));
        }

        @Test
        @DisplayName("Hata mesajı orijinal nedeni taşır")
        void errorMessageCarriesOriginalCause() {
            when(chatModel.generate(any(ChatMessage[].class)))
                    .thenThrow(new RuntimeException("model bulunamadı"));

            var ex = assertThrows(RuntimeException.class, () -> service.generateTestCase("prompt"));
            assertTrue(ex.getMessage().contains("model bulunamadı"), ex.getMessage());
        }
    }
}
