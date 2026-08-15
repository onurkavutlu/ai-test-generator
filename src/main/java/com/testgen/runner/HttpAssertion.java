package com.testgen.runner;

/**
 * Bir HTTP yanıtı üzerinde doğrulanabilir tek bir kural.
 *
 * NEDEN VAR: Runner bugüne kadar isteği gönderip yanıtı gösteriyordu, doğrulama
 * kullanıcının gözüne kalıyordu. Yakalanan yanıttan üretilen deterministik test
 * ise yalnızca İKİ şey doğruluyordu (status + sabit 10 sn süre sınırı) — gövdeye
 * hiç bakmıyordu.
 *
 * Bu model, gerçek yanıttan türetilen doğrulamaları taşınabilir hale getirir:
 * aynı liste hem Runner'da yerinde değerlendirilir, hem de üretilen Karate /
 * REST Assured testine derlenebilir. Değerler UYDURULMAZ — hepsi gözlenen
 * yanıttan gelir, bu yüzden yakalama anında geçmeleri garantidir.
 *
 * @param type        neyin doğrulandığı
 * @param path        JSON gövde yolu (ör. {@code $.data[0].id}) ya da header adı; diğerlerinde null
 * @param operator    karşılaştırma biçimi
 * @param expected    gözlenen değer / tip / eşik — metin olarak taşınır
 * @param description ekranda gösterilecek insan-okur açıklama
 * @param enabled     kullanıcı kapatabilir; kapalı assertion derlenmez ve değerlendirilmez
 */
public record HttpAssertion(
        Type type,
        String path,
        Operator operator,
        String expected,
        String description,
        boolean enabled
) {

    public enum Type {
        /** HTTP durum kodu */
        STATUS,
        /** Yanıt header'ı */
        HEADER,
        /** Yanıt süresi (ms) */
        RESPONSE_TIME,
        /** JSON gövdesinde bir alanın varlığı */
        JSON_FIELD_EXISTS,
        /** JSON gövdesinde bir alanın tipi */
        JSON_FIELD_TYPE,
        /** JSON dizisinin eleman sayısı */
        JSON_ARRAY_SIZE
    }

    public enum Operator {
        EQUALS,
        CONTAINS,
        LESS_THAN,
        NOT_NULL,
        TYPE_IS
    }

    /** Varsayılan olarak açık üretilir; kullanıcı sonradan kapatabilir. */
    public static HttpAssertion of(Type type, String path, Operator operator,
                                   String expected, String description) {
        return new HttpAssertion(type, path, operator, expected, description, true);
    }

    public HttpAssertion disabled() {
        return new HttpAssertion(type, path, operator, expected, description, false);
    }
}
