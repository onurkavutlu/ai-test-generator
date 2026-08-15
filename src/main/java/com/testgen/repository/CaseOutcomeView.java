package com.testgen.repository;

import com.testgen.model.TestRunStatus;

/**
 * Yakınsama raporunun ihtiyaç duyduğu tek şey: hangi isteğe ait, geçti mi,
 * kaç senaryo, kaynağı LLM mi gözlem mi.
 *
 * Ağır sütunlar (testContent, runOutput) bilinçli olarak DIŞARIDA bırakıldı —
 * rapor bunları kullanmıyor ama önceki sürüm hepsini belleğe çekiyordu.
 */
public record CaseOutcomeView(
        String requestId,
        TestRunStatus runStatus,
        Integer totalScenarios,
        Integer passedScenarios,
        boolean deterministic
) {}
