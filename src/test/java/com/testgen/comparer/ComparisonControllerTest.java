package com.testgen.comparer;

import com.testgen.config.BadRequestException;
import com.testgen.controller.WebLayerTest;
import com.testgen.model.ComparisonRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Endpoint Comparer API sözleşmesi.
 *
 * <p>Bu uç da sunucunun kendi ağından istek atar; Runner ile aynı SSRF yüzeyine sahiptir
 * ve reddin 400 olarak yüzeye çıkması burada da kilitleniyor. Ayrıca doğrulama
 * kuralları önemli: iki base URL zorunlu, en az bir istek kaynağı zorunlu — bunlar
 * boş geçerse koşum hiçbir şey karşılaştırmadan "başarılı" görünür.
 */
@WebLayerTest
class ComparisonControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EndpointComparisonService comparisonService;

    private static final String VALID_BODY = """
            {
              "baseUrlA": "http://localhost:8081",
              "baseUrlB": "http://localhost:8082",
              "requests": [{"method":"GET","path":"/api/pets"}]
            }
            """;

    private ComparisonResultDto result() {
        return new ComparisonResultDto(
                "http://localhost:8081", "http://localhost:8082",
                LocalDateTime.of(2026, 8, 14, 12, 0), 250L,
                new ComparisonResultDto.Summary(1, 1, 0, 0),
                List.of());
    }

    private ComparisonRun run(String id) {
        ComparisonRun r = new ComparisonRun();
        r.setId(id);
        r.setBaseUrlA("http://localhost:8081");
        r.setBaseUrlB("http://localhost:8082");
        r.setTotalRequests(3);
        r.setIdenticalCount(2);
        r.setDifferentCount(1);
        r.setErrorCount(0);
        r.setTotalDurationMs(420L);
        r.setExecutedAt(LocalDateTime.of(2026, 8, 14, 12, 0));
        r.setResultJson("{\"summary\":{}}");
        return r;
    }

    @Test
    @DisplayName("POST /run — karşılaştırma özetini döner")
    void runReturnsSummary() throws Exception {
        when(comparisonService.compare(any())).thenReturn(result());

        mockMvc.perform(post("/api/v1/comparison/run")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUrlA").value("http://localhost:8081"))
                .andExpect(jsonPath("$.summary.totalRequests").value(1))
                .andExpect(jsonPath("$.summary.identicalCount").value(1));
    }

    /**
     * İstek kaynağı verilmezse koşum hiçbir şey karşılaştırmaz ama "başarılı" görünür —
     * sessiz yanlış güven. Bu yüzden 400 ile reddedilmeli.
     */
    @Test
    @DisplayName("Ne istek listesi ne collection verilirse 400 döner")
    void rejectsWhenNoRequestSourceGiven() throws Exception {
        mockMvc.perform(post("/api/v1/comparison/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseUrlA\":\"http://a.dev\",\"baseUrlB\":\"http://b.dev\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("collectionJson")));

        verify(comparisonService, never()).compare(any());
    }

    @Test
    @DisplayName("baseUrlA boşsa doğrulama hatası 400 döner")
    void rejectsBlankBaseUrl() throws Exception {
        mockMvc.perform(post("/api/v1/comparison/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseUrlA\":\"\",\"baseUrlB\":\"http://b.dev\","
                                + "\"requests\":[{\"method\":\"GET\",\"path\":\"/x\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("baseUrlA")));
    }

    @Test
    @DisplayName("İstek içinde method boşsa doğrulama hatası 400 döner")
    void rejectsRequestWithoutMethod() throws Exception {
        mockMvc.perform(post("/api/v1/comparison/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseUrlA\":\"http://a.dev\",\"baseUrlB\":\"http://b.dev\","
                                + "\"requests\":[{\"method\":\"\",\"path\":\"/x\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("method")));
    }

    @Test
    @DisplayName("timeoutSeconds üst sınırı aşarsa 400 döner")
    void rejectsTimeoutAboveLimit() throws Exception {
        mockMvc.perform(post("/api/v1/comparison/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseUrlA\":\"http://a.dev\",\"baseUrlB\":\"http://b.dev\","
                                + "\"requests\":[{\"method\":\"GET\",\"path\":\"/x\"}],"
                                + "\"timeoutSeconds\":999}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("SSRF reddi 400 olarak yüzeye çıkar")
    void ssrfRejectionMapsToBadRequest() throws Exception {
        when(comparisonService.compare(any())).thenThrow(new BadRequestException(
                "baseUrlA reddedildi — Bulut metadata adreslerine istek atılamaz: 169.254.169.254"));

        mockMvc.perform(post("/api/v1/comparison/run")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("metadata")));
    }

    @Test
    @DisplayName("GET /history — geçmiş koşum özetlerini döner")
    void historyReturnsSummaries() throws Exception {
        when(comparisonService.history()).thenReturn(List.of(run("r-1")));

        mockMvc.perform(get("/api/v1/comparison/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("r-1"))
                .andExpect(jsonPath("$[0].totalRequests").value(3))
                .andExpect(jsonPath("$[0].differentCount").value(1))
                .andExpect(jsonPath("$[0].totalDurationMs").value(420));
    }

    @Test
    @DisplayName("GET /history — kayıt yokken boş dizi döner")
    void historyReturnsEmptyArrayWhenNoRuns() throws Exception {
        when(comparisonService.history()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/comparison/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("GET /history/{id} — kayıtlı sonucun ham JSON'ını döner")
    void historyDetailReturnsStoredJson() throws Exception {
        when(comparisonService.historyDetail("r-1")).thenReturn(run("r-1"));

        mockMvc.perform(get("/api/v1/comparison/history/r-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").exists());
    }

    @Test
    @DisplayName("Bulunamayan geçmiş kaydı 404 döner")
    void missingHistoryMapsToNotFound() throws Exception {
        when(comparisonService.historyDetail("yok"))
                .thenThrow(new IllegalArgumentException("Karşılaştırma bulunamadı: yok"));

        mockMvc.perform(get("/api/v1/comparison/history/yok"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("POST /parse-collection — istek sayısı ve listesini döner")
    void parseCollectionReturnsRequests() throws Exception {
        when(comparisonService.parseCollection(anyString())).thenReturn(List.of(
                new ComparisonHttpRequestDto("Pets", "GET", "/api/pets", Map.of(), null)));

        mockMvc.perform(post("/api/v1/comparison/parse-collection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"collectionJson\":\"{\\\"item\\\":[]}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestCount").value(1))
                .andExpect(jsonPath("$.requests[0].method").value("GET"))
                .andExpect(jsonPath("$.requests[0].path").value("/api/pets"));
    }

    @Test
    @DisplayName("collectionJson boşsa 400 döner ve ayrıştırma denenmez")
    void rejectsBlankCollectionJson() throws Exception {
        mockMvc.perform(post("/api/v1/comparison/parse-collection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"collectionJson\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("collectionJson")));

        verify(comparisonService, never()).parseCollection(anyString());
    }
}
