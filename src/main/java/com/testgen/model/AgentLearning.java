package com.testgen.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Servis bazlı öğrenim kaydı — halüsinasyon azaltma mekanizmasının temeli.
 *
 * Koşum hataları ve self-healing düzeltmelerinden çıkarılan dersler serviceKey
 * (hedef servisin host'u) altında birikir; aynı servise yönelik sonraki test
 * üretimlerinde prompt'a enjekte edilir. Böylece LLM aynı hatalı varsayımı
 * (yanlış status kodu, olmayan alan, yanlış selector) tekrarlamaz.
 */
@Entity
@Table(name = "agent_learnings", indexes = {
        @Index(name = "idx_learning_service", columnList = "serviceKey"),
        @Index(name = "idx_learning_created", columnList = "createdAt")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AgentLearning {

    public enum Source { RUN_FAILURE, SELF_HEAL, RUN_SUCCESS }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Hedef servisin kimliği — swaggerUrl/applicationUrl host'u, yoksa "global". */
    @Column(nullable = false, length = 255)
    private String serviceKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Source source;

    /** Prompt'a enjekte edilecek ders metni — kısa ve aksiyon odaklı. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String lesson;

    /** Dersin çıkarıldığı test adı (izlenebilirlik). */
    private String originTestName;

    @Column(updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
