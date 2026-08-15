package com.testgen.repository;

import com.testgen.model.AgentBenchmarkResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentBenchmarkResultRepository extends JpaRepository<AgentBenchmarkResult, String> {

    List<AgentBenchmarkResult> findByRunIdOrderByIterationAsc(String runId);
}
