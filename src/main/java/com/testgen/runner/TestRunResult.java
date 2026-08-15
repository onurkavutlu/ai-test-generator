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

    private static final java.util.regex.Pattern SUREFIRE_SUMMARY = java.util.regex.Pattern.compile(
            "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+)(?:,\\s*Skipped:\\s*(\\d+))?");

    /**
     * Maven/Surefire çıktısından GERÇEK geçen/kalan sayılarını çıkarır.
     *
     * "hepsi geçti ya da hiçbiri geçmedi" varsayımı kısmi başarıyı (örn. 10 testin 6'sı
     * geçti) raporlarda tamamen kaybediyordu; burada özet satırı ayrıştırılır.
     * Özet satırı bulunamazsa {@link #ofMaven} davranışına düşülür.
     */
    public static TestRunResult fromSurefireOutput(boolean processOk, String output, long durationMs) {
        java.util.regex.Matcher m = SUREFIRE_SUMMARY.matcher(output == null ? "" : output);
        int total = -1, failures = 0, errors = 0, skipped = 0;
        while (m.find()) { // birden çok özet satırı olabilir; sonuncusu genel toplamdır
            total    = Integer.parseInt(m.group(1));
            failures = Integer.parseInt(m.group(2));
            errors   = Integer.parseInt(m.group(3));
            skipped  = m.group(4) != null ? Integer.parseInt(m.group(4)) : 0;
        }
        if (total < 0) {
            // Derleme hatası / mvn hiç koşamadı: senaryo sayısı bilinmiyor
            return ofMaven(processOk, output, processOk ? 1 : 0, durationMs);
        }
        int failed = failures + errors;
        int passed = Math.max(0, total - failed - skipped);
        return of(processOk && failed == 0, output, total, passed, failed, durationMs);
    }

    private static String truncate(String text) {
        if (text == null) return "";
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= MAX_OUTPUT_BYTES) return text;
        String truncated = new String(bytes, 0, MAX_OUTPUT_BYTES, java.nio.charset.StandardCharsets.UTF_8);
        return truncated + "\n...[LOG KISALTILDI — toplam " + bytes.length + " byte]";
    }
}
