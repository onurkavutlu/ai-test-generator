package com.testgen.controller;

import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestPlan;
import com.testgen.model.TestRunStatus;
import com.testgen.model.TestSuite;
import com.testgen.model.TestType;
import com.testgen.model.ValidationStatus;
import com.testgen.runner.TestRunnerService;
import com.testgen.service.TestPlanService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test Plan API sözleşmesi — hiyerarşinin en üst katmanı (Plan → Suite → Execution).
 *
 * <p>Plan detayında hem suite listesi hem de çözümlenmiş case listesi döner; ikisi
 * ayrı kaynaklardan gelir ve biri boş kalırsa arayüz "plan boş" gösterir. Bu yüzden
 * her ikisi ayrı ayrı kilitleniyor.
 */
@WebLayerTest
class TestPlanControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TestPlanService planService;

    @MockitoBean
    private TestRunnerService testRunnerService;

    private TestPlan plan(String id, String name, TestSuite... suites) {
        TestPlan p = new TestPlan();
        p.setId(id);
        p.setName(name);
        p.setDescription("plan açıklaması");
        p.setVersion("v1.0");
        p.setSuites(new ArrayList<>(List.of(suites)));
        p.setCreatedAt(LocalDateTime.of(2026, 8, 14, 9, 0));
        return p;
    }

    private TestSuite suite(String id, String name, int caseCount) {
        TestSuite s = new TestSuite();
        s.setId(id);
        s.setName(name);
        s.setDescription("suite açıklaması");
        List<GeneratedTestCase> cases = new ArrayList<>();
        for (int i = 0; i < caseCount; i++) {
            cases.add(GeneratedTestCase.builder().id("c-" + i).testName("Case" + i)
                    .framework(TestFramework.KARATE).runStatus(TestRunStatus.PASSED).build());
        }
        s.setTestCases(cases);
        return s;
    }

    @Test
    @DisplayName("POST /api/v1/plans — plan oluşturur ve sürüm bilgisini döner")
    void createReturnsSummaryWithVersion() throws Exception {
        when(planService.create(eq("Regresyon Planı"), any(), eq("v1.0")))
                .thenReturn(plan("p-1", "Regresyon Planı"));

        mockMvc.perform(post("/api/v1/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Regresyon Planı\",\"description\":\"d\",\"version\":\"v1.0\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("p-1"))
                .andExpect(jsonPath("$.version").value("v1.0"))
                .andExpect(jsonPath("$.suiteCount").value(0));
    }

    @Test
    @DisplayName("GET /api/v1/plans — liste suite sayısını içerir")
    void listIncludesSuiteCount() throws Exception {
        TestPlan p = plan("p-1", "Regresyon Planı", suite("s-1", "Smoke", 2));
        when(planService.list()).thenReturn(List.of(p));
        when(planService.get("p-1")).thenReturn(p);

        mockMvc.perform(get("/api/v1/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].suiteCount").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/plans/{id} — detay hem suite'leri hem çözümlenmiş case'leri taşır")
    void detailCarriesSuitesAndResolvedCases() throws Exception {
        when(planService.get("p-1")).thenReturn(plan("p-1", "Regresyon Planı", suite("s-1", "Smoke", 2)));
        when(planService.resolveCases("p-1")).thenReturn(List.of(
                GeneratedTestCase.builder().id("c-1").testName("GetPet")
                        .framework(TestFramework.KARATE).runStatus(TestRunStatus.PASSED).build()));

        mockMvc.perform(get("/api/v1/plans/p-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suites[0].id").value("s-1"))
                .andExpect(jsonPath("$.suites[0].caseCount").value(2))
                .andExpect(jsonPath("$.cases[0].id").value("c-1"))
                .andExpect(jsonPath("$.cases[0].testName").value("GetPet"));
    }

    @Test
    @DisplayName("Plan detayı case'in kanıt türü ve doğrulama meta verisini taşır")
    void detailCarriesEvidenceMetadata() throws Exception {
        TestGenerationRequest request = TestGenerationRequest.builder().id("req-plan-1")
                .testType(TestType.FRONTEND_WEB).framework(TestFramework.SELENIUM)
                .additionalContext("## OBSERVED USER FLOW\n1. tıkla: Ürünler").build();
        GeneratedTestCase flowCase = GeneratedTestCase.builder().id("c-flow").testName("ObservedUserFlowTest")
                .framework(TestFramework.SELENIUM).request(request).deterministic(true)
                .validationStatus(ValidationStatus.VALID).build();
        when(planService.get("p-1")).thenReturn(plan("p-1", "Frontend Planı"));
        when(planService.resolveCases("p-1")).thenReturn(List.of(flowCase));

        mockMvc.perform(get("/api/v1/plans/p-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases[0].requestId").value("req-plan-1"))
                .andExpect(jsonPath("$.cases[0].evidenceType").value("OBSERVED_USER_FLOW"))
                .andExpect(jsonPath("$.cases[0].deterministic").value(true))
                .andExpect(jsonPath("$.cases[0].validationStatus").value("VALID"));
    }

    @Test
    @DisplayName("Suite'i olmayan planda suiteCount 0 döner, null değil")
    void planWithoutSuitesReportsZero() throws Exception {
        TestPlan p = plan("p-1", "Boş Plan");
        p.setSuites(null);
        when(planService.get("p-1")).thenReturn(p);
        when(planService.resolveCases("p-1")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/plans/p-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suiteCount").value(0));
    }

    @Test
    @DisplayName("Bulunamayan plan 404 döner")
    void missingPlanMapsToNotFound() throws Exception {
        when(planService.get("yok")).thenThrow(new IllegalArgumentException("Plan bulunamadı: yok"));

        mockMvc.perform(get("/api/v1/plans/yok"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("DELETE /api/v1/plans/{id} — siler ve onay döner")
    void deleteConfirms() throws Exception {
        mockMvc.perform(delete("/api/v1/plans/p-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").value("p-1"));

        verify(planService).delete("p-1");
    }

    @Test
    @DisplayName("POST /{id}/suites/{suiteId} — plana suite ekler")
    void addSuiteReturnsUpdatedPlan() throws Exception {
        when(planService.addSuite("p-1", "s-1"))
                .thenReturn(plan("p-1", "Regresyon Planı", suite("s-1", "Smoke", 1)));

        mockMvc.perform(post("/api/v1/plans/p-1/suites/s-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suiteCount").value(1));
    }

    @Test
    @DisplayName("DELETE /{id}/suites/{suiteId} — plandan suite çıkarır")
    void removeSuiteReturnsUpdatedPlan() throws Exception {
        when(planService.removeSuite("p-1", "s-1")).thenReturn(plan("p-1", "Regresyon Planı"));

        mockMvc.perform(delete("/api/v1/plans/p-1/suites/s-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suiteCount").value(0));
    }

    @Test
    @DisplayName("POST /{id}/run — 202 döner ve koşulacak case sayısını bildirir")
    void runReturnsAcceptedWithCaseCount() throws Exception {
        when(planService.get("p-1")).thenReturn(plan("p-1", "Regresyon Planı"));
        when(planService.resolveCases("p-1")).thenReturn(List.of(
                GeneratedTestCase.builder().id("c-1").build(),
                GeneratedTestCase.builder().id("c-2").build(),
                GeneratedTestCase.builder().id("c-3").build()));

        mockMvc.perform(post("/api/v1/plans/p-1/run"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.planId").value("p-1"))
                .andExpect(jsonPath("$.planName").value("Regresyon Planı"))
                .andExpect(jsonPath("$.caseCount").value(3));

        verify(testRunnerService).runPlan("p-1");
    }

    @Test
    @DisplayName("Var olmayan plan koşulmaya çalışıldığında runner tetiklenmez")
    void runOnMissingPlanDoesNotTriggerRunner() throws Exception {
        when(planService.get("yok")).thenThrow(new IllegalArgumentException("Plan bulunamadı: yok"));

        mockMvc.perform(post("/api/v1/plans/yok/run"))
                .andExpect(status().isNotFound());

        verify(testRunnerService, never()).runPlan("yok");
    }
}
