package com.testgen.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "generated_test_cases")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class GeneratedTestCase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id", nullable = false)
    private TestGenerationRequest request;

    @Column(nullable = false)
    private String testName;

    @Column(nullable = false)
    private String fileName;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String testContent;

    @Column(columnDefinition = "TEXT")
    private String testSummary;

    @Enumerated(EnumType.STRING)
    private TestFramework framework;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private TestRunStatus runStatus = TestRunStatus.NOT_RUN;

    @Column(columnDefinition = "TEXT")
    private String runOutput;           // max 10KB (TestRunResult.truncate ile kesilir)

    private Integer totalScenarios;
    private Integer passedScenarios;
    private Integer failedScenarios;
    private Long executionTimeMs;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime lastRunAt;

    // ── ISTQB Metadata ────────────────────────────────────────
    /** Test kategorisi: SMOKE, REGRESSION, SECURITY, PERFORMANCE vb. */
    @Enumerated(EnumType.STRING)
    private TestCategory testCategory;

    /** Test seviyesi: UNIT, INTEGRATION, SYSTEM, ACCEPTANCE */
    @Enumerated(EnumType.STRING)
    private TestLevel testLevel;

    /** Test önceliği: P0_BLOCKER → P3_MINOR */
    @Enumerated(EnumType.STRING)
    private TestPriority testPriority;

    /** Kullanılan ISTQB test tasarım tekniği */
    @Enumerated(EnumType.STRING)
    private TestDesignTechnique testDesignTechnique;

    /** Test ön koşulları (preconditions) */
    @Column(columnDefinition = "TEXT")
    private String preconditions;

    /** Beklenen sonuçlar */
    @Column(columnDefinition = "TEXT")
    private String expectedResults;

    /** İzlenebilirlik referansı: User Story ID, Requirement ID vb. */
    private String traceabilityRef;

    // ── Self-Healing meta ────────────────────────────────────
    /** Kaç kez self-heal denemesi yapıldı (orijinal case için). */
    @Builder.Default
    private int healAttempts = 0;

    /** Bu case, hangi başarısız case'den türetildi (AUTO-FIX ise dolu). */
    private String parentCaseId;

    /** Bu case supersede edildi mi (daha yeni bir _Fixed_ versiyonu var). */
    @Builder.Default
    private boolean superseded = false;

    // ── LLM meta ─────────────────────────────────────────────
    /** LLM üretiminde kullanılan model adı. */
    private String llmModel;

    /** LLM çağrısının süresi (ms). */
    private Long llmDurationMs;

    /** LLM'e gönderilen prompt'un karakter sayısı (token tahmini için). */
    private Integer llmPromptChars;

    /** LLM'den gelen yanıtın karakter sayısı. */
    private Integer llmResponseChars;
}
