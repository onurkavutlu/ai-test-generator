package com.testgen.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Ölçüm koşumunda tek bir üretimin (bir kol × bir tekrar) sonucu.
 *
 * Tüm sayılar ölçülmüştür: doğrulama sonuçları makine (parser/derleyici) çıktısından,
 * LLM maliyeti üretim penceresindeki çağrı kayıtlarından gelir.
 */
@Entity
@Table(name = "agent_benchmark_results")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AgentBenchmarkResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private AgentBenchmarkRun run;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BenchmarkArm arm;

    /** 1..repetitions */
    private int iteration;

    /** Bu ölçümde kullanılan üretim isteğinin kimliği — çıktı incelenebilsin. */
    @Column(length = 40)
    private String requestId;

    // ── Üretim kalitesi (makine doğrulaması) ─────────────────
    private int caseCount;
    private int validCases;
    private int invalidCases;
    private int skippedCases;
    /** Doğrulamayı geçmek için yapılan toplam yeniden üretim denemesi. */
    private int validationRetries;

    // ── Maliyet ──────────────────────────────────────────────
    private int llmCalls;
    private long llmDurationMs;
    private long llmPromptChars;
    private long generationDurationMs;

    // ── Koşum sonucu (yalnızca runTests=true ise doldurulur) ─
    private Integer totalScenarios;
    private Integer passedScenarios;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Builder.Default
    private LocalDateTime recordedAt = LocalDateTime.now();

    /** Üretilen case'lerin kaçı makine doğrulamasından geçti (%). */
    public double getValidRate() {
        return caseCount == 0 ? 0.0 : (validCases * 100.0) / caseCount;
    }
}
