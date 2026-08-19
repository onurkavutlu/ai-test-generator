package com.testgen.service;

import com.testgen.config.BadRequestException;
import com.testgen.repository.GeneratedTestCaseRepository;
import com.testgen.repository.TestGenerationRequestRepository;
import com.testgen.repository.TestSuiteRepository;
import com.testgen.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Saklama süresi dolmuş üretim isteklerini ve onlara bağlı test case'leri temizler.
 *
 * <p><b>Neden yaş eşiği zorunlu ve tabansız silme yok:</b> "hepsini sil" davranışı,
 * yanlışlıkla tetiklendiğinde geri alınamaz. Uç yalnız bir <i>yaş</i> alır; en yeni
 * veriye hiçbir parametre kombinasyonuyla dokunulamaz.
 *
 * <p><b>Neden varsayılan önizleme (dryRun):</b> silme geri alınamaz olduğu için
 * varsayılanın <i>zararsız</i> olması gerekir. Swagger'dan parametresiz tetikleyen bir
 * kullanıcı yalnızca sayıyı görür; gerçek silme için {@code dryRun=false} vermek
 * bilinçli bir eylemdir.
 *
 * <p><b>Neden suite'e bağlı istekler korunur:</b> {@code suite_test_cases} bağlantı
 * tablosu case'lere yabancı anahtarla bağlıdır. Bağlı bir case silinseydi ya kısıt
 * ihlali alınır ya da kullanıcının elle kurduğu suite sessizce eksilirdi. Yaşı dolmuş
 * ama bir suite'e girmiş istek, "kullanımda" sayılır ve atlanır — sayısı yanıtta
 * raporlanır ki neden silinmediği görünür olsun.
 *
 * <p><b>Neden PENDING/GENERATING/RUNNING atlanır:</b> bu durumlar süren bir asenkron iş
 * demektir. Altından kaydı çekmek, çalışan üretimi teşhis edilemez bir hatayla düşürür.
 *
 * <p><b>Neden toplu (bulk) JPQL silme:</b> entity'leri yükleyip silmek, her istek için
 * 10 KB'a varan {@code testContent} / {@code runOutput} sütunlarını belleğe çeker.
 * Toplu silme bunları hiç okumaz. Bedeli, JPA cascade'inin çalışmamasıdır; bu yüzden
 * çocuk kayıtlar (case'ler) <b>önce ve açıkça</b> silinir.
 *
 * <p><b>Bilinen sınır:</b> {@code agent_analyses} ve {@code llm_call_logs} tabloları
 * isteğe yabancı anahtarla değil düz {@code requestId} metniyle bağlıdır ve bu temizliğin
 * kapsamı dışındadır; istek silindikten sonra o satırlar sahipsiz kalır. Kapsama
 * alınmaları ayrı bir karardır — denetim/maliyet raporları bu satırları okur.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataRetentionService {

    /** En düşük saklama süresi. 0 gün "her şeyi sil" demektir ve bilerek yasaktır. */
    static final int MIN_RETENTION_DAYS = 1;

    /** Yanıt gövdesini şişirmemek için raporlanan korunan kimlik sayısı üst sınırı. */
    static final int MAX_REPORTED_PROTECTED_IDS = 50;

    /**
     * {@code IN (...)} listelerinin parça boyu. Sınırsız bir IN listesi, veritabanının
     * bind parametre sınırına (PostgreSQL'de 65535) çarpar ve temizlik tam da en çok
     * gerektiği anda — birikmiş veri varken — patlar.
     */
    private static final int BATCH_SIZE = 500;

    private final TestGenerationRequestRepository requestRepository;
    private final GeneratedTestCaseRepository testCaseRepository;
    private final TestSuiteRepository suiteRepository;

    /**
     * Yaşı dolmuş test datasını siler ya da (dryRun) yalnız sayar.
     *
     * @param retentionDays kaç günden eski kayıtlar temizlenecek; en az {@value #MIN_RETENTION_DAYS}
     * @param dryRun        true ise hiçbir şey silinmez, yalnız etkilenecek sayılar döner
     */
    @Transactional
    public DataRetentionResult purge(int retentionDays, boolean dryRun) {
        if (retentionDays < MIN_RETENTION_DAYS) {
            throw new BadRequestException(
                    "olderThanDays en az " + MIN_RETENTION_DAYS + " olmalıdır; verilen: " + retentionDays
                            + ". Sıfır veya negatif değer tüm veriyi silmek anlamına gelirdi.");
        }

        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        String tenant = TenantContext.currentTenant();

        List<String> candidates = requestRepository.findPurgeableIds(
                cutoff, tenant, TenantContext.DEFAULT_TENANT);
        if (candidates.isEmpty()) {
            return new DataRetentionResult(dryRun, retentionDays, cutoff, 0, 0, 0, List.of());
        }

        Set<String> protectedIds = new LinkedHashSet<>();
        for (List<String> batch : batches(candidates)) {
            protectedIds.addAll(suiteRepository.findRequestIdsLinkedToSuites(batch));
        }

        List<String> deletable = candidates.stream()
                .filter(id -> !protectedIds.contains(id))
                .toList();
        if (deletable.isEmpty()) {
            return new DataRetentionResult(dryRun, retentionDays, cutoff, 0, 0,
                    protectedIds.size(), reportable(protectedIds));
        }

        long caseCount = 0;
        for (List<String> batch : batches(deletable)) {
            caseCount += testCaseRepository.countByRequestIdIn(batch);
        }

        if (dryRun) {
            log.info("Veri saklama önizlemesi: {} gün öncesi, {} istek / {} case silinecek, {} istek suite'e bağlı olduğu için korunuyor",
                    retentionDays, deletable.size(), caseCount, protectedIds.size());
            return new DataRetentionResult(true, retentionDays, cutoff,
                    deletable.size(), caseCount, protectedIds.size(), reportable(protectedIds));
        }

        long deletedCases = 0;
        long deletedRequests = 0;
        for (List<String> batch : batches(deletable)) {
            // Sıra önemli: case'ler isteğe yabancı anahtarla bağlı. Önce istek silinirse
            // kısıt ihlali alınır ve işlem geri sarılır.
            deletedCases += testCaseRepository.deleteByRequestIdIn(batch);
            deletedRequests += requestRepository.deleteByIdIn(batch);
        }

        log.warn("Veri saklama temizliği: {} tarihinden eski {} istek ve {} test case silindi (kiracı: {})",
                cutoff, deletedRequests, deletedCases, tenant);

        return new DataRetentionResult(false, retentionDays, cutoff,
                deletedRequests, deletedCases, protectedIds.size(), reportable(protectedIds));
    }

    private static List<List<String>> batches(List<String> ids) {
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += BATCH_SIZE) {
            out.add(ids.subList(i, Math.min(ids.size(), i + BATCH_SIZE)));
        }
        return out;
    }

    private static List<String> reportable(Set<String> protectedIds) {
        return protectedIds.stream().limit(MAX_REPORTED_PROTECTED_IDS).toList();
    }
}
