package com.testgen.repository;

import com.testgen.model.FrontendFlowLearning;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FrontendFlowLearningRepository extends JpaRepository<FrontendFlowLearning, String> {

    List<FrontendFlowLearning> findTop3ByServiceKeyAndRequestIdNotOrderByCreatedAtDesc(
            String serviceKey, String requestId);

    boolean existsByRequestIdAndObservedFlow(String requestId, String observedFlow);
}
