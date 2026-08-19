package com.testgen.controller;

import com.testgen.config.BadRequestException;
import com.testgen.service.DataRetentionResult;
import com.testgen.service.DataRetentionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bakım ucunun sözleşmesi.
 *
 * <p>En kritik satır <b>varsayılanların</b> testidir: Swagger'dan parametresiz tetikleyen
 * bir kullanıcı 30 gün + önizleme almalıdır. Bu varsayılan sessizce değişirse, "ne
 * silineceğine bakayım" diyen biri veriyi siler.
 */
@WebLayerTest
class DataRetentionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DataRetentionService dataRetentionService;

    private DataRetentionResult result(boolean dryRun, int days, long requests, long cases) {
        return new DataRetentionResult(dryRun, days,
                LocalDateTime.of(2026, 7, 20, 12, 0), requests, cases, 0, List.of());
    }

    @Test
    @DisplayName("Parametresiz çağrı 30 gün + önizleme varsayılanını kullanır")
    void defaultsAreThirtyDaysAndDryRun() throws Exception {
        when(dataRetentionService.purge(anyInt(), anyBoolean())).thenReturn(result(true, 30, 5, 12));

        mockMvc.perform(delete("/api/v1/maintenance/test-data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.olderThanDays").value(30))
                .andExpect(jsonPath("$.deletedRequests").value(5))
                .andExpect(jsonPath("$.deletedTestCases").value(12));

        verify(dataRetentionService).purge(eq(30), eq(true));
    }

    @Test
    @DisplayName("dryRun=false açıkça verildiğinde servise aynen iletilir")
    void explicitPurgeIsForwarded() throws Exception {
        when(dataRetentionService.purge(anyInt(), anyBoolean())).thenReturn(result(false, 7, 3, 9));

        mockMvc.perform(delete("/api/v1/maintenance/test-data")
                        .param("olderThanDays", "7")
                        .param("dryRun", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(false))
                .andExpect(jsonPath("$.olderThanDays").value(7));

        verify(dataRetentionService).purge(eq(7), eq(false));
    }

    @Test
    @DisplayName("Geçersiz gün eşiği 400 döner — 500 değil")
    void invalidRetentionReturnsBadRequest() throws Exception {
        when(dataRetentionService.purge(anyInt(), anyBoolean()))
                .thenThrow(new BadRequestException("olderThanDays en az 1 olmalıdır"));

        mockMvc.perform(delete("/api/v1/maintenance/test-data").param("olderThanDays", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Korunan istekler yanıtta kimlikleriyle raporlanır")
    void protectedRequestsAreReported() throws Exception {
        when(dataRetentionService.purge(anyInt(), anyBoolean())).thenReturn(
                new DataRetentionResult(true, 30, LocalDateTime.of(2026, 7, 20, 12, 0),
                        1, 2, 2, List.of("r-a", "r-b")));

        mockMvc.perform(delete("/api/v1/maintenance/test-data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.protectedRequestCount").value(2))
                .andExpect(jsonPath("$.protectedRequestIds[0]").value("r-a"))
                .andExpect(jsonPath("$.protectedRequestIds[1]").value("r-b"));
    }
}
