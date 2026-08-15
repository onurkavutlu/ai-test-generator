package com.testgen.generator;

import com.testgen.model.TestGenerationRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bir üretim isteğinde kaç test case üretileceğini belirler.
 *
 * NEDEN AYRI BİR SINIF: Sınırın prompt'la konulamadığı deneyle görüldü —
 * "en fazla 5 case üret" talimatı LLM'e verildiğinde yalnızca dosya İÇİNDEKİ
 * senaryo sayısını etkiliyor; case sayısı üretici döngüsünde endpoint sayısına
 * eşit kalıyor (37 yollu bir API → 45 case, ~25 dk). Sınır bu yüzden
 * deterministik olarak üretici döngüsünde uygulanır ve tüm üreticiler aynı
 * kuralı paylaşır.
 *
 * ÖNCELİK SIRASI:
 *   1. İstekteki {@code maxCases} — verilmişse her zaman kazanır
 *   2. {@code test-generator.generation.default-max-cases} — kurulum genelinde varsayılan
 *   3. Hiçbiri yoksa sınırsız (endpoint sayısı kadar) — eski davranış korunur
 *
 * İstek alanı ZORUNLU DEĞİLDİR; boş bırakıldığında 2. ve 3. adımlar devreye girer.
 */
@Component
public class GenerationLimit {

    /** 0 veya negatif = kurulum genelinde sınır yok. */
    @Value("${test-generator.generation.default-max-cases:0}")
    private int defaultMaxCases;

    public GenerationLimit() {
        // Spring bu kurucuyu kullanır; değer @Value ile enjekte edilir
    }

    /** Testler ve elle kurulum için. */
    GenerationLimit(int defaultMaxCases) {
        this.defaultMaxCases = defaultMaxCases;
    }

    /**
     * @param request       üretim isteği (opsiyonel {@code maxCases} taşıyabilir)
     * @param endpointCount spec'teki endpoint sayısı — hiçbir sınır yoksa bu kullanılır
     * @return üretilecek en fazla case sayısı (her zaman ≥ 1)
     */
    public int resolve(TestGenerationRequest request, int endpointCount) {
        Integer requested = request == null ? null : request.getMaxCases();
        if (requested != null && requested > 0) {
            return requested;
        }
        if (defaultMaxCases > 0) {
            return defaultMaxCases;
        }
        return Math.max(1, endpointCount);
    }
}
