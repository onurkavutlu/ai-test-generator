package com.testgen.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Endpoint Comparer koşumlarının kalıcı geçmişi.
 * resultJson: ComparisonResultDto'nun tam JSON hali (UI'da geçmiş görüntüleme için).
 */
@Entity
@Table(name = "comparison_runs", indexes = {
        @Index(name = "idx_comparison_executed_at", columnList = "executedAt")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ComparisonRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String baseUrlA;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String baseUrlB;

    private int totalRequests;
    private int identicalCount;
    private int differentCount;
    private int errorCount;
    private long totalDurationMs;

    @Column(columnDefinition = "TEXT")
    private String resultJson;

    @Column(updatable = false)
    @Builder.Default
    private LocalDateTime executedAt = LocalDateTime.now();
}
