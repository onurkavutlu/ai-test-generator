package com.testgen.comparer;

/**
 * İki yanıt gövdesi arasındaki tek bir alan farkı.
 * path: JSON pointer formatında alan konumu (örn: /data/items/0/price)
 */
public record FieldDifference(
        String path,
        DifferenceType type,
        String valueA,
        String valueB
) {
    public enum DifferenceType {
        VALUE_MISMATCH,
        TYPE_MISMATCH,
        MISSING_IN_A,
        MISSING_IN_B,
        ARRAY_LENGTH_MISMATCH
    }
}
