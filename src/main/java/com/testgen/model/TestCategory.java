package com.testgen.model;

/**
 * ISTQB test kategorisi — Fonksiyonel ve Fonksiyonel Olmayan test ayrımı.
 * @see <a href="https://www.istqb.org">ISTQB Foundation Level Syllabus 4.0</a>
 */
public enum TestCategory {

    // ── Fonksiyonel Test Türleri ──────────────────────────────
    SMOKE("Duman Testi", "Temel fonksiyonelliğin çalıştığını doğrular"),
    REGRESSION("Regresyon Testi", "Mevcut fonksiyonelliğin bozulmadığını doğrular"),
    INTEGRATION("Entegrasyon Testi", "Bileşenler arası etkileşimi doğrular"),
    E2E("Uçtan Uca Test", "Tam kullanıcı akışını doğrular"),
    ACCEPTANCE("Kabul Testi", "Business acceptance criteria'yı doğrular"),

    // ── Fonksiyonel Olmayan Test Türleri ──────────────────────
    PERFORMANCE("Performans Testi", "Latency, throughput ve kaynak kullanımı"),
    SECURITY("Güvenlik Testi", "Auth, injection, OWASP kontrolleri"),
    USABILITY("Kullanılabilirlik Testi", "UI/UX erişilebilirlik ve kullanım kolaylığı"),
    RELIABILITY("Güvenilirlik Testi", "Hata toleransı ve kurtarma senaryoları"),
    COMPATIBILITY("Uyumluluk Testi", "Farklı platform/tarayıcı/cihaz uyumu"),

    // ── Negatif & Sınır Testleri ─────────────────────────────
    NEGATIVE("Negatif Test", "Geçersiz girdi ve hata senaryoları"),
    BOUNDARY("Sınır Değer Testi", "Boundary value ve edge case senaryoları"),
    EXPLORATORY("Keşif Testi", "Serbest keşif ve ad-hoc test senaryoları");

    private final String displayName;
    private final String description;

    TestCategory(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
}
