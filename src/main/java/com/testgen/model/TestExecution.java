package com.testgen.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Test Execution — tek bir koşumun kalıcı kaydı (Jira/Xray'deki Test Execution karşılığı).
 *
 * Her koşum kendi kaydını üretir; case sonuçları {@link TestExecutionResult} olarak
 * ANLIK GÖRÜNTÜ şeklinde saklanır. Böylece case'in güncel durumu sonradan değişse bile
 * geçmiş koşumların sonucu bozulmaz ve aynı kapsam istenildiği zaman tekrar koşulabilir.
 */
@Entity
@Table(name = "test_executions")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TestExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, length = 200)
    private String name;

    /** Koşum bir plandan başlatıldıysa doldurulur. */
    @Column(length = 40)
    private String planId;

    @Column(length = 150)
    private String planName;

    /** Koşum tek bir suite'ten başlatıldıysa doldurulur. */
    @Column(length = 40)
    private String suiteId;

    @Column(length = 120)
    private String suiteName;

    /** RERUN ise kaynak koşumun kimliği — koşum zinciri izlenebilir. */
    @Column(length = 40)
    private String sourceExecutionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ExecutionStatus status = ExecutionStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExecutionTrigger trigger;

    @Builder.Default
    private int totalCases = 0;
    @Builder.Default
    private int passedCases = 0;
    @Builder.Default
    private int failedCases = 0;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;

    @OneToMany(mappedBy = "execution", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @Builder.Default
    private List<TestExecutionResult> results = new ArrayList<>();

    @Column(updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /** Koşum tamamlandı mı (yeniden koşum ve raporlama kararları için). */
    public boolean isFinished() {
        return status == ExecutionStatus.PASSED
                || status == ExecutionStatus.FAILED
                || status == ExecutionStatus.ABORTED;
    }

    public double getPassRate() {
        return totalCases == 0 ? 0.0 : (passedCases * 100.0) / totalCases;
    }
}
