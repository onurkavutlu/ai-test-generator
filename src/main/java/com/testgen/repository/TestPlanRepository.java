package com.testgen.repository;

import com.testgen.model.TestPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TestPlanRepository extends JpaRepository<TestPlan, String> {

    List<TestPlan> findAllByOrderByCreatedAtDesc();
}
