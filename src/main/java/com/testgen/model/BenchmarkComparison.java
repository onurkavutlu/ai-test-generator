package com.testgen.model;

import java.util.List;

/**
 * Ölçüm koşumunun karşılaştırma ekseni.
 *
 * Her eksen tam iki kol koşar; kollar arasındaki TEK fark eksenin tanımladığı değişkendir.
 */
public enum BenchmarkComparison {

    /** Ajan katmanı açık mı kapalı mı? */
    AGENTS_ON_OFF(List.of(BenchmarkArm.WITH_AGENTS, BenchmarkArm.WITHOUT_AGENTS)),

    /** Ajan katmanı dar mı geniş mi? (ikisinde de ajanlar açık) */
    LEAN_VS_FULL(List.of(BenchmarkArm.LEAN_AGENTS, BenchmarkArm.FULL_AGENTS));

    private final List<BenchmarkArm> arms;

    BenchmarkComparison(List<BenchmarkArm> arms) {
        this.arms = arms;
    }

    public List<BenchmarkArm> arms() {
        return arms;
    }
}
