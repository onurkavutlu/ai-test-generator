package com.testgen.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Test Plan — kapsam/sürüm bazlı en üst seviye kapsayıcı (Jira/Xray'deki Test Plan karşılığı).
 *
 * Hiyerarşi:  Test Plan → Test Suite → Test Case
 * Bir plan birden fazla suite içerir; bir suite birden fazla planda yer alabilir.
 * Plan koşturulduğunda kapsamındaki tüm suite'lerin case'leri tek bir
 * {@link TestExecution} altında koşulur ve sonuç geçmişi kalıcı olarak saklanır.
 */
@Entity
@Table(name = "test_plans")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TestPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Serbest metin sürüm/sprint etiketi (örn. "R2025.4", "Sprint-18"). */
    @Column(length = 60)
    private String version;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "plan_test_suites",
            joinColumns = @JoinColumn(name = "plan_id"),
            inverseJoinColumns = @JoinColumn(name = "suite_id"))
    @Builder.Default
    private List<TestSuite> suites = new ArrayList<>();

    /** Son koşumun özeti (liste görünümünde sorgu yapmadan gösterebilmek için). */
    private LocalDateTime lastExecutedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ExecutionStatus lastExecutionStatus;

    private Integer lastExecutionPassed;
    private Integer lastExecutionFailed;

    @Column(updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
