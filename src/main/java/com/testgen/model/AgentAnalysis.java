package com.testgen.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Bir test isteği için tek bir ajanın ürettiği analiz çıktısı.
 * Daha önce yalnızca additionalContext string'ine gömülüydü — artık sorgulanabilir.
 */
@Entity
@Table(name = "agent_analyses", indexes = {
        @Index(name = "idx_agent_request", columnList = "requestId"),
        @Index(name = "idx_agent_role", columnList = "role")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AgentAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String requestId;

    @Column(nullable = false, length = 64)
    private String role;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String output;

    @Column(updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
