package com.testgen.controller;

import com.testgen.llm.LlmCallReport;
import com.testgen.llm.LlmReportStore;
import com.testgen.model.TestGenerationRequest;
import com.testgen.service.TestGenerationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * LLM rapor uçları — hem JSON API hem Thymeleaf görünümü.
 *
 * <p>En önemli davranış Türkçe locale tuzağı: {@code "generic".toUpperCase()} Türkçe
 * locale'de {@code "GENERİC"} üretir (noktalı İ) ve hiçbir kayda eşleşmez — filtre
 * sessizce boş liste döner, kullanıcı "hiç çağrı yapılmamış" sanır. Kod bunu
 * {@code Locale.ROOT} ile çözmüş; bu test o çözümü kilitler.
 */
@WebLayerTest
class LlmReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TestGenerationService testGenerationService;

    @MockitoBean
    private LlmReportStore llmReportStore;

    /**
     * Rapor şablonu framework ve testType alanlarını okur; ikisi de üretim isteğinde
     * zorunludur, bu yüzden fixture da onları doldurur.
     */
    private TestGenerationRequest requestWith(String additionalContext) {
        TestGenerationRequest request = TestGenerationRequest.builder()
                .testType(com.testgen.model.TestType.BACKEND_API)
                .framework(com.testgen.model.TestFramework.KARATE)
                .userStory("Kullanıcı evcil hayvan bilgisini görebilmeli")
                .additionalContext(additionalContext)
                .build();
        request.setCreatedAt(LocalDateTime.of(2026, 8, 14, 15, 30));
        return request;
    }

    private LlmCallReport report(String callType, boolean success) {
        return new LlmCallReport("llama3.1", callType, "prompt özeti",
                1000, 500, 250L, success, success ? null : "zaman aşımı",
                "ham yanıt", LocalDateTime.of(2026, 8, 14, 12, 0));
    }

    @Test
    @DisplayName("GET /api/v1/llm/calls — çağrı geçmişini metrikleriyle döner")
    void listsCallsWithMetrics() throws Exception {
        when(llmReportStore.all()).thenReturn(List.of(report("KARATE", true)));

        mockMvc.perform(get("/api/v1/llm/calls"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].model").value("llama3.1"))
                .andExpect(jsonPath("$[0].callType").value("KARATE"))
                .andExpect(jsonPath("$[0].success").value(true))
                .andExpect(jsonPath("$[0].durationMs").value(250))
                .andExpect(jsonPath("$[0].promptChars").value(1000))
                .andExpect(jsonPath("$[0].estimatedPromptTokens").exists());
    }

    @Test
    @DisplayName("Başarısız çağrının hata mesajı yanıtta yer alır")
    void failedCallCarriesErrorMessage() throws Exception {
        when(llmReportStore.all()).thenReturn(List.of(report("AGENT", false)));

        mockMvc.perform(get("/api/v1/llm/calls"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].success").value(false))
                .andExpect(jsonPath("$[0].errorMessage").value("zaman aşımı"));
    }

    @Test
    @DisplayName("type parametresi verilmezse tüm çağrılar döner")
    void withoutTypeReturnsAllCalls() throws Exception {
        when(llmReportStore.all()).thenReturn(List.of(report("KARATE", true)));

        mockMvc.perform(get("/api/v1/llm/calls"))
                .andExpect(status().isOk());

        verify(llmReportStore).all();
    }

    /**
     * Türkçe locale tuzağı: küçük 'i' harfi içeren bir tip adı, locale'e duyarlı
     * toUpperCase ile 'İ'ye dönüşür ve eşleşme kaybolur. Filtrenin ROOT locale
     * kullandığı burada kilitleniyor.
     */
    @Test
    @DisplayName("type filtresi ROOT locale ile büyütülür — Türkçe 'i' tuzağına düşmez")
    void typeFilterUsesRootLocale() throws Exception {
        when(llmReportStore.byType("GENERIC")).thenReturn(List.of(report("GENERIC", true)));

        mockMvc.perform(get("/api/v1/llm/calls").param("type", "generic"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].callType").value("GENERIC"));

        // ROOT locale kullanılmazsa bu çağrı "GENERİC" ile yapılır ve eşleşme olmaz
        verify(llmReportStore).byType("GENERIC");
    }

    @Test
    @DisplayName("GET /api/v1/llm/summary — özet istatistikleri döner")
    void summaryReturnsAggregates() throws Exception {
        when(llmReportStore.summary())
                .thenReturn(new LlmReportStore.LlmCallSummary(10, 8, 2, 320L, 12000, 4500));

        mockMvc.perform(get("/api/v1/llm/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCalls").value(10))
                .andExpect(jsonPath("$.successCalls").value(8))
                .andExpect(jsonPath("$.failedCalls").value(2))
                .andExpect(jsonPath("$.avgDurationMs").value(320))
                .andExpect(jsonPath("$.totalPromptTokens").value(12000));
    }

    @Test
    @DisplayName("Hiç çağrı yokken boş dizi döner, null değil")
    void emptyStoreReturnsEmptyArray() throws Exception {
        when(llmReportStore.all()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/llm/calls"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("GET /tests/{id}/llm-report — HTML görünümü ve model nitelikleri")
    void htmlReportExposesModelAttributes() throws Exception {
        TestGenerationRequest request = requestWith(
                "## AI AGENT ANALYSIS\n\n### Product Manager Agent\nRisk analizi metni");
        request.setId("req-1");

        when(testGenerationService.getRequest("req-1")).thenReturn(request);
        when(testGenerationService.getTestCasesByRequestId("req-1")).thenReturn(List.of());
        when(llmReportStore.summary())
                .thenReturn(new LlmReportStore.LlmCallSummary(1, 1, 0, 100L, 10, 5));

        mockMvc.perform(get("/tests/req-1/llm-report"))
                .andExpect(status().isOk())
                .andExpect(view().name("llm-report"))
                .andExpect(model().attributeExists("request", "testCases", "analyses", "llmSummary"))
                .andExpect(model().attribute("formattedDate", "14.08.2026 15:30"));
    }

    /**
     * Ajan analizleri serbest metinden ayrıştırılıyor. Bölüm başlığı yoksa liste BOŞ
     * olmalı — hatalı ayrıştırma raporu çöp içerikle doldurur.
     */
    @Test
    @DisplayName("Ajan bölümü yoksa analiz listesi boş kalır")
    void noAgentSectionMeansEmptyAnalyses() throws Exception {
        TestGenerationRequest request = requestWith("Sadece düz bağlam metni");
        request.setId("req-2");

        when(testGenerationService.getRequest("req-2")).thenReturn(request);
        when(testGenerationService.getTestCasesByRequestId("req-2")).thenReturn(List.of());
        when(llmReportStore.summary())
                .thenReturn(new LlmReportStore.LlmCallSummary(0, 0, 0, 0L, 0, 0));

        mockMvc.perform(get("/tests/req-2/llm-report"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("analyses", org.hamcrest.Matchers.empty()));
    }

    @Test
    @DisplayName("Bağlam null iken rapor yine açılır, çökmez")
    void nullContextDoesNotBreakReport() throws Exception {
        TestGenerationRequest request = requestWith(null);
        request.setId("req-3");

        when(testGenerationService.getRequest("req-3")).thenReturn(request);
        when(testGenerationService.getTestCasesByRequestId("req-3")).thenReturn(List.of());
        when(llmReportStore.summary())
                .thenReturn(new LlmReportStore.LlmCallSummary(0, 0, 0, 0L, 0, 0));

        mockMvc.perform(get("/tests/req-3/llm-report"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("analyses", org.hamcrest.Matchers.empty()));
    }
}
