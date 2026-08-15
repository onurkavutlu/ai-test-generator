package com.testgen.repository;

import com.testgen.model.AgentBenchmarkRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentBenchmarkRunRepository extends JpaRepository<AgentBenchmarkRun, String> {

    List<AgentBenchmarkRun> findAllByOrderByCreatedAtDesc();
}
