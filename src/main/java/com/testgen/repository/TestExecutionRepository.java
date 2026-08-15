package com.testgen.repository;

import com.testgen.model.TestExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TestExecutionRepository extends JpaRepository<TestExecution, String> {

    List<TestExecution> findAllByOrderByCreatedAtDesc();

    List<TestExecution> findByPlanIdOrderByCreatedAtDesc(String planId);

    List<TestExecution> findBySuiteIdOrderByCreatedAtDesc(String suiteId);
}
