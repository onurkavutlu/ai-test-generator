package com.testgen.model;

/**
 * Üretilen test içeriğinin ÜRETİM ANINDAKİ makine doğrulaması sonucu.
 *
 * Amaç: sözdizimi/derleme hatalarını koşum anında "0/0 FAILED" olarak görmek yerine
 * üretildiği anda yakalamak.
 */
public enum ValidationStatus {
    /** Henüz doğrulanmadı (eski kayıtlar veya manuel eklenen case'ler). */
    NOT_VALIDATED,
    /** Parse/derleme başarılı — case koşulabilir. */
    VALID,
    /** Parse/derleme başarısız — içerik kaydedildi ama koşulamaz. */
    INVALID,
    /**
     * Doğrulama YAPILAMADI (örn. çalışma ortamında Java derleyicisi yok ya da
     * doğrulayıcının classpath'i eksik). Bu bir kalite yargısı DEĞİLDİR.
     */
    SKIPPED
}
