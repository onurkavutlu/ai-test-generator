package com.testgen.model;

/**
 * Koşumu kimin/neyin başlattığı — denetim ve raporlama için kaydedilir.
 */
public enum ExecutionTrigger {
    /** Test Plan üzerinden koşuldu */
    PLAN,
    /** Tek bir Test Suite üzerinden koşuldu */
    SUITE,
    /** Geçmiş bir koşumun aynı kapsamla tekrarı */
    RERUN,
    /** Günlük zamanlayıcı */
    SCHEDULER
}
