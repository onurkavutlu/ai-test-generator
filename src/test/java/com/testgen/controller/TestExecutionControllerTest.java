package com.testgen.controller;

import com.testgen.model.ExecutionStatus;
import com.testgen.model.ExecutionTrigger;
import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestExecution;
import com.testgen.model.TestExecutionResult;
import com.testgen.model.TestFramework;
import com.testgen.model.TestRunStatus;
import com.testgen.runner.TestRunnerService;
import com.testgen.service.TestExecutionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Koşum geçmişi API sözleşmesi.
 *
 * <p>Kritik nokta: yeniden koşum YENİ bir kayıt üretmeli, eskisini ezmemeli — koşum
 * geçmişi denetlenebilirliğin temelidir. Ayrıca geçiş oranının yuvarlanması sözleşmenin
 * parçası; ham double dönerse arayüzde 66.66666666666667 gibi değerler görünür.
 */
@WebLayerTest
class TestExecutionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TestExecutionService executionService;

    @MockitoBean
    private TestRunnerService testRunnerService;

    private TestExecution execution(String id, int total, int passed, int failed) {
        TestExecution e = new TestExecution();
        e.setId(id);
        e.setName("Gece koşumu");
        e.setStatus(ExecutionStatus.PASSED);
        e.setTrigger(ExecutionTrigger.PLAN);
        e.setPlanId("p-1");
        e.setPlanName("Regresyon Planı");
        e.setTotalCases(total);
        e.setPassedCases(passed);
        e.setFailedCases(failed);
        e.setResults(new ArrayList<>());
        return e;
    }

    private TestExecutionResult result(String caseId, TestRunStatus runStatus) {
        TestExecutionResult r = new TestExecutionResult();
        r.setTestCaseId(caseId);
        r.setTestName("GetPet");
        r.setFramework(TestFramework.KARATE);
        r.setRunStatus(runStatus);
        r.setTotalScenarios(4);
        r.setPassedScenarios(3);
        r.setFailedScenarios(1);
        r.setExecutionTimeMs(1200L);
        return r;
    }

    @Test
    @DisplayName("GET /api/v1/executions — koşum geçmişini listeler")
    void listsExecutions() throws Exception {
        when(executionService.list(null, null)).thenReturn(List.of(execution("e-1", 10, 8, 2)));

        mockMvc.perform(get("/api/v1/executions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("e-1"))
                .andExpect(jsonPath("$[0].status").value("PASSED"))
                .andExpect(jsonPath("$[0].trigger").value("PLAN"))
                .andExpect(jsonPath("$[0].totalCases").value(10));
    }

    @Test
    @DisplayName("planId ve suiteId filtreleri servise aynen iletilir")
    void filtersArePassedThrough() throws Exception {
        when(executionService.list("p-1", "s-1")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/executions").param("planId", "p-1").param("suiteId", "s-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        verify(executionService).list("p-1", "s-1");
    }

    /**
     * Geçiş oranı tek ondalığa yuvarlanmalı. Ham double döndüğünde arayüzde
     * 66.66666666666667 gibi okunamaz değerler görünür.
     */
    @Test
    @DisplayName("Geçiş oranı tek ondalığa yuvarlanır")
    void passRateIsRoundedToOneDecimal() throws Exception {
        when(executionService.list(null, null)).thenReturn(List.of(execution("e-1", 3, 2, 1)));

        mockMvc.perform(get("/api/v1/executions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].passRate").value(66.7));
    }

    @Test
    @DisplayName("Sıfır case'li koşumda oran 0 döner, bölme hatası olmaz")
    void zeroCasesGivesZeroPassRate() throws Exception {
        when(executionService.list(null, null)).thenReturn(List.of(execution("e-0", 0, 0, 0)));

        mockMvc.perform(get("/api/v1/executions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].passRate").value(0.0));
    }

    @Test
    @DisplayName("GET /api/v1/executions/{id} — detay case bazlı sonuçları taşır")
    void detailCarriesPerCaseResults() throws Exception {
        TestExecution e = execution("e-1", 1, 0, 1);
        e.getResults().add(result("c-1", TestRunStatus.FAILED));
        when(executionService.get("e-1")).thenReturn(e);

        mockMvc.perform(get("/api/v1/executions/e-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].testCaseId").value("c-1"))
                .andExpect(jsonPath("$.results[0].runStatus").value("FAILED"))
                .andExpect(jsonPath("$.results[0].failedScenarios").value(1))
                .andExpect(jsonPath("$.results[0].executionTimeMs").value(1200));
    }

    @Test
    @DisplayName("Bulunamayan koşum 404 döner")
    void missingExecutionMapsToNotFound() throws Exception {
        when(executionService.get("yok")).thenThrow(new IllegalArgumentException("Koşum bulunamadı: yok"));

        mockMvc.perform(get("/api/v1/executions/yok"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    /**
     * Yeniden koşum asenkron ve YENİ kayıt üretir. Yanıt, kaynak koşumu ve kapsamdaki
     * case sayısını taşımalı — istemci "neyin yeniden koşulduğunu" böyle doğrular.
     */
    @Test
    @DisplayName("POST /{id}/rerun — 202 döner ve kaynak koşumu bildirir")
    void rerunReturnsAcceptedWithSourceInfo() throws Exception {
        when(executionService.get("e-1")).thenReturn(execution("e-1", 5, 5, 0));
        when(executionService.resolveCasesForRerun("e-1")).thenReturn(List.of(
                GeneratedTestCase.builder().id("c-1").build(),
                GeneratedTestCase.builder().id("c-2").build()));

        mockMvc.perform(post("/api/v1/executions/e-1/rerun"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sourceExecutionId").value("e-1"))
                .andExpect(jsonPath("$.sourceName").value("Gece koşumu"))
                .andExpect(jsonPath("$.caseCount").value(2));

        verify(testRunnerService).rerunExecution("e-1");
    }

    @Test
    @DisplayName("Var olmayan koşum yeniden çalıştırılmaya çalışıldığında runner tetiklenmez")
    void rerunOnMissingExecutionDoesNotTriggerRunner() throws Exception {
        when(executionService.get("yok")).thenThrow(new IllegalArgumentException("Koşum bulunamadı: yok"));

        mockMvc.perform(post("/api/v1/executions/yok/rerun"))
                .andExpect(status().isNotFound());

        verify(testRunnerService, never()).rerunExecution("yok");
    }
}
