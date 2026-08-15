package com.testgen.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Bir koşum içindeki tek bir test case'in sonuç ANLIK GÖRÜNTÜSÜ.
 *
 * Case'e referans yalnızca id ile tutulur (yabancı anahtar ilişkisi değil): case
 * sonradan silinse veya self-healing ile değişse bile geçmiş koşum kaydı okunabilir kalır.
 */
@Entity
@Table(name = "test_execution_results")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TestExecutionResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "execution_id", nullable = false)
    private TestExecution execution;

    /** Koşulan case'in kimliği — yeniden koşumda kapsamı çözmek için kullanılır. */
    @Column(nullable = false, length = 40)
    private String testCaseId;

    @Column(nullable = false)
    private String testName;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TestFramework framework;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TestRunStatus runStatus;

    private Integer totalScenarios;
    private Integer passedScenarios;
    private Integer failedScenarios;
    private Long executionTimeMs;

    @Column(columnDefinition = "TEXT")
    private String runOutput;

    @Builder.Default
    private LocalDateTime recordedAt = LocalDateTime.now();
}
