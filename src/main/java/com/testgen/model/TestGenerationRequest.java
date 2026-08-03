package com.testgen.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

// ─────────────────────────────────────────────────────────
// TestGenerationRequest – Kullanıcıdan gelen istek
// ─────────────────────────────────────────────────────────
@Entity
@Table(name = "test_generation_requests")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TestGenerationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TestType testType;           // BACKEND_API | FRONTEND_WEB

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TestFramework framework;     // KARATE | SELENIUM

    @Column(columnDefinition = "TEXT")
    private String userStory;           // "Kullanıcı login olabilmeli"

    @Column(columnDefinition = "TEXT")
    private String swaggerUrl;          // BE için swagger/openapi url

    @Column(columnDefinition = "TEXT")
    private String applicationUrl;      // FE için base url

    @Column(columnDefinition = "TEXT")
    private String additionalContext;   // Ekstra bilgi

    @Column(columnDefinition = "TEXT")
    private String rawPayload;          // cURL, JSON veya XML payload verisi

    @Column(columnDefinition = "VARCHAR(20)")
    private String payloadType;         // CURL, JSON, XML vs.

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private RequestStatus status = RequestStatus.PENDING;

    // ── Zamanlayıcı (Scheduler) alanları ──────────────────────
    /** true ise bu request günlük schedule'a dahil edilir */
    @Builder.Default
    private boolean scheduledRun = false;

    /** Bu request'in son schedule koşum zamanı */
    private LocalDateTime lastScheduledRunAt;

    /** Kaç kez schedule ile koşuldu */
    @Builder.Default
    private int scheduledRunCount = 0;

    /** Toplam kaç başarısız koşum oldu (trend takibi için) */
    @Builder.Default
    private int totalFailureCount = 0;

    /** LLM bu request için yeni test üretsin mi? (failure analizi sonrası) */
    @Builder.Default
    private boolean autoGenerateOnFailure = true;

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<GeneratedTestCase> generatedTestCases;

    @Column(updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    @PreUpdate
    public void onUpdate() { this.updatedAt = LocalDateTime.now(); }
}
