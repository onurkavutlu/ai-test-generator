package com.testgen.repository;

import com.testgen.model.AgentLearning;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentLearningRepository extends JpaRepository<AgentLearning, String> {

    List<AgentLearning> findTop10ByServiceKeyOrderByCreatedAtDesc(String serviceKey);

    long countByServiceKey(String serviceKey);
}
