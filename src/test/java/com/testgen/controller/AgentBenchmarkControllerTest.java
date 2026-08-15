package com.testgen.controller;

import com.testgen.model.AgentBenchmarkResult;
import com.testgen.model.AgentBenchmarkRun;
import com.testgen.model.BenchmarkArm;
import com.testgen.model.BenchmarkComparison;
import com.testgen.model.BenchmarkStatus;
import com.testgen.model.TestFramework;
import com.testgen.model.TestType;
import com.testgen.service.AgentBenchmarkService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ajan ölçüm (A/B) API sözleşmesi.
 *
 * <p>Bu uç, projenin "çok-ajanlı analiz gerçekten işe yarıyor mu" sorusunu ölçen yerdir;
 * dolayısıyla raporun DÜRÜST olması kritik. En önemli davranış: bir kolda hiç ölçüm
 * yoksa ortalamalar <b>null kalmalı, sıfır uydurulmamalı</b> — sıfır dönerse "ajanlar
 * hiç case üretmedi" gibi okunur ve yanlış sonuca götürür.
 */
@WebLayerTest
class AgentBenchmarkControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AgentBenchmarkService benchmarkService;

    private AgentBenchmarkRun run(String id, BenchmarkComparison comparison,
                                  AgentBenchmarkResult... results) {
        AgentBenchmarkRun r = AgentBenchmarkRun.builder()
                .name("Ajan katkısı ölçümü")
                .testType(TestType.BACKEND_API)
                .framework(TestFramework.KARATE)
                .repetitions(2)
                .runTests(false)
                .comparison(comparison)
                .build();
        r.setId(id);
        r.setStatus(BenchmarkStatus.COMPLETED);
        r.setResults(new ArrayList<>(List.of(results)));
        return r;
    }

    private AgentBenchmarkResult result(BenchmarkArm arm, int caseCount, int validCases, int llmCalls) {
        AgentBenchmarkResult r = new AgentBenchmarkResult();
        r.setArm(arm);
        r.setIteration(1);
        r.setCaseCount(caseCount);
        r.setValidCases(validCases);
        r.setInvalidCases(caseCount - validCases);
        r.setLlmCalls(llmCalls);
        r.setLlmDurationMs(1000L);
        r.setLlmPromptChars(5000L);
        return r;
    }

    @Test
    @DisplayName("POST — 202 döner ve toplam üretim sayısını bildirir")
    void startReturnsAcceptedWithTotalGenerations() throws Exception {
        when(benchmarkService.create(any())).thenReturn(run("b-1", BenchmarkComparison.AGENTS_ON_OFF));

        mockMvc.perform(post("/api/v1/benchmarks/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ajan katkısı ölçümü\",\"testType\":\"BACKEND_API\","
                                + "\"framework\":\"KARATE\",\"repetitions\":2}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value("b-1"))
                .andExpect(jsonPath("$.repetitions").value(2))
                // İki kol koştuğu için toplam üretim tekrar sayısının iki katıdır
                .andExpect(jsonPath("$.totalGenerations").value(4));

        verify(benchmarkService).execute("b-1");
    }

    @Test
    @DisplayName("repetitions verilmezse 1 varsayılır")
    void defaultsRepetitionsToOne() throws Exception {
        when(benchmarkService.create(any())).thenReturn(run("b-1", BenchmarkComparison.AGENTS_ON_OFF));

        mockMvc.perform(post("/api/v1/benchmarks/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"testType\":\"BACKEND_API\",\"framework\":\"KARATE\"}"))
                .andExpect(status().isAccepted());

        ArgumentCaptor<AgentBenchmarkRun> captor = ArgumentCaptor.forClass(AgentBenchmarkRun.class);
        verify(benchmarkService).create(captor.capture());
        assertEquals(1, captor.getValue().getRepetitions());
    }

    @Test
    @DisplayName("comparison verilmezse AGENTS_ON_OFF varsayılır")
    void defaultsComparisonToAgentsOnOff() throws Exception {
        when(benchmarkService.create(any())).thenReturn(run("b-1", BenchmarkComparison.AGENTS_ON_OFF));

        mockMvc.perform(post("/api/v1/benchmarks/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"testType\":\"BACKEND_API\",\"framework\":\"KARATE\"}"))
                .andExpect(status().isAccepted());

        ArgumentCaptor<AgentBenchmarkRun> captor = ArgumentCaptor.forClass(AgentBenchmarkRun.class);
        verify(benchmarkService).create(captor.capture());
        assertEquals(BenchmarkComparison.AGENTS_ON_OFF, captor.getValue().getComparison());
    }

    @Test
    @DisplayName("GET — koşum listesini özetleriyle döner")
    void listReturnsSummaries() throws Exception {
        when(benchmarkService.list()).thenReturn(List.of(run("b-1", BenchmarkComparison.AGENTS_ON_OFF)));

        mockMvc.perform(get("/api/v1/benchmarks/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("b-1"))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[0].comparison").value("AGENTS_ON_OFF"));
    }

    @Test
    @DisplayName("GET /{id} — iki kolun ortalamalarını ve farkını döner")
    void detailReportsBothArmsAndDelta() throws Exception {
        when(benchmarkService.get("b-1")).thenReturn(run("b-1", BenchmarkComparison.AGENTS_ON_OFF,
                result(BenchmarkArm.WITH_AGENTS, 10, 9, 8),
                result(BenchmarkArm.WITHOUT_AGENTS, 6, 4, 2)));

        mockMvc.perform(get("/api/v1/benchmarks/agents/b-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.WITH_AGENTS.samples").value(1))
                .andExpect(jsonPath("$.WITH_AGENTS.avgCaseCount").value(10.0))
                .andExpect(jsonPath("$.WITHOUT_AGENTS.avgCaseCount").value(6.0))
                .andExpect(jsonPath("$.delta").exists());
    }

    /**
     * AGENTS_ON_OFF ekseninde arayüz eski {@code withAgents}/{@code withoutAgents}
     * alanlarını okuyor. Bu alanlar düşerse dashboard sessizce boş gösterir.
     */
    @Test
    @DisplayName("AGENTS_ON_OFF ekseninde geriye dönük alan adları da doldurulur")
    void backwardCompatibleFieldNamesArePresent() throws Exception {
        when(benchmarkService.get("b-1")).thenReturn(run("b-1", BenchmarkComparison.AGENTS_ON_OFF,
                result(BenchmarkArm.WITH_AGENTS, 10, 9, 8),
                result(BenchmarkArm.WITHOUT_AGENTS, 6, 4, 2)));

        mockMvc.perform(get("/api/v1/benchmarks/agents/b-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.withAgents.avgCaseCount").value(10.0))
                .andExpect(jsonPath("$.withoutAgents.avgCaseCount").value(6.0));
    }

    @Test
    @DisplayName("LEAN_VS_FULL ekseninde geriye dönük alanlar YOKTUR, kol adları kullanılır")
    void leanVsFullUsesArmNamesOnly() throws Exception {
        when(benchmarkService.get("b-2")).thenReturn(run("b-2", BenchmarkComparison.LEAN_VS_FULL,
                result(BenchmarkArm.LEAN_AGENTS, 8, 7, 4),
                result(BenchmarkArm.FULL_AGENTS, 9, 8, 9)));

        mockMvc.perform(get("/api/v1/benchmarks/agents/b-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.LEAN_AGENTS.avgCaseCount").value(8.0))
                .andExpect(jsonPath("$.FULL_AGENTS.avgCaseCount").value(9.0))
                .andExpect(jsonPath("$.withAgents").doesNotExist());
    }

    /**
     * Ölçüm yoksa sıfır UYDURULMAMALI: 0 değeri "ajanlar hiç case üretmedi" diye
     * okunur ve ölçümün tüm sonucunu ters çevirir. Alan hiç bulunmamalı.
     */
    @Test
    @DisplayName("Ölçümü olmayan kolda ortalama alanları hiç yer almaz, sıfır uydurulmaz")
    void armWithoutSamplesOmitsAveragesInsteadOfZero() throws Exception {
        when(benchmarkService.get("b-1")).thenReturn(run("b-1", BenchmarkComparison.AGENTS_ON_OFF,
                result(BenchmarkArm.WITH_AGENTS, 10, 9, 8)));

        mockMvc.perform(get("/api/v1/benchmarks/agents/b-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.WITHOUT_AGENTS.samples").value(0))
                .andExpect(jsonPath("$.WITHOUT_AGENTS.avgCaseCount").doesNotExist());
    }

    @Test
    @DisplayName("Bulunamayan ölçüm koşumu 404 döner")
    void missingRunMapsToNotFound() throws Exception {
        when(benchmarkService.get("yok"))
                .thenThrow(new IllegalArgumentException("Ölçüm koşumu bulunamadı: yok"));

        mockMvc.perform(get("/api/v1/benchmarks/agents/yok"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
