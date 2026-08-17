package com.testgen.orchestration;

/** Framework, tool ve model provider ayrıntılarından bağımsız QA orkestrasyon girişi. */
public interface QaOrchestrator {
    OrchestrationResult execute(OrchestrationRequest request);
}
