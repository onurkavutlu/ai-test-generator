package com.testgen.orchestration;

/** Bu sürümde kayıtlı olmayan veya uygulanmayan adımın açık hatası. */
public class UnsupportedOrchestrationStepException extends RuntimeException {
    public UnsupportedOrchestrationStepException(OrchestrationStepType type) {
        super("Desteklenmeyen orkestrasyon adımı: " + type);
    }
}
