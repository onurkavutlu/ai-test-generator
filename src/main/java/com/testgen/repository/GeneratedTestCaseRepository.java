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

    /** Bir request'e ait başarısız test case'ler */
    List<GeneratedTestCase> findByRequestIdAndRunStatus(String requestId, TestRunStatus status);

    /** Bir request'teki tüm başarısız test case'lerin içeriği — LLM analizi için */
    @Query("""
        SELECT tc FROM GeneratedTestCase tc
        WHERE tc.request.id = :requestId
          AND tc.runStatus = 'FAILED'
        ORDER BY tc.lastRunAt DESC
    """)
    List<GeneratedTestCase> findFailedByRequestId(@Param("requestId") String requestId);

    /** Belirli bir framework'te kaç başarısız var? */
    @Query("""
        SELECT COUNT(tc) FROM GeneratedTestCase tc
        WHERE tc.request.id = :requestId
          AND tc.framework = :framework
          AND tc.runStatus = 'FAILED'
    """)
    long countFailedByRequestIdAndFramework(
            @Param("requestId") String requestId,
            @Param("framework") TestFramework framework);
}
