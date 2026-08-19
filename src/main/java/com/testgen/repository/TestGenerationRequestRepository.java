package com.testgen.repository;

import com.testgen.model.TestGenerationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TestGenerationRequestRepository
        extends JpaRepository<TestGenerationRequest, String> {

    /** Günlük schedule'a dahil tüm aktif request'ler */
    @Query("""
        SELECT r FROM TestGenerationRequest r
        WHERE r.scheduledRun = true
          AND r.status = com.testgen.model.RequestStatus.GENERATED
        ORDER BY r.createdAt ASC
    """)
    List<TestGenerationRequest> findAllScheduled();

    /** Schedule'a dahil VE autoGenerateOnFailure açık olanlar */
    @Query("""
        SELECT r FROM TestGenerationRequest r
        WHERE r.scheduledRun = true
          AND r.autoGenerateOnFailure = true
          AND r.status = com.testgen.model.RequestStatus.GENERATED
    """)
    List<TestGenerationRequest> findScheduledWithAutoGenerate();

    /**
     * Saklama süresi dolmuş, üzerinde süren iş olmayan isteklerin kimlikleri.
     *
     * <p>Yalnız kimlik seçilir: temizlik kararı için gövde alanlarına (10 KB'a varan
     * {@code additionalContext} / {@code observedBody}) ihtiyaç yoktur ve bunları
     * yüklemek binlerce kayıtta belleği gereksiz doldurur.
     *
     * <p>PENDING / GENERATING / RUNNING dışarıda bırakılır: bu durumlar süren bir asenkron
     * işi gösterir ve altından kaydı çekmek o işi teşhis edilemez biçimde düşürür.
     *
     * <p>{@code COALESCE}: göç öncesi yazılmış satırların {@code tenantId} alanı boş
     * olabilir. Bunları varsayılan kiracıya saymak, {@code TenantScope} ile aynı kuraldır —
     * aksi hâlde eski veri hiçbir kiracının temizliğine girmez ve sonsuza dek birikir.
     */
    @Query("""
        SELECT r.id FROM TestGenerationRequest r
        WHERE r.createdAt < :cutoff
          AND r.status NOT IN (com.testgen.model.RequestStatus.PENDING,
                               com.testgen.model.RequestStatus.GENERATING,
                               com.testgen.model.RequestStatus.RUNNING)
          AND COALESCE(NULLIF(r.tenantId, ''), :defaultTenant) = :tenantId
        ORDER BY r.createdAt ASC
    """)
    List<String> findPurgeableIds(@Param("cutoff") LocalDateTime cutoff,
                                  @Param("tenantId") String tenantId,
                                  @Param("defaultTenant") String defaultTenant);

    /**
     * Toplu silme. Entity yüklemeden çalışır; bu yüzden JPA cascade'i tetiklenmez ve
     * çağıran, bağlı test case'leri ÖNCE silmekle yükümlüdür.
     */
    @Modifying
    @Query("DELETE FROM TestGenerationRequest r WHERE r.id IN :ids")
    int deleteByIdIn(@Param("ids") List<String> ids);
}
