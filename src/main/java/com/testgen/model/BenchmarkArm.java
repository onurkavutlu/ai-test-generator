package com.testgen.model;

/**
 * Ölçüm koşumunun kolu.
 *
 * Aynı girdi iki kez üretilir; tek fark çok-ajanlı analiz adımının koşulup koşulmadığıdır.
 */
public enum BenchmarkArm {
    /** Çok-ajanlı analiz açık (mevcut varsayılan davranış). */
    WITH_AGENTS,
    /** Kontrol kolu: ajan analizi atlanır. */
    WITHOUT_AGENTS,
    /** Ajan katmanı dar: yalnızca çıktısı test üretiminde kullanılan ajanlar. */
    LEAN_AGENTS,
    /** Ajan katmanı geniş: önerilen ajanlar + yönetici özeti de koşar. */
    FULL_AGENTS
}
