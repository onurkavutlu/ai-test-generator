package com.testgen.orchestration;

/**
 * Orkestratörün tanıdığı, kapalı adım kümesi.
 *
 * <p>Bu enum bir LLM'in üretebileceği serbest tool adlarının yerine geçer. İlk
 * iterasyonda yalnız TEST_DESIGN, GENERATE_TEST_ARTIFACT ve VALIDATE_ARTIFACT
 * planlanıp doğrulanır; diğer tipler gelecekte ilgili deterministic tool veya
 * adapter kaydı eklenmeden çalıştırılamaz.</p>
 */
public enum OrchestrationStepType {
    TEST_DESIGN,
    GENERATE_TEST_ARTIFACT,
    VALIDATE_ARTIFACT,
    EXECUTE_TEST,
    QUERY_DATABASE,
    FETCH_LOG,
    COMPARE,
    ANALYZE_DIFFERENCE,
    ANALYZE_FAILURE,
    GENERATE_REPORT
}
