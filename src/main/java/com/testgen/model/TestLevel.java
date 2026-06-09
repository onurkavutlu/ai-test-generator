package com.testgen.model;

/**
 * ISTQB test seviyesi — V-Model katmanlarına karşılık gelir.
 * @see <a href="https://www.istqb.org">ISTQB Foundation Level Syllabus 4.0</a>
 */
public enum TestLevel {

    UNIT("Birim Testi", "Tek bir bileşen/fonksiyonun testi"),
    INTEGRATION("Entegrasyon Testi", "Bileşenler arası arayüz ve etkileşim testi"),
    SYSTEM("Sistem Testi", "Tüm sistemin gereksinimlerine karşı testi"),
    ACCEPTANCE("Kabul Testi", "Kullanıcı/iş kabul kriterlerine karşı test");

    private final String displayName;
    private final String description;

    TestLevel(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
}
