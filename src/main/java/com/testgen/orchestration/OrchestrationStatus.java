package com.testgen.orchestration;

/** Yaşam döngüsü için süreçler arası taşınabilir durum adları. */
public enum OrchestrationStatus {
    PLANNED,
    RUNNING,
    COMPLETED,
    FAILED,
    REJECTED
}
