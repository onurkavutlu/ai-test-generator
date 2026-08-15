package com.testgen.llm;

/**
 * Bir LLM çağrısının <b>hangi iş için</b> yapıldığını taşıyan korelasyon bağlamı.
 *
 * <p><b>Neden gerekli:</b> LLM çağrı geçmişi ekranı 15 çağrıyı listeliyor ama
 * hiçbirinin hangi üretim isteğine ait olduğu görünmüyordu — çünkü kayıtta böyle
 * bir alan yoktu. Aynı anda iki üretim koştuğunda hangi maliyetin hangi isteğe ait
 * olduğu ölçülemiyor, bir isteğin toplam maliyeti çıkarılamıyordu.
 *
 * <p><b>Neden ThreadLocal:</b> LLM çağrıları ajan katmanının derinlerinde, imzasında
 * requestId olmayan metotlardan yapılıyor. Tüm çağrı zincirine parametre eklemek
 * yerine bağlam iş parçacığına iliştirilir. Ölçüldü: bir üretim isteğinin tüm LLM
 * çağrıları tek bir {@code task-N} iş parçacığında koşuyor, dolayısıyla bu güvenli.
 *
 * <p>Bağlam kurulmamışsa alanlar {@code null} kalır — <b>uydurulmaz</b>. Bilinmeyen
 * bir korelasyon, yanlış bir korelasyondan iyidir.
 *
 * <p>Kullanım her zaman try/finally ile:
 * <pre>
 * LlmCallContext.set(requestId, LlmCallContext.Phase.GENERATION);
 * try { ... } finally { LlmCallContext.clear(); }
 * </pre>
 */
public final class LlmCallContext {

    /** Çağrının hangi iş akışında yapıldığı. */
    public enum Phase {
        /** Normal test üretimi */
        GENERATION,
        /** Üretim kapısı içeriği doğrulayamadı, düzeltme isteniyor */
        VALIDATION_REPAIR,
        /** Başarısız testin onarımı */
        SELF_HEAL,
        /** Ajan kıyaslama koşumu */
        BENCHMARK,
        /** Runner üzerinden tekil çağrı */
        RUNNER
    }

    public record Scope(String requestId, Phase phase) {}

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private LlmCallContext() {
    }

    public static void set(String requestId, Phase phase) {
        CURRENT.set(new Scope(requestId, phase));
    }

    /**
     * Mevcut requestId'yi koruyarak yalnızca fazı değiştirir; bağlam yoksa hiçbir şey
     * yapmaz. Üretim içinde tetiklenen doğrulama onarımı gibi iç içe adımlar için.
     *
     * @return önceki kapsam — çağıran try/finally ile geri koymalıdır
     */
    public static Scope enterPhase(Phase phase) {
        Scope previous = CURRENT.get();
        if (previous != null) {
            CURRENT.set(new Scope(previous.requestId(), phase));
        }
        return previous;
    }

    public static void restore(Scope scope) {
        if (scope == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(scope);
        }
    }

    public static String currentRequestId() {
        Scope s = CURRENT.get();
        return s == null ? null : s.requestId();
    }

    public static String currentPhase() {
        Scope s = CURRENT.get();
        return s == null || s.phase() == null ? null : s.phase().name();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
