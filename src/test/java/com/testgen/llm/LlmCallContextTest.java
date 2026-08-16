package com.testgen.llm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * LLM çağrılarının hangi üretim isteğine ait olduğunun kaydedilmesi.
 *
 * <p><b>Kapatılan arıza:</b> Çağrı geçmişi ekranı 15 çağrıyı listeliyor ama hiçbirinin
 * hangi isteğe ait olduğu görünmüyordu — kayıtta böyle bir alan yoktu. Aynı anda iki
 * üretim koştuğunda maliyet ayrıştırılamıyor, bir isteğin toplam maliyeti
 * çıkarılamıyordu.
 *
 * <p>Bu sınıf korelasyonun kurulduğunu, iç içe fazlarda korunduğunu ve —en önemlisi—
 * bağlam yokken <b>uydurulmadığını</b> kilitler.
 */
class LlmCallContextTest {

    @AfterEach
    void tearDown() {
        LlmCallContext.clear();
    }

    private static LlmCallReport report() {
        return LlmCallReport.success("llama3.1", "KARATE", "prompt", "yanıt", 10L);
    }

    @Test
    @DisplayName("Bağlam kurulduğunda çağrı isteğe ve faza bağlanır")
    void reportCarriesRequestIdAndPhase() {
        LlmCallContext.set("req-1", LlmCallContext.Phase.GENERATION);

        LlmCallReport r = report();

        assertEquals("req-1", r.requestId());
        assertEquals("GENERATION", r.phase());
    }

    /**
     * En kritik davranış: korelasyon bilinmiyorsa boş kalır. Yanlış bir requestId,
     * hiç requestId olmamasından daha zararlıdır — maliyet yanlış isteğe yazılır.
     */
    @Test
    @DisplayName("Bağlam yoksa korelasyon uydurulmaz, null kalır")
    void missingContextIsNotFabricated() {
        LlmCallContext.clear();

        LlmCallReport r = report();

        assertNull(r.requestId());
        assertNull(r.phase());
    }

    @Nested
    @DisplayName("İç içe faz")
    class NestedPhase {

        /**
         * Doğrulama onarımı üretimin İÇİNDEN tetiklenir. requestId aynı kalmalı,
         * yalnızca faz değişmeli; aksi hâlde onarım çağrıları sahipsiz görünür.
         */
        @Test
        @DisplayName("Faz değişir, istek korunur ve sonra geri alınır")
        void phaseSwitchKeepsRequestIdAndRestores() {
            LlmCallContext.set("req-2", LlmCallContext.Phase.GENERATION);

            var previous = LlmCallContext.enterPhase(LlmCallContext.Phase.VALIDATION_REPAIR);
            LlmCallReport during = report();
            LlmCallContext.restore(previous);

            LlmCallReport after = report();

            assertEquals("req-2", during.requestId(), "onarım çağrısı isteğini kaybetti");
            assertEquals("VALIDATION_REPAIR", during.phase());
            assertEquals("GENERATION", after.phase(), "faz geri alınmadı");
        }

        @Test
        @DisplayName("Bağlam yokken faz değiştirmek bağlam yaratmaz")
        void enterPhaseWithoutContextStaysEmpty() {
            LlmCallContext.clear();

            LlmCallContext.enterPhase(LlmCallContext.Phase.SELF_HEAL);

            assertNull(LlmCallContext.currentRequestId());
            assertNull(LlmCallContext.currentPhase());
        }
    }

    /**
     * Havuz iş parçacıkları yeniden kullanılır. Bağlam temizlenmezse sonraki isteğin
     * çağrıları önceki isteğe yazılır — sessiz ve fark edilmesi zor bir hata.
     */
    @Test
    @DisplayName("clear() sonrası bağlam sızmaz")
    void contextDoesNotLeakAfterClear() {
        LlmCallContext.set("req-3", LlmCallContext.Phase.SELF_HEAL);
        LlmCallContext.clear();

        assertNull(new LlmCallReport("llama3.1", "KARATE", "özet",
                5, 5, 1L, true, null, null, LocalDateTime.now()).requestId());
    }
}
