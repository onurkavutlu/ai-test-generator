package com.testgen.service;

import com.testgen.config.BadRequestException;
import com.testgen.repository.GeneratedTestCaseRepository;
import com.testgen.repository.TestGenerationRequestRepository;
import com.testgen.repository.TestSuiteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Saklama temizliğinin davranış sözleşmesi.
 *
 * <p>Bu testlerin kilitlediği şey silme SAYISI değil, <b>silmeme</b> garantileridir:
 * önizlemenin gerçekten hiçbir şey silmemesi, suite'e bağlı isteğe dokunulmaması,
 * sıfır/negatif gün eşiğinin reddedilmesi ve çocuk kayıtların ebeveynden ÖNCE
 * silinmesi. Bunların her biri, kırıldığında geri alınamaz veri kaybı üretir.
 */
@ExtendWith(MockitoExtension.class)
class DataRetentionServiceTest {

    @Mock
    private TestGenerationRequestRepository requestRepository;

    @Mock
    private GeneratedTestCaseRepository testCaseRepository;

    @Mock
    private TestSuiteRepository suiteRepository;

    @InjectMocks
    private DataRetentionService service;

    private void candidates(String... ids) {
        when(requestRepository.findPurgeableIds(any(LocalDateTime.class), anyString(), anyString()))
                .thenReturn(List.of(ids));
    }

    @Test
    @DisplayName("Sıfır gün reddedilir — 'her şeyi sil' bir parametre kazası olamaz")
    void zeroDaysRejected() {
        assertThrows(BadRequestException.class, () -> service.purge(0, false));
        verify(requestRepository, never()).deleteByIdIn(anyList());
    }

    @Test
    @DisplayName("Negatif gün reddedilir — gelecekteki bir eşik tüm veriyi kapsardı")
    void negativeDaysRejected() {
        assertThrows(BadRequestException.class, () -> service.purge(-5, false));
        verify(testCaseRepository, never()).deleteByRequestIdIn(anyList());
    }

    @Test
    @DisplayName("Önizleme hiçbir kaydı silmez, yalnız sayar")
    void dryRunDeletesNothing() {
        candidates("r1", "r2");
        when(suiteRepository.findRequestIdsLinkedToSuites(anyList())).thenReturn(List.of());
        when(testCaseRepository.countByRequestIdIn(anyList())).thenReturn(7L);

        DataRetentionResult result = service.purge(30, true);

        assertTrue(result.dryRun());
        assertEquals(2, result.requestCount());
        assertEquals(7, result.testCaseCount());
        verify(requestRepository, never()).deleteByIdIn(anyList());
        verify(testCaseRepository, never()).deleteByRequestIdIn(anyList());
    }

    @Test
    @DisplayName("Suite'e bağlı istek silinmez ve neden korunduğu yanıtta görünür")
    void suiteLinkedRequestIsProtected() {
        candidates("r1", "r2");
        when(suiteRepository.findRequestIdsLinkedToSuites(anyList())).thenReturn(List.of("r2"));
        when(testCaseRepository.countByRequestIdIn(anyList())).thenReturn(3L);
        when(testCaseRepository.deleteByRequestIdIn(anyList())).thenReturn(3);
        when(requestRepository.deleteByIdIn(anyList())).thenReturn(1);

        DataRetentionResult result = service.purge(30, false);

        assertEquals(1, result.protectedRequestCount());
        assertEquals(List.of("r2"), result.protectedRequestIds());

        ArgumentCaptor<List<String>> deleted = ArgumentCaptor.forClass(List.class);
        verify(requestRepository).deleteByIdIn(deleted.capture());
        assertEquals(List.of("r1"), deleted.getValue());
    }

    @Test
    @DisplayName("Adayların tamamı korunuyorsa hiç silme çalışmaz")
    void allCandidatesProtected() {
        candidates("r1");
        when(suiteRepository.findRequestIdsLinkedToSuites(anyList())).thenReturn(List.of("r1"));

        DataRetentionResult result = service.purge(30, false);

        assertEquals(0, result.requestCount());
        assertEquals(0, result.testCaseCount());
        assertEquals(1, result.protectedRequestCount());
        verify(requestRepository, never()).deleteByIdIn(anyList());
        verify(testCaseRepository, never()).deleteByRequestIdIn(anyList());
    }

    @Test
    @DisplayName("Case'ler istekten ÖNCE silinir — ters sıra yabancı anahtar ihlali verir")
    void childrenDeletedBeforeParents() {
        candidates("r1");
        when(suiteRepository.findRequestIdsLinkedToSuites(anyList())).thenReturn(List.of());
        when(testCaseRepository.countByRequestIdIn(anyList())).thenReturn(4L);
        when(testCaseRepository.deleteByRequestIdIn(anyList())).thenReturn(4);
        when(requestRepository.deleteByIdIn(anyList())).thenReturn(1);

        DataRetentionResult result = service.purge(30, false);

        assertFalse(result.dryRun());
        assertEquals(1, result.requestCount());
        assertEquals(4, result.testCaseCount());

        var order = org.mockito.Mockito.inOrder(testCaseRepository, requestRepository);
        order.verify(testCaseRepository).deleteByRequestIdIn(List.of("r1"));
        order.verify(requestRepository).deleteByIdIn(List.of("r1"));
    }

    @Test
    @DisplayName("Aday yoksa hiçbir sorgu daha çalışmaz — boş temizlik ücretsizdir")
    void noCandidatesShortCircuits() {
        when(requestRepository.findPurgeableIds(any(LocalDateTime.class), anyString(), anyString()))
                .thenReturn(List.of());

        DataRetentionResult result = service.purge(30, false);

        assertEquals(0, result.requestCount());
        verifyNoMoreInteractions(suiteRepository);
        verify(testCaseRepository, never()).countByRequestIdIn(anyList());
    }

    @Test
    @DisplayName("Kesim tarihi verilen gün kadar geçmişe düşer")
    void cutoffReflectsRetentionDays() {
        when(requestRepository.findPurgeableIds(any(LocalDateTime.class), anyString(), anyString()))
                .thenReturn(List.of());

        DataRetentionResult result = service.purge(90, true);

        assertEquals(90, result.retentionDays());
        assertTrue(result.cutoff().isBefore(LocalDateTime.now().minusDays(89)));
        assertTrue(result.cutoff().isAfter(LocalDateTime.now().minusDays(91)));
    }

    @Test
    @DisplayName("500'den çok aday parçalara bölünür — IN listesi bind sınırına çarpmaz")
    void largeCandidateSetIsBatched() {
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            many.add("r" + i);
        }
        when(requestRepository.findPurgeableIds(any(LocalDateTime.class), anyString(), anyString()))
                .thenReturn(many);
        when(suiteRepository.findRequestIdsLinkedToSuites(anyList())).thenReturn(List.of());
        when(testCaseRepository.countByRequestIdIn(anyList())).thenReturn(1L);

        DataRetentionResult result = service.purge(30, true);

        assertEquals(1200, result.requestCount());
        // 1200 kayıt / 500 = 3 parça
        verify(suiteRepository, times(3)).findRequestIdsLinkedToSuites(anyList());
        verify(testCaseRepository, times(3)).countByRequestIdIn(anyList());
    }
}
