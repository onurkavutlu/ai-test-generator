package com.testgen.repository;

import com.testgen.model.AgentAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentAnalysisRepository extends JpaRepository<AgentAnalysis, String> {

    List<AgentAnalysis> findByRequestIdOrderByCreatedAtAsc(String requestId);
}
