package com.testgen.runner;

import com.testgen.controller.WebLayerTest;
import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.service.TestGenerationService;
import com.testgen.service.TestSuiteService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runner API sözleşmesi — projenin en riskli ucu.
 *
 * <p>{@code /execute} kullanıcının verdiği adrese sunucunun kendi ağından istek atar;
 * {@code /generate-from-response} ise bunu yapıp yanıtı LLM prompt'una koyar. İki
 * davranış kritik: (1) SSRF reddi 500 değil 400 dönmeli — engellemenin bilerek
 * yapıldığı bilgisi kaybolmamalı, (2) hedef yanıt vermediğinde test ÜRETİLMEMELİ,
 * aksi halde LLM boş gözlemle assertion uydurur.
 */
@WebLayerTest
class DirectRequestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DirectRequestService directRequestService;

    @MockitoBean
    private TestGenerationService testGenerationService;

    @MockitoBean
    private TestRunnerService testRunnerService;

    @MockitoBean
    private TestSuiteService testSuiteService;

    private DirectRequestService.DirectRunResult okResult() {
        return new DirectRequestService.DirectRunResult(
                200, 120L, Map.of("Content-Type", "application/json"),
                "{\"id\":7,\"name\":\"Pamuk\"}", null, List.of());
    }

    private void stubGeneration() {
        TestGenerationRequest saved = TestGenerationRequest.builder().build();
        saved.setId("req-1");
        when(testGenerationService.createRequest(any())).thenReturn(saved);
        when(testGenerationService.generateTests(anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.<GeneratedTestCase>of()));
    }

    @Test
    @DisplayName("POST /execute — status, gecikme ve gövdeyi döner")
    void executeReturnsObservedResponse() throws Exception {
        when(directRequestService.execute(any())).thenReturn(okResult());

        mockMvc.perform(post("/api/v1/runner/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://localhost:9/api\",\"method\":\"GET\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.latencyMs").value(120))
                .andExpect(jsonPath("$.body").value("{\"id\":7,\"name\":\"Pamuk\"}"));
    }

    @Test
    @DisplayName("POST /parse-curl — -X olmadan --data içeren Postman cURL'ünü POST algılar")
    void parseCurlInfersPostFromData() throws Exception {
        mockMvc.perform(post("/api/v1/runner/parse-curl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"curl":"curl --location 'https://ornek.local/soap' --header 'Content-Type: text/xml' --data '<Envelope/>'"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("POST"))
                .andExpect(jsonPath("$.methodReason").value(
                        org.hamcrest.Matchers.containsString("POST")))
                .andExpect(jsonPath("$.url").value("https://ornek.local/soap"))
                .andExpect(jsonPath("$.headers.Content-Type").value("text/xml"))
                .andExpect(jsonPath("$.body").value("<Envelope/>"));
    }

    /**
     * SSRF reddi bilinçli bir karardır. 500 dönerse kullanıcı "sistem bozuk" sanır ve
     * engellemenin nedeni kaybolur; 400 + açıklayıcı mesaj doğru sözleşmedir.
     */
    @Test
    @DisplayName("SSRF reddi 500 değil 400 döner ve nedeni açıklar")
    void ssrfRejectionMapsToBadRequest() throws Exception {
        when(directRequestService.execute(any())).thenThrow(
                new com.testgen.config.BadRequestException(
                        "Bulut metadata adreslerine istek atılamaz: 169.254.169.254"));

        mockMvc.perform(post("/api/v1/runner/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://169.254.169.254/latest/meta-data/\",\"method\":\"GET\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("metadata")));
    }

    @Test
    @DisplayName("POST /generate-from-response — 202 döner ve gözlenen yanıtı bildirir")
    void generateFromResponseReturnsAcceptedWithObservation() throws Exception {
        when(directRequestService.execute(any())).thenReturn(okResult());
        stubGeneration();

        mockMvc.perform(post("/api/v1/runner/generate-from-response")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://localhost:9/api\",\"method\":\"GET\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requestId").value("req-1"))
                .andExpect(jsonPath("$.observedStatus").value(200))
                .andExpect(jsonPath("$.observedLatencyMs").value(120))
                .andExpect(jsonPath("$.autoRun").value(true));
    }

    /**
     * Hedef yanıt vermediyse gözlem YOKTUR. Yine de üretime devam edilirse LLM'e boş
     * bağlam gider ve model assertion'ları uydurur — sistemin tüm "gözlem-öncelikli"
     * tasarımı sessizce çöker.
     */
    @Test
    @DisplayName("Hedef yanıt vermediğinde test üretimi hiç başlatılmaz")
    void doesNotGenerateWhenTargetUnreachable() throws Exception {
        when(directRequestService.execute(any())).thenReturn(
                new DirectRequestService.DirectRunResult(
                        null, 50L, Map.of(), null, "Connection refused", List.of()));

        mockMvc.perform(post("/api/v1/runner/generate-from-response")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://localhost:1/api\",\"method\":\"GET\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("Connection refused")));

        verify(testGenerationService, never()).createRequest(any());
        verify(testGenerationService, never()).generateTests(anyString());
    }

    @Test
    @DisplayName("Framework verilmezse KARATE varsayılır")
    void defaultsToKarateFramework() throws Exception {
        when(directRequestService.execute(any())).thenReturn(okResult());
        stubGeneration();

        mockMvc.perform(post("/api/v1/runner/generate-from-response")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://localhost:9/api\",\"method\":\"GET\"}"))
                .andExpect(status().isAccepted());

        ArgumentCaptor<TestGenerationRequest> captor =
                ArgumentCaptor.forClass(TestGenerationRequest.class);
        verify(testGenerationService).createRequest(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(
                TestFramework.KARATE, captor.getValue().getFramework());
    }

    /**
     * Selenium tarayıcı testidir; yanıt-temelli API üretiminde anlamsızdır. Sessizce
     * kabul edilirse üretim başlar ve dakikalar sonra çalışmayan testlerle biter.
     */
    @Test
    @DisplayName("SELENIUM bu akışta reddedilir, üretim başlatılmaz")
    void rejectsSeleniumFramework() throws Exception {
        mockMvc.perform(post("/api/v1/runner/generate-from-response")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://localhost:9/api\",\"framework\":\"SELENIUM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("KARATE")));

        verify(testGenerationService, never()).createRequest(any());
    }

    @Test
    @DisplayName("Tanınmayan framework adı 400 döner")
    void rejectsUnknownFramework() throws Exception {
        mockMvc.perform(post("/api/v1/runner/generate-from-response")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://localhost:9/api\",\"framework\":\"CYPRESS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("Geçersiz framework")));
    }

    @Test
    @DisplayName("REST_ASSURED seçilebilir ve büyük/küçük harf duyarsızdır")
    void acceptsRestAssuredCaseInsensitively() throws Exception {
        when(directRequestService.execute(any())).thenReturn(okResult());
        stubGeneration();

        mockMvc.perform(post("/api/v1/runner/generate-from-response")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://localhost:9/api\",\"framework\":\"rest_assured\"}"))
                .andExpect(status().isAccepted());

        ArgumentCaptor<TestGenerationRequest> captor =
                ArgumentCaptor.forClass(TestGenerationRequest.class);
        verify(testGenerationService).createRequest(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(
                TestFramework.REST_ASSURED, captor.getValue().getFramework());
    }

    /**
     * Gözlenen yanıt prompt bağlamına GERÇEKTEN yazılmalı — bu, sistemin
     * "assertion'lar tahmin değil gözlemdir" iddiasının tek dayanağı.
     */
    @Test
    @DisplayName("Gözlenen status ve gövde üretim bağlamına yazılır")
    void observedResponseIsWrittenIntoGenerationContext() throws Exception {
        when(directRequestService.execute(any())).thenReturn(okResult());
        stubGeneration();

        mockMvc.perform(post("/api/v1/runner/generate-from-response")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://localhost:9/api\",\"method\":\"GET\"}"))
                .andExpect(status().isAccepted());

        ArgumentCaptor<TestGenerationRequest> captor =
                ArgumentCaptor.forClass(TestGenerationRequest.class);
        verify(testGenerationService).createRequest(captor.capture());

        String context = captor.getValue().getAdditionalContext();
        assertNotNull(context, "Gözlem bağlamı boş olmamalı");
        assertTrue(context.contains("OBSERVED RESPONSE"), context);
        assertTrue(context.contains("200"), context);
        assertTrue(context.contains("Pamuk"), "Gözlenen gövde bağlama girmeli: " + context);
    }

    @Test
    @DisplayName("autoRun=false verildiğinde yanıt bunu bildirir")
    void autoRunCanBeDisabled() throws Exception {
        when(directRequestService.execute(any())).thenReturn(okResult());
        stubGeneration();

        mockMvc.perform(post("/api/v1/runner/generate-from-response")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://localhost:9/api\",\"autoRun\":false}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.autoRun").value(false));
    }

    @Test
    @DisplayName("suiteId verildiğinde yanıtta yer alır")
    void suiteIdIsEchoedBack() throws Exception {
        when(directRequestService.execute(any())).thenReturn(okResult());
        stubGeneration();

        mockMvc.perform(post("/api/v1/runner/generate-from-response")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://localhost:9/api\",\"suiteId\":\"s-1\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.suiteId").value("s-1"));
    }
}
