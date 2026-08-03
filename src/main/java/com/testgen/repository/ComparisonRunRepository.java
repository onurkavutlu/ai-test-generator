package com.testgen.repository;

import com.testgen.model.ComparisonRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ComparisonRunRepository extends JpaRepository<ComparisonRun, String> {

    List<ComparisonRun> findTop50ByOrderByExecutedAtDesc();
}
