package com.testgen.model;

/**
 * ISTQB öncelik seviyesi — Test case'in koşum önceliğini belirler.
 */
public enum TestPriority {

    P0_BLOCKER("Blocker", "Kritik — sistemin temel fonksiyonunu engelleyen"),
    P1_CRITICAL("Critical", "Yüksek — ana iş akışını etkileyen"),
    P2_MAJOR("Major", "Orta — önemli ama workaround mevcut"),
    P3_MINOR("Minor", "Düşük — kozmetik veya iyileştirme");

    private final String displayName;
    private final String description;

    TestPriority(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
}
