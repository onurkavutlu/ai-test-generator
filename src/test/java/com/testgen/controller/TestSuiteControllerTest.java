package com.testgen.controller;

import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.model.TestRunStatus;
import com.testgen.model.TestSuite;
import com.testgen.runner.TestRunnerService;
import com.testgen.service.TestSuiteService;
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
 * Suite API sözleşmesi.
 *
 * <p>Bu testler iş mantığını değil <b>sözleşmeyi</b> kilitler: durum kodları, JSON alan
 * adları ve hata eşlemesi. Dashboard bu alan adlarına doğrudan bağlı; bir alan yeniden
 * adlandırıldığında derleme kırılmaz, arayüz sessizce boş gösterir. Ayrıca "bulunamadı"
 * durumunun 500 değil 404 döndüğü burada sabitlenir.
 */
@WebLayerTest
class TestSuiteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TestSuiteService suiteService;

    @MockitoBean
    private TestRunnerService testRunnerService;

    private TestSuite suite(String id, String name, GeneratedTestCase... cases) {
        TestSuite s = new TestSuite();
        s.setId(id);
        s.setName(name);
        s.setDescription("açıklama");
        s.setCreatedAt(LocalDateTime.of(2026, 8, 14, 10, 0));
        s.setTestCases(new ArrayList<>(List.of(cases)));
        return s;
    }

    private GeneratedTestCase testCase(String id, String name) {
        return GeneratedTestCase.builder()
                .id(id)
                .testName(name)
                .fileName(name + ".feature")
                .framework(TestFramework.KARATE)
                .runStatus(TestRunStatus.PASSED)
                .passedScenarios(3)
                .totalScenarios(3)
                .build();
    }

    @Test
    @DisplayName("POST /api/v1/suites — suite oluşturur ve özeti döner")
    void createReturnsSummary() throws Exception {
        when(suiteService.create(eq("Regresyon"), any())).thenReturn(suite("s-1", "Regresyon"));

        mockMvc.perform(post("/api/v1/suites")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Regresyon\",\"description\":\"açıklama\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("s-1"))
                .andExpect(jsonPath("$.name").value("Regresyon"))
                .andExpect(jsonPath("$.caseCount").value(0));
    }

    @Test
    @DisplayName("GET /api/v1/suites — liste her suite'in case sayısını içerir")
    void listIncludesCaseCounts() throws Exception {
        TestSuite s1 = suite("s-1", "Regresyon", testCase("c-1", "GetPet"));
        when(suiteService.list()).thenReturn(List.of(s1));
        when(suiteService.get("s-1")).thenReturn(s1);

        mockMvc.perform(get("/api/v1/suites"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("s-1"))
                .andExpect(jsonPath("$[0].caseCount").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/suites — suite yokken boş dizi döner, null değil")
    void listReturnsEmptyArrayWhenNoSuites() throws Exception {
        when(suiteService.list()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/suites"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("GET /api/v1/suites/{id} — detay case alanlarını taşır")
    void detailCarriesCaseFields() throws Exception {
        when(suiteService.get("s-1")).thenReturn(suite("s-1", "Regresyon", testCase("c-1", "GetPet")));

        mockMvc.perform(get("/api/v1/suites/s-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testCases[0].id").value("c-1"))
                .andExpect(jsonPath("$.testCases[0].testName").value("GetPet"))
                .andExpect(jsonPath("$.testCases[0].framework").value("KARATE"))
                .andExpect(jsonPath("$.testCases[0].runStatus").value("PASSED"))
                .andExpect(jsonPath("$.testCases[0].passedScenarios").value(3));
    }

    /**
     * Koşulmamış case'in runStatus'ü null'dur. Sözleşme bunu "NOT_RUN" metnine çevirir;
     * null dönerse dashboard boş hücre gösterip "koşuldu mu" sorusunu cevapsız bırakır.
     */
    @Test
    @DisplayName("Koşulmamış case runStatus alanında NOT_RUN döner, null değil")
    void neverRunCaseReportsNotRun() throws Exception {
        GeneratedTestCase notRun = GeneratedTestCase.builder()
                .id("c-2").testName("PostPet").fileName("PostPet.feature")
                .framework(TestFramework.KARATE).runStatus(null).build();
        when(suiteService.get("s-1")).thenReturn(suite("s-1", "Regresyon", notRun));

        mockMvc.perform(get("/api/v1/suites/s-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testCases[0].runStatus").value("NOT_RUN"));
    }

    @Test
    @DisplayName("Bulunamayan suite 500 değil 404 döner")
    void missingSuiteMapsToNotFound() throws Exception {
        when(suiteService.get("yok")).thenThrow(new IllegalArgumentException("Suite bulunamadı: yok"));

        mockMvc.perform(get("/api/v1/suites/yok"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Suite bulunamadı: yok"));
    }

    @Test
    @DisplayName("DELETE /api/v1/suites/{id} — siler ve onay döner")
    void deleteConfirms() throws Exception {
        mockMvc.perform(delete("/api/v1/suites/s-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suiteId").value("s-1"));

        verify(suiteService).delete("s-1");
    }

    @Test
    @DisplayName("POST /{id}/cases/{caseId} — case ekler, güncel sayıyı döner")
    void addCaseReturnsUpdatedCount() throws Exception {
        when(suiteService.addCase("s-1", "c-1"))
                .thenReturn(suite("s-1", "Regresyon", testCase("c-1", "GetPet")));

        mockMvc.perform(post("/api/v1/suites/s-1/cases/c-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseCount").value(1));
    }

    @Test
    @DisplayName("DELETE /{id}/cases/{caseId} — case çıkarır, güncel sayıyı döner")
    void removeCaseReturnsUpdatedCount() throws Exception {
        when(suiteService.removeCase("s-1", "c-1")).thenReturn(suite("s-1", "Regresyon"));

        mockMvc.perform(delete("/api/v1/suites/s-1/cases/c-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseCount").value(0));
    }

    /**
     * Koşum asenkrondur: 200 değil 202 dönmeli, aksi halde istemci sonucun hazır
     * olduğunu sanıp hemen detay çeker ve boş sonuç görür.
     */
    @Test
    @DisplayName("POST /{id}/run — asenkron koşum için 202 Accepted döner")
    void runReturnsAccepted() throws Exception {
        when(suiteService.get("s-1")).thenReturn(suite("s-1", "Regresyon"));

        mockMvc.perform(post("/api/v1/suites/s-1/run"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.suiteId").value("s-1"));

        verify(testRunnerService).runSuite("s-1");
    }

    /**
     * Var olmayan suite için koşum TETİKLENMEMELİ. Kontrol yapılmazsa runner arka planda
     * boş bir koşuma başlar ve hata yalnızca loglarda kalır.
     */
    @Test
    @DisplayName("Var olmayan suite koşulmaya çalışıldığında runner tetiklenmez")
    void runOnMissingSuiteDoesNotTriggerRunner() throws Exception {
        when(suiteService.get("yok")).thenThrow(new IllegalArgumentException("Suite bulunamadı: yok"));

        mockMvc.perform(post("/api/v1/suites/yok/run"))
                .andExpect(status().isNotFound());

        verify(testRunnerService, never()).runSuite("yok");
    }
}
