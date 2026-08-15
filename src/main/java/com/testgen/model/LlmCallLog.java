package com.testgen.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Her LLM çağrısının kalıcı kaydı.
 * In-memory LlmReportStore'un DB karşılığı — restart sonrası da izlenebilirlik sağlar.
 */
@Entity
@Table(name = "llm_call_logs", indexes = {
        @Index(name = "idx_llm_call_type", columnList = "callType"),
        @Index(name = "idx_llm_called_at", columnList = "calledAt"),
        // Bir isteğin tüm çağrılarını ve toplam maliyetini çekmek için
        @Index(name = "idx_llm_request_id", columnList = "requestId")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class LlmCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String model;

    /** KARATE | SELENIUM | FAILURE_ANALYSIS | AGENT_<ROLE> | GENERIC */
    @Column(nullable = false, length = 64)
    private String callType;

    /** Prompt'un ilk 200 karakteri — hızlı tarama için. */
    @Column(columnDefinition = "TEXT")
    private String promptSummary;

    private int promptChars;
    private int responseChars;
    private long durationMs;
    private boolean success;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * Çağrının ait olduğu üretim isteği. Bilinmiyorsa null bırakılır — yanlış bir
     * korelasyon, korelasyon olmamasından kötüdür.
     */
    @Column(length = 40)
    private String requestId;

    /** GENERATION | VALIDATION_REPAIR | SELF_HEAL | BENCHMARK | RUNNER */
    @Column(length = 24)
    private String phase;

    @Column(updatable = false)
    @Builder.Default
    private LocalDateTime calledAt = LocalDateTime.now();
}
