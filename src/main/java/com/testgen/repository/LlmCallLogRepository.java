package com.testgen.repository;

import com.testgen.model.LlmCallLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LlmCallLogRepository extends JpaRepository<LlmCallLog, String> {

    List<LlmCallLog> findTop100ByOrderByCalledAtDesc();

    List<LlmCallLog> findByCallTypeOrderByCalledAtDesc(String callType);

    long countBySuccess(boolean success);
}
