package com.testgen.runner;

/**
 * Test koşumu sonucu.
 *
 * @param passed       tüm senaryolar geçtiyse true
 * @param output       runner log çıktısı (10KB ile kısıtlı)
 * @param total        toplam senaryo sayısı
 * @param passedCount  geçen senaryo sayısı (kısmi başarı desteği)
 * @param failedCount  başarısız senaryo sayısı
 * @param durationMs   koşum süresi (ms)
 */
public record TestRunResult(
        boolean passed,
        String output,
        int total,
        int passedCount,
        int failedCount,
        long durationMs
) {
    private static final int MAX_OUTPUT_BYTES = 10_240; // 10 KB

    /** Geriye dönük uyumluluk için kısa fabrika metodu. */
    public static TestRunResult of(boolean passed, String output, int total,
                                   int passedCount, int failedCount, long durationMs) {
        String truncated = truncate(output);
        return new TestRunResult(passed, truncated, total, passedCount, failedCount, durationMs);
    }

    /** Sadece total biliniyorsa (Selenium alt-süreç). */
    public static TestRunResult ofMaven(boolean passed, String output, int total, long durationMs) {
        int p = passed ? total : 0;
        int f = passed ? 0 : total;
        return of(passed, output, total, p, f, durationMs);
    }

    private static String truncate(String text) {
        if (text == null) return "";
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= MAX_OUTPUT_BYTES) return text;
        String truncated = new String(bytes, 0, MAX_OUTPUT_BYTES, java.nio.charset.StandardCharsets.UTF_8);
        return truncated + "\n...[LOG KISALTILDI — toplam " + bytes.length + " byte]";
    }
}
