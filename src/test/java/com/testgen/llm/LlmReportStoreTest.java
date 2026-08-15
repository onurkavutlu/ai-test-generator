package com.testgen.llm;

import com.testgen.metrics.TestGenMetrics;
import com.testgen.model.LlmCallLog;
import com.testgen.repository.LlmCallLogRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LLM çağrı geçmişi deposu.
 *
 * <p>Sınıfın kendi yorumu yaşanmış bir hatayı anlatıyor: kayıtlar DB'ye yazılıyordu ama
 * hiçbir okuma yolu tabloya bakmadığı için her yeniden başlatmada arayüz "çağrı geçmişi
 * bulunamadı" gösteriyordu — <b>veri duruyor, görünmüyordu</b>. Çözüm PostConstruct ile
 * DB'den geri yükleme; bu test onu kilitler.
 *
 * <p>İkinci kritik davranış: DB yazımı best-effort olmalı. Log kaydı LLM akışını asla
 * durdurmamalı — kaydedilemeyen bir metrik yüzünden test üretimi çökerse bu, çözdüğünden
 * çok daha büyük bir sorun yaratır.
 */
class LlmReportStoreTest {

    private LlmCallLogRepository repository;
    private LlmReportStore store;

    @BeforeEach
    void setUp() {
        repository = mock(LlmCallLogRepository.class);
        when(repository.findRecent(any(Pageable.class))).thenReturn(List.of());
        store = new LlmReportStore(repository, new TestGenMetrics(new SimpleMeterRegistry()));
    }

    private LlmCallReport report(String callType, boolean success, long durationMs) {
        return new LlmCallReport("llama3.1", callType, "özet",
                1000, 500, durationMs, success, success ? null : "hata",
                "ham yanıt", LocalDateTime.of(2026, 8, 14, 12, 0));
    }

    private LlmCallLog log(String callType, LocalDateTime at) {
        return LlmCallLog.builder()
                .model("llama3.1").callType(callType).promptSummary("özet")
                .promptChars(100).responseChars(50).durationMs(200L)
                .success(true).calledAt(at).build();
    }

    @Nested
    @DisplayName("Kayıt ve okuma")
    class RecordAndRead {

        @Test
        @DisplayName("Kaydedilen çağrı listede görünür ve DB'ye yazılır")
        void recordedCallIsVisibleAndPersisted() {
            store.record(report("KARATE", true, 250));

            assertEquals(1, store.all().size());
            assertEquals("KARATE", store.all().get(0).callType());
            verify(repository).save(any(LlmCallLog.class));
        }

        @Test
        @DisplayName("byType tip filtresini büyük/küçük harf duyarsız uygular")
        void byTypeIsCaseInsensitive() {
            store.record(report("KARATE", true, 100));
            store.record(report("AGENT", true, 100));

            assertEquals(1, store.byType("karate").size());
            assertEquals(1, store.byType("KARATE").size());
            assertEquals(0, store.byType("SELENIUM").size());
        }

        /**
         * Döndürülen liste değiştirilebilir olursa çağıran taraf depoyu farkında
         * olmadan bozabilir; okuma yolu salt-okunur olmalı.
         */
        @Test
        @DisplayName("all() salt okunur liste döner")
        void allReturnsUnmodifiableList() {
            store.record(report("KARATE", true, 100));

            assertThrows(UnsupportedOperationException.class,
                    () -> store.all().add(report("X", true, 1)));
        }

        /**
         * Sınırsız büyüyen in-memory liste, uzun süre çalışan kurulumda belleği tüketir.
         * 500'lük tavan uygulanmalı ve EN ESKİ kayıt düşmeli.
         */
        @Test
        @DisplayName("500 kayıt tavanı uygulanır, en eski kayıtlar düşer")
        void enforcesMaxSizeDroppingOldest() {
            IntStream.rangeClosed(1, 600).forEach(i ->
                    store.record(new LlmCallReport("llama3.1", "TYPE-" + i, "özet",
                            10, 5, 1L, true, null, null, LocalDateTime.now())));

            assertEquals(500, store.all().size());
            // En yeni kayıt korunmalı, en eski düşmüş olmalı
            assertEquals("TYPE-600", store.all().get(499).callType());
            assertTrue(store.byType("TYPE-1").isEmpty(), "En eski kayıt düşmeliydi");
        }
    }

    @Nested
    @DisplayName("Özet istatistikler")
    class Summary {

        @Test
        @DisplayName("Toplam, başarılı ve başarısız çağrı sayıları doğru toplanır")
        void countsSuccessAndFailure() {
            store.record(report("KARATE", true, 100));
            store.record(report("KARATE", true, 300));
            store.record(report("AGENT", false, 200));

            var summary = store.summary();

            assertEquals(3, summary.totalCalls());
            assertEquals(2, summary.successCalls());
            assertEquals(1, summary.failedCalls());
        }

        @Test
        @DisplayName("Ortalama süre hesaplanır")
        void computesAverageDuration() {
            store.record(report("KARATE", true, 100));
            store.record(report("KARATE", true, 300));

            assertEquals(200, store.summary().avgDurationMs());
        }

        @Test
        @DisplayName("Hiç çağrı yokken sıfıra bölme hatası olmaz")
        void emptyStoreDoesNotDivideByZero() {
            var summary = store.summary();

            assertEquals(0, summary.totalCalls());
            assertEquals(0, summary.avgDurationMs());
        }

        @Test
        @DisplayName("Tahmini token toplamları raporlanır")
        void aggregatesEstimatedTokens() {
            store.record(report("KARATE", true, 100));

            assertTrue(store.summary().totalPromptTokens() > 0);
            assertTrue(store.summary().totalResponseTokens() > 0);
        }
    }

    @Nested
    @DisplayName("Açılışta DB'den geri yükleme")
    class RestoreFromDatabase {

        /**
         * Bu testin kilitlediği hata: kayıtlar DB'ye yazılıyor ama okunmuyordu; her
         * yeniden başlatmada geçmiş "yok" görünüyordu.
         */
        @Test
        @DisplayName("Açılışta geçmiş DB'den belleğe yüklenir")
        void loadsHistoryOnStartup() {
            when(repository.findRecent(any(Pageable.class))).thenReturn(List.of(
                    log("KARATE", LocalDateTime.of(2026, 8, 14, 12, 0))));
            var freshStore = new LlmReportStore(repository, new TestGenMetrics(new SimpleMeterRegistry()));

            freshStore.restoreFromDatabase();

            assertEquals(1, freshStore.all().size());
            assertEquals("KARATE", freshStore.all().get(0).callType());
        }

        /**
         * Sorgu en yeniden eskiye döner ama bellekte kronolojik sıra beklenir; sıra
         * ters kalırsa arayüzde geçmiş baş aşağı görünür.
         */
        @Test
        @DisplayName("DB'den gelen ters sıra kronolojik hâle çevrilir")
        void reversesQueryOrderToChronological() {
            when(repository.findRecent(any(Pageable.class))).thenReturn(List.of(
                    log("YENI", LocalDateTime.of(2026, 8, 14, 15, 0)),
                    log("ESKI", LocalDateTime.of(2026, 8, 14, 9, 0))));
            var freshStore = new LlmReportStore(repository, new TestGenMetrics(new SimpleMeterRegistry()));

            freshStore.restoreFromDatabase();

            assertEquals("ESKI", freshStore.all().get(0).callType(), "En eski kayıt başta olmalı");
            assertEquals("YENI", freshStore.all().get(1).callType());
        }

        /**
         * rawResponse boyut nedeniyle tabloda tutulmuyor. Geri yüklenen kayıtlarda
         * null kalması BEKLENEN davranış — sözleşme olarak kilitleniyor.
         */
        @Test
        @DisplayName("Geri yüklenen kayıtlarda ham yanıt null kalır (tabloda tutulmuyor)")
        void restoredReportsHaveNoRawResponse() {
            when(repository.findRecent(any(Pageable.class))).thenReturn(List.of(
                    log("KARATE", LocalDateTime.of(2026, 8, 14, 12, 0))));
            var freshStore = new LlmReportStore(repository, new TestGenMetrics(new SimpleMeterRegistry()));

            freshStore.restoreFromDatabase();

            assertNull(freshStore.all().get(0).rawResponse());
        }

        @Test
        @DisplayName("DB erişilemezse açılış devam eder, uygulama ayağa kalkar")
        void databaseFailureDoesNotBlockStartup() {
            when(repository.findRecent(any(Pageable.class)))
                    .thenThrow(new RuntimeException("DB kapalı"));
            var freshStore = new LlmReportStore(repository, new TestGenMetrics(new SimpleMeterRegistry()));

            assertDoesNotThrow(freshStore::restoreFromDatabase);
            assertTrue(freshStore.all().isEmpty());
        }
    }

    @Nested
    @DisplayName("Kalıcılık dayanıklılığı")
    class PersistenceResilience {

        /**
         * DB yazımı best-effort. Log kaydedilemedi diye LLM akışının çökmesi,
         * çözdüğünden çok daha büyük bir sorun yaratır.
         */
        @Test
        @DisplayName("DB yazımı patlasa da çağrı bellekte tutulur ve akış sürer")
        void persistFailureDoesNotBreakRecording() {
            doThrow(new RuntimeException("DB kapalı")).when(repository).save(any(LlmCallLog.class));

            assertDoesNotThrow(() -> store.record(report("KARATE", true, 100)));
            assertEquals(1, store.all().size(), "Kayıt bellekte tutulmalıydı");
        }

        @Test
        @DisplayName("Başarısız çağrının hata mesajı korunur")
        void failedCallKeepsErrorMessage() {
            store.record(report("AGENT", false, 100));

            assertEquals("hata", store.all().get(0).errorMessage());
        }
    }
}
