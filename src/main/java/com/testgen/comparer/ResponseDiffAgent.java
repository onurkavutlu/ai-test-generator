package com.testgen.comparer;

import com.testgen.llm.LlmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Comparer'a özel ajan — YALNIZCA yanıt gövdelerini yorumlar.
 *
 * Kapsam bilinçli olarak dardır:
 *  - Farkları ajan HESAPLAMAZ; farkı deterministik {@link JsonDiff} çıkarır.
 *    Ajan yalnızca "bu farklar anlamlı mı, kırıcı mı?" sorusunu yanıtlar.
 *  - Test üretimi hattına DAHİL DEĞİLDİR; {@code AgentRouting} bu ajanı asla çağırmaz.
 *    Böylece küçültülen ajan katmanının maliyetini artırmaz.
 *  - Fark yoksa hiç çağrılmaz — sıfır maliyet.
 *
 * Amaç: alan bazlı diff listesini, gözden geçiren kişinin okuyabileceği bir
 * "kırıcı değişiklik mi, gürültü mü?" değerlendirmesine çevirmek.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResponseDiffAgent {

    private static final int MAX_DIFFS_IN_PROMPT = 25;
    private static final int MAX_BODY_CHARS = 1_500;

    private final LlmService llmService;

    @Value("${test-generator.comparer.agent-enabled:true}")
    private boolean enabled;

    /**
     * Farklı bulunan istekleri yorumlar.
     *
     * @return değerlendirme metni; ajan kapalıysa, fark yoksa veya LLM erişilemezse null
     */
    public String analyze(List<RequestComparisonResult> results) {
        if (!enabled) {
            return null;
        }
        List<RequestComparisonResult> different = results.stream()
                .filter(r -> !r.identical() && !r.hasError())
                .filter(r -> r.differences() != null && !r.differences().isEmpty())
                .toList();

        if (different.isEmpty()) {
            log.debug("Yanıt gövdelerinde fark yok — diff ajanı çağrılmadı.");
            return null;
        }

        try {
            String analysis = llmService.generateTestCase(buildPrompt(different), "AGENT_RESPONSE_DIFF");
            if (analysis == null || analysis.isBlank()) {
                return null;
            }
            log.info("Yanıt farkı ajanı {} farklı isteği değerlendirdi.", different.size());
            return analysis.trim();
        } catch (Exception e) {
            // Yorum üretilemezse karşılaştırma sonucu yine de döner — ajan opsiyoneldir
            log.warn("Yanıt farkı ajanı çalışmadı: {}", e.getMessage());
            return null;
        }
    }

    private String buildPrompt(List<RequestComparisonResult> different) {
        String cases = different.stream().map(this::describe).collect(Collectors.joining("\n\n"));
        return """
                Sen bir API yanıt farkı analiz ajanısın. Görevin YALNIZCA yanıt gövdelerindeki
                farkları değerlendirmek.

                Aşağıdaki farklar deterministik bir diff aracıyla çıkarılmıştır — yeniden hesaplama.
                Her istek için farkın anlamlı mı yoksa gürültü mü olduğunu değerlendir.

                %s

                Kurallar (KESİN):
                - Yukarıda listelenen HER fark için TEK BİR satır yaz, şu biçimde:
                  <alan yolu> | KIRICI | <en fazla 15 kelime gerekçe>
                - Etiket tam olarak ÜÇÜNDEN BİRİ olmalı: KIRICI, UYUMLU, GÜRÜLTÜ.
                  Aynı alana birden fazla etiket VERME; en olası tek etiketi seç.
                    KIRICI  = tüketiciyi bozar (alan kayboldu, tip değişti, anlam değişti)
                    UYUMLU  = geriye dönük uyumlu (yeni alan eklendi, ek detay)
                    GÜRÜLTÜ = her çağrıda doğal değişen değer (timestamp, id, süre, sıralama)
                - YALNIZCA yukarıda listelenen alan yolları hakkında yaz. Listede olmayan
                  alan, endpoint, timestamp veya davranış UYDURMA.
                - Satırlardan sonra tek cümlelik genel sonuç yaz: "SONUÇ: ..."
                - Kod, test veya öneri yazma. Markdown başlığı ve kod bloğu kullanma.
                """.formatted(cases);
    }

    private String describe(RequestComparisonResult r) {
        List<FieldDifference> diffs = r.differences();
        String diffText = diffs.stream()
                .limit(MAX_DIFFS_IN_PROMPT)
                .map(d -> "  - %s [%s] A=%s | B=%s".formatted(
                        d.path(), d.type(), shorten(d.valueA()), shorten(d.valueB())))
                .collect(Collectors.joining("\n"));

        String omitted = diffs.size() > MAX_DIFFS_IN_PROMPT
                ? "\n  (… %d fark daha listelenmedi)".formatted(diffs.size() - MAX_DIFFS_IN_PROMPT)
                : "";

        return """
                ### %s (%s %s)
                Status: A=%s B=%s
                Gövde farkları (%d adet):
                %s%s""".formatted(
                r.name(), r.method(), r.path(),
                r.statusA(), r.statusB(),
                diffs.size(), diffText, omitted);
    }

    private static String shorten(String value) {
        if (value == null) {
            return "(yok)";
        }
        String clean = value.replaceAll("\\s+", " ").trim();
        return clean.length() > MAX_BODY_CHARS / 10
                ? clean.substring(0, MAX_BODY_CHARS / 10) + "…"
                : clean;
    }
}
