package com.testgen.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "mock_responses", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"path", "method"})
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MockResponse {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Schema(hidden = true)
    private String id;

    @Schema(description = "İstek atılacak URL path", example = "/pet/10")
    @Column(nullable = false)
    private String path; // e.g. "/pet/10"

    @Schema(description = "HTTP Metodu", example = "GET")
    @Column(nullable = false)
    private String method; // e.g. "GET", "POST"

    @Schema(description = "Geri dönülecek HTTP Status Kodu", example = "200")
    @Column(nullable = false)
    private int statusCode; // e.g. 200, 404

    @Schema(description = "Geri dönülecek JSON Response içeriği", example = "{\"id\": 10, \"name\": \"Mavi\", \"status\": \"available\"}")
    @Column(columnDefinition = "TEXT")
    private String responseBody; // JSON string

    @Schema(hidden = true)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
