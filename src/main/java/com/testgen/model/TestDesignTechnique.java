package com.testgen.model;

/**
 * ISTQB test tasarım tekniği — Test case'in hangi teknikle üretildiğini belirtir.
 * @see <a href="https://www.istqb.org">ISTQB Foundation Level Syllabus 4.0, Bölüm 4</a>
 */
public enum TestDesignTechnique {

    // ── Black-box (Kara Kutu) Teknikleri ─────────────────────
    EQUIVALENCE_PARTITIONING("Eşdeğerlik Bölümleme",
            "Girdi alanını eşdeğer sınıflara ayırarak her sınıftan bir temsilci seçer"),
    BOUNDARY_VALUE_ANALYSIS("Sınır Değer Analizi",
            "Sınır değerleri (min, min+1, max-1, max) ile test eder"),
    DECISION_TABLE("Karar Tablosu",
            "Koşul-eylem kombinasyonlarını tablo ile sistematik kapsar"),
    STATE_TRANSITION("Durum Geçişi",
            "Sistem durumları arası geçişleri ve geçersiz geçişleri test eder"),
    USE_CASE("Kullanım Senaryosu",
            "Kullanıcı akışlarını uçtan uca senaryo olarak kapsar"),
    PAIRWISE("İkili Kombinasyon",
            "Parametrelerin ikili kombinasyonlarını minimize ederek kapsar"),

    // ── White-box (Beyaz Kutu) Teknikleri ────────────────────
    STATEMENT_COVERAGE("İfade Kapsamı", "Her kod satırının en az bir kez çalıştığını doğrular"),
    BRANCH_COVERAGE("Dal Kapsamı", "Her if/else dalının en az bir kez çalıştığını doğrular"),

    // ── Deneyim Tabanlı Teknikler ────────────────────────────
    ERROR_GUESSING("Hata Tahmini", "Deneyime dayalı hata senaryoları üretir"),
    EXPLORATORY("Keşif Testi", "Serbest keşif ile test charter'ları kullanır"),
    CHECKLIST_BASED("Kontrol Listesi", "Önceden tanımlı kontrol listesine göre test eder"),

    // ── AI/LLM Tabanlı ──────────────────────────────────────
    AI_GENERATED("AI Tabanlı Üretim", "LLM tarafından bağlam analizi ile üretilmiş"),
    AI_SELF_HEALED("AI Self-Healing", "Başarısız test üzerinde LLM ile otomatik iyileştirme");

    private final String displayName;
    private final String description;

    TestDesignTechnique(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
}
