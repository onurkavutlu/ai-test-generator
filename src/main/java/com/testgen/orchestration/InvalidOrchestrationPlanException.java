package com.testgen.orchestration;

/** Beklenen plan doğrulama hatası. */
public class InvalidOrchestrationPlanException extends RuntimeException {
    public InvalidOrchestrationPlanException(String message) {
        super(message);
    }
}
