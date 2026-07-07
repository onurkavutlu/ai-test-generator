package com.testgen.repository;

import com.testgen.model.TestGenerationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

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
}
