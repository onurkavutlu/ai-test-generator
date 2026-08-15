package com.testgen.repository;

import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.model.TestRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GeneratedTestCaseRepository
        extends JpaRepository<GeneratedTestCase, String> {

    List<GeneratedTestCase> findByRequestId(String requestId);

    /**
     * Yakınsama raporu için hafif projeksiyon.
     *
     * NEDEN PROJEKSİYON: Rapor tüm istekleri gezip her biri için ayrı sorgu atıyordu (N+1)
     * ve her seferinde 10 KB'a varan testContent / runOutput sütunlarını da çekiyordu.
     * Rapor bu alanların hiçbirini kullanmıyor; yalnızca sayım ve durum bilgisi gerekiyor.
     */
    @Query("""
            select new com.testgen.repository.CaseOutcomeView(
                tc.request.id, tc.runStatus, tc.totalScenarios, tc.passedScenarios, tc.deterministic)
            from GeneratedTestCase tc
            where tc.superseded = false
            """)
    List<CaseOutcomeView> findOutcomeViews();

    /**
     * Koşulabilir case'ler: self-healing ile yenisi üretilip supersede edilenler hariç.
     * Supersede edilen case yeni versiyonla AYNI dosya adını kullandığı için birlikte
     * koşulduklarında diskteki dosyayı ezer ve sonuçlar tutarsız olur.
     */
    List<GeneratedTestCase> findByRequestIdAndSupersededFalse(String requestId);

    /** Bir request'e ait başarısız test case'ler */
    List<GeneratedTestCase> findByRequestIdAndRunStatus(String requestId, TestRunStatus status);

    /** Bir request'teki (supersede edilmemiş) başarısız test case'ler — LLM analizi için */
    @Query("""
        SELECT tc FROM GeneratedTestCase tc
        WHERE tc.request.id = :requestId
          AND tc.runStatus = com.testgen.model.TestRunStatus.FAILED
          AND tc.superseded = false
        ORDER BY tc.lastRunAt DESC
    """)
    List<GeneratedTestCase> findFailedByRequestId(@Param("requestId") String requestId);

    /** Belirli bir framework'te kaç başarısız var? */
    @Query("""
        SELECT COUNT(tc) FROM GeneratedTestCase tc
        WHERE tc.request.id = :requestId
          AND tc.framework = :framework
          AND tc.runStatus = com.testgen.model.TestRunStatus.FAILED
    """)
    long countFailedByRequestIdAndFramework(
            @Param("requestId") String requestId,
            @Param("framework") TestFramework framework);
}
