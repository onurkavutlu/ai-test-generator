package com.testgen.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Kullanıcı tanımlı test paketi.
 *
 * Farklı üretim isteklerinden (veya manuel eklenen) test case'ler tek suite
 * altında toplanır ve istenildiği zaman birlikte yeniden koşulur.
 * Case ↔ Suite ilişkisi many-to-many: bir case birden fazla suite'te yer alabilir.
 */
@Entity
@Table(name = "test_suites")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TestSuite {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "suite_test_cases",
            joinColumns = @JoinColumn(name = "suite_id"),
            inverseJoinColumns = @JoinColumn(name = "case_id"))
    @Builder.Default
    private List<GeneratedTestCase> testCases = new ArrayList<>();

    /** Son suite koşumunun özeti (hızlı liste görünümü için). */
    private LocalDateTime lastRunAt;
    private Integer lastRunPassed;
    private Integer lastRunFailed;

    @Column(updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    @PreUpdate
    public void onUpdate() { this.updatedAt = LocalDateTime.now(); }
}
