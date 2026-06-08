package com.testgen.repository;

import com.testgen.model.MockResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MockResponseRepository extends JpaRepository<MockResponse, String> {
    Optional<MockResponse> findByPathAndMethod(String path, String method);
}
