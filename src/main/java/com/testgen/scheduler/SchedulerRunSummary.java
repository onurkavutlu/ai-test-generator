package com.testgen.scheduler;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Tek bir scheduler koşumunun özet sonucu.
 */
public record SchedulerRunSummary(
        String requestId,
        int totalExisting,    // kaç mevcut test vardı
        int passed,           // kaç geçti
        int failed,           // kaç başarısız oldu
        int newGenerated,     // LLM kaç yeni test üretti
        LocalDateTime ranAt
) {
    public String formattedDate() {
        return ranAt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"));
    }

    @Override
    public String toString() {
        return "SchedulerRunSummary{requestId='%s', existing=%d, passed=%d, failed=%d, newGenerated=%d, at=%s}"
                .formatted(requestId, totalExisting, passed, failed, newGenerated, formattedDate());
    }
}
