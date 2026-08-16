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

    /**
     * Üretilecek en fazla case sayısı. Swagger'dan üretimde case sayısı endpoint
     * sayısına eşittir; sınır konmazsa geniş bir API tek istekte onlarca case ve
     * saatlik LLM süresi demektir. null = sınırsız (eski davranış).
     */
    private Integer maxCases;

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

    /**
     * Koşum sonrası başarısız testler için self-healing OTOMATİK tetiklensin mi?
     *
     * VARSAYILAN KAPALI. Ölçülen bir koşumda LLM zamanının ~%50'si otomatik
     * self-healing'e gidiyordu: her başarısız case 2 LLM çağrısı demek ve 45
     * başarısız case, yeni üretimleri 10 dakikadan uzun süre aç bıraktı.
     * İyileştirme artık kullanıcı isteğiyle tetiklenir:
     * {@code POST /api/v1/tests/{requestId}/self-heal}
     *
     * Zamanlanmış (scheduler) koşumlarda otomatik iyileştirme istenirse bu alan
     * request bazında açılabilir.
     */
    @Builder.Default
    private boolean autoGenerateOnFailure = false;

    // NOT: Burada kısa süre `observeMutating` alanı vardı — yan etkili isteklerin
    // gözlemlenmesi için kullanıcı onayı isteniyordu. Kaldırıldı: isteği kullanıcı
    // zaten kendisi veriyor, onay istemek Postman'de Send'e bastıktan sonra "emin
    // misin" diye sormak gibiydi. Onay kuralı isteğin metoduna değil KAYNAĞINA bağlı
    // ve doğru yerde uygulanıyor (Swagger taraması yalnızca yan etkisiz problar).
    //
    // Veritabanındaki observe_mutating sütunu duruyor; Hibernate `update` sütun
    // düşürmez ve düşürmek için yeni bir şema göçü gerekirdi. Sütunun varsayılanı
    // false, kimse yazmıyor, kimse okumuyor — zararsız.

    // ── Gözlem kanıtı ────────────────────────────────────────────────────────
    // Üretilen her assertion'ın dayanağı. Saklanmazsa kullanıcı, testin neye göre
    // yazıldığını göremez; "bu iddia nereden çıktı" sorusu cevapsız kalır.

    /** "POST https://host/path" — gözlemlenen isteğin özeti. */
    @Column(columnDefinition = "TEXT")
    private String observedRequestLine;

    /** Gerçekten dönen HTTP durum kodu. */
    private Integer observedStatus;

    /** Gerçekten ölçülen süre (ms). SLA yalnızca bu değerden türetilebilir. */
    private Long observedDurationMs;

    /** Yanıt gövdesi — kısaltılmamış hâliyle saklanır. */
    @Column(columnDefinition = "TEXT")
    private String observedBody;

    /** Gerçek response header'ları — satır başına bir başlık. */
    @Column(columnDefinition = "TEXT")
    private String observedResponseHeaders;

    /** Set-Cookie başlıklarından çıkarılan cookie'ler — satır başına bir cookie. */
    @Column(columnDefinition = "TEXT")
    private String observedResponseCookies;

    /** UTF-8 yanıt gövdesinin byte cinsinden ölçülen boyutu. */
    private Long observedResponseSizeBytes;

    /** HTTP_1_1 veya HTTP_2. */
    private String observedHttpVersion;

    /** Gözlem yapılamadıysa nedeni; yapıldıysa null. */
    @Column(columnDefinition = "TEXT")
    private String observationSkipReason;

    private LocalDateTime observedAt;

    /**
     * Çok-ajanlı analiz adımı bu istek için koşulsun mu?
     *
     * Dahili alan — public üretim API'sinde yer almaz; varsayılan açıktır. Yalnızca
     * ajan ölçüm koşumu (benchmark) kontrol kolunu kapatmak için false yapar.
     */
    @Builder.Default
    private boolean agentsEnabled = true;

    /**
     * Bu istek için ajan katmanı genişliği. null ise konfigürasyondaki varsayılan geçerlidir.
     * Dahili alan — yalnızca ölçüm koşumu LEAN/FULL kollarını ayırmak için doldurur.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private com.testgen.agent.AgentRouting.Mode agentMode;

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<GeneratedTestCase> generatedTestCases;

    @Column(updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    @PreUpdate
    public void onUpdate() { this.updatedAt = LocalDateTime.now(); }
}
