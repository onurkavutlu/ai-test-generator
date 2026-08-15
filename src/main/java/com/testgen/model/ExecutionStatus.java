package com.testgen.model;

/**
 * Bir test koşumunun (Test Execution) yaşam döngüsü durumu.
 *
 * PENDING → RUNNING → PASSED | FAILED
 * ABORTED yalnızca koşum beklenmedik şekilde sonlandığında kullanılır.
 */
public enum ExecutionStatus {
    PENDING,
    RUNNING,
    PASSED,
    FAILED,
    ABORTED
}
