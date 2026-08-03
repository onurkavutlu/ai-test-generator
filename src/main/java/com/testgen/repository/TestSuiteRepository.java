package com.testgen.repository;

import com.testgen.model.TestSuite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestSuiteRepository extends JpaRepository<TestSuite, String> {

    List<TestSuite> findAllByOrderByCreatedAtDesc();
}
