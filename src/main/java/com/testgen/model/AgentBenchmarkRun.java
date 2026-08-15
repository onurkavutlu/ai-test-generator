package com.testgen.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Ajan ölçüm koşumu — çok-ajanlı analizin üretim kalitesine ve maliyetine etkisini ölçer.
 *
 * Aynı girdi iki kolda da üretilir ({@link BenchmarkArm}); tek değişken ajan adımının
 * koşulup koşulmadığıdır. Böylece "ajanlar işe yarıyor mu" sorusu tahminle değil
 * ölçümle yanıtlanır.
 */
@Entity
@Table(name = "agent_benchmark_runs")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AgentBenchmarkRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, length = 150)
    private String name;

    // ── Ölçülecek senaryo (iki kolda da AYNI) ────────────────
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TestType testType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TestFramework framework;

    @Column(columnDefinition = "TEXT")
    private String userStory;

    @Column(columnDefinition = "TEXT")
    private String additionalContext;

    @Column(columnDefinition = "TEXT")
    private String swaggerUrl;

    @Column(columnDefinition = "TEXT")
    private String applicationUrl;

    /** Hangi değişken ölçülüyor: ajan açık/kapalı mı, yoksa dar/geniş mi? */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private BenchmarkComparison comparison = BenchmarkComparison.AGENTS_ON_OFF;

    /** Kol başına tekrar sayısı — LLM değişkenliğini bir miktar dengeler. */
    @Builder.Default
    private int repetitions = 1;

    /** Üretilen testler ayrıca koşulsun mu? (yavaş ama en güçlü sinyal) */
    @Builder.Default
    private boolean runTests = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private BenchmarkStatus status = BenchmarkStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<AgentBenchmarkResult> results = new ArrayList<>();

    @Column(updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
