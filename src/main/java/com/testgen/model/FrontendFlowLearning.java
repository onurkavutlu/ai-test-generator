package com.testgen.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Bir frontend üretim isteği sırasında gerçekten yürünmüş, yan etkisiz UI akışının
 * kanıtı. {@code request_id} ile kaynak isteğe; {@code serviceKey} ile sonraki aynı
 * site üretimlerine bağlanır.
 */
@Entity
@Table(name = "frontend_flow_learnings", indexes = {
        @Index(name = "idx_flow_learning_service", columnList = "serviceKey"),
        @Index(name = "idx_flow_learning_request", columnList = "request_id"),
        @Index(name = "idx_flow_learning_created", columnList = "createdAt")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FrontendFlowLearning {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private TestGenerationRequest request;

    /** Şema + host + port: aynı origin için tekrar kullanılabilir kanıt havuzu. */
    @Column(nullable = false, length = 512)
    private String serviceKey;

    /** Kullanıcının akış isteği — yalnız izlenebilirlik için, sır olarak kullanılmaz. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String userIntent;

    /** OBSERVED USER FLOW bölümü; yalnız ölçülen locator/URL/görünür gerçekleri içerir. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String observedFlow;

    @Column(updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
