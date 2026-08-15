package com.testgen.controller;

import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.report.CucumberReportService;
import com.testgen.service.TestGenerationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cucumber rapor sunumu.
 *
 * <p>İlginç davranış: rapor yoksa uç HATA DÖNMEZ — case'lerden raporu anında üretip
 * sunar. Bu "tembel üretim" kullanıcı için doğru davranış, ama case de yoksa 404
 * dönmeli; aksi halde boş bir HTML sayfası "rapor buymuş" gibi görünür.
 */
@WebLayerTest
class CucumberReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CucumberReportService cucumberReportService;

    @MockitoBean
    private TestGenerationService testGenerationService;

    private GeneratedTestCase aCase() {
        return GeneratedTestCase.builder()
                .id("c-1").testName("GetPet").fileName("GetPet.feature")
                .framework(TestFramework.KARATE).build();
    }

    @Test
    @DisplayName("GET /{id} — var olan raporu HTML olarak sunar")
    void servesExistingReportAsHtml() throws Exception {
        when(cucumberReportService.readReport("req-1"))
                .thenReturn(Optional.of("<html><body>Rapor</body></html>"));

        mockMvc.perform(get("/reports/cucumber/req-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("Rapor")));

        // Rapor zaten varken yeniden üretilmemeli
        verify(cucumberReportService, never()).generateReport(anyString(), any());
    }

    /**
     * Rapor yoksa case'lerden anında üretilir. Kullanıcı "önce rapor üret" adımını
     * bilmek zorunda kalmaz.
     */
    @Test
    @DisplayName("Rapor yoksa case'lerden üretilip sunulur")
    void generatesReportOnDemandWhenMissing() throws Exception {
        when(cucumberReportService.readReport("req-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of("<html>üretildi</html>"));
        when(testGenerationService.getTestCasesByRequestId("req-1")).thenReturn(List.of(aCase()));

        mockMvc.perform(get("/reports/cucumber/req-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("üretildi")));

        verify(cucumberReportService).generateReport(anyString(), any());
    }

    /**
     * Case yoksa üretilecek bir şey de yoktur. Boş HTML dönmek "rapor bu" yanılgısı
     * yaratır; 404 doğru cevaptır.
     */
    @Test
    @DisplayName("Rapor da case de yoksa 404 döner, boş sayfa değil")
    void returnsNotFoundWhenNoReportAndNoCases() throws Exception {
        when(cucumberReportService.readReport("yok")).thenReturn(Optional.empty());
        when(testGenerationService.getTestCasesByRequestId("yok")).thenReturn(List.of());

        mockMvc.perform(get("/reports/cucumber/yok"))
                .andExpect(status().isNotFound());

        verify(cucumberReportService, never()).generateReport(anyString(), any());
    }

    @Test
    @DisplayName("Üretim denendiği hâlde rapor okunamıyorsa 500 döner")
    void returnsServerErrorWhenGenerationYieldsNothing() throws Exception {
        when(cucumberReportService.readReport("req-2")).thenReturn(Optional.empty());
        when(testGenerationService.getTestCasesByRequestId("req-2")).thenReturn(List.of(aCase()));

        mockMvc.perform(get("/reports/cucumber/req-2"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    @DisplayName("POST /{id}/build — raporu yeniden üretir ve yolunu bildirir")
    void buildRegeneratesReport() throws Exception {
        when(testGenerationService.getTestCasesByRequestId("req-1")).thenReturn(List.of(aCase()));
        when(cucumberReportService.generateReport(anyString(), any()))
                .thenReturn(Path.of("/tmp/rapor.html"));

        mockMvc.perform(post("/reports/cucumber/req-1/build"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/reports/cucumber/req-1")));
    }

    @Test
    @DisplayName("Case yokken yeniden üretim 400 döner")
    void buildWithoutCasesIsBadRequest() throws Exception {
        when(testGenerationService.getTestCasesByRequestId("yok")).thenReturn(List.of());

        mockMvc.perform(post("/reports/cucumber/yok/build"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("test case bulunamadı")));

        verify(cucumberReportService, never()).generateReport(anyString(), any());
    }

    @Test
    @DisplayName("Üretim null yol dönerse 500 ile bildirilir")
    void buildFailureIsReportedAsServerError() throws Exception {
        when(testGenerationService.getTestCasesByRequestId("req-1")).thenReturn(List.of(aCase()));
        when(cucumberReportService.generateReport(anyString(), any())).thenReturn(null);

        mockMvc.perform(post("/reports/cucumber/req-1/build"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(containsString("Rapor üretilemedi")));
    }
}
