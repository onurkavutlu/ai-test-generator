package com.testgen.service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Saklama süresi temizliğinin sonucu.
 *
 * <p><b>Neden ayrı bir tip, Map değil:</b> bu sonuç hem önizleme (dryRun) hem gerçek
 * silme yolunda aynı alanları taşır. Map ile döndürülseydi iki yol zamanla farklı
 * anahtar kümeleri üretmeye başlardı ve önizlemenin gerçekten silinecek şeyi
 * gösterdiği garanti edilemezdi. Tek tip, iki yolun aynı şeyi raporlamasını
 * <b>derleme zamanında</b> zorunlu kılar.
 *
 * @param dryRun                 true ise hiçbir kayıt silinmedi; sayılar yalnız önizlemedir
 * @param retentionDays          uygulanan saklama süresi (gün)
 * @param cutoff                 bu andan ESKİ kayıtlar temizlik kapsamındadır
 * @param requestCount           silinen (veya dryRun'da silinecek) üretim isteği sayısı
 * @param testCaseCount          silinen (veya dryRun'da silinecek) test case sayısı
 * @param protectedRequestCount  yaşı dolmuş olmasına rağmen bir suite'e bağlı olduğu için
 *                               korunan istek sayısı
 * @param protectedRequestIds    korunan isteklerin kimlikleri (en fazla ilk 50 tanesi)
 */
public record DataRetentionResult(
        boolean dryRun,
        int retentionDays,
        LocalDateTime cutoff,
        long requestCount,
        long testCaseCount,
        long protectedRequestCount,
        List<String> protectedRequestIds) {
}
