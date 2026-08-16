package com.testgen.agent;

import com.testgen.llm.LlmService;
import com.testgen.model.TestGenerationRequest;
import com.testgen.service.TestGenerationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public abstract class AbstractLlmAgent implements AiAgent {

    protected final LlmService llmService;

    /**
     * Ajanı koşturur ve YALNIZCA gerçek LLM çıktısını döndürür.
     *
     * <p><b>Yedek metin YOKTUR — bilinçli.</b> Önceden her ajan, LLM erişilemediğinde
     * kullanılmak üzere hazır yazılmış bir "analiz" metni taşıyordu. Bu metin bugün
     * kullanılmıyordu ama kodda durduğu sürece tek satırlık bir değişiklikle gerçek
     * ajan çıktısı gibi servis edilebilirdi. Uydurma analiz, uydurma testten daha
     * tehlikelidir: sonraki ajanlar ve üretim onu GÖZLEM sanıp üstüne inşa eder.
     * Bu yüzden parametre tamamen kaldırıldı; LLM konuşamıyorsa üretim durur.
     */
    protected AiAgentResult runAgent(AiAgentContext context, String title, String instructions) {
        try {
            // Rol bazlı callType: LlmReportStore'da ajan başına süre/başarı telemetrisi görünür
            String response = llmService.generateTestCase(buildPrompt(context, instructions), "AGENT_" + role().name());
            if (response != null && !response.isBlank()) {
                return new AiAgentResult(role(), title, clean(response));
            }
        } catch (Exception e) {
            log.error("{} agent LLM çıktısı üretemedi", role(), e);
            throw new TestGenerationException(role() + " agent LLM çıktısı üretemedi: " + e.getMessage(), e);
        }
        throw new TestGenerationException(role() + " agent boş LLM çıktısı döndürdü.");
    }

    private String buildPrompt(AiAgentContext context, String instructions) {
        TestGenerationRequest request = context.request();
        return """
                Sen bir %s agent'ısın. Bu çok agentlı test üretim iş akışında sıran geldi.
                Aşağıdaki test üretim isteği için yalnızca kendi uzmanlık alanında kısa, somut ve aksiyon odaklı analiz üret.
                Önceki agent çıktıları tamamlanmış kabul edilir; onları tekrar yazma, onların üstüne kendi rol çıktını ekle.

                Test tipi: %s
                Framework: %s
                User story: %s
                Swagger URL: %s
                Application URL: %s
                Ek bağlam: %s

                Önceki agent çıktıları:
                %s

                Görev:
                %s

                Kurallar:
                - 2-4 madde yaz.
                - Konudan sapma; yalnızca verilen user story, framework, URL/package ve ek bağlam üzerinden konuş.
                - Başka role ait işleri devralma; kendi rolünün karar, risk, veri veya aksiyonlarını üret.
                - Önceki agent çıktılarıyla çelişme; gerekiyorsa onları netleştir veya tamamla.
                - Belirsiz endpoint, selector, veri, status, SLA veya ortam bilgisi uydurma.
                - Kanıtı verilmeyen bilgi için varsayım yapma; açıkça "bilinmiyor" yaz.
                - Çıktın bir sonraki agent tarafından doğrudan kullanılabilir olmalı.
                - Test datası, test üretimi veya raporlama için doğrudan kullanılabilir somut bilgi ver.
                - Markdown kod bloğu kullanma.
                """.formatted(
                role(),
                request.getTestType(),
                request.getFramework(),
                valueOrDash(request.getUserStory()),
                valueOrDash(request.getSwaggerUrl()),
                valueOrDash(request.getApplicationUrl()),
                valueOrDash(request.getAdditionalContext()),
                context.previousOutputs(),
                instructions
        );
    }

    protected String clean(String response) {
        String clean = response.trim();
        if (clean.startsWith("```")) {
            int firstNewLine = clean.indexOf('\n');
            if (firstNewLine >= 0) {
                clean = clean.substring(firstNewLine + 1);
            }
            if (clean.endsWith("```")) {
                clean = clean.substring(0, clean.length() - 3);
            }
        }
        return clean.trim();
    }

    private String valueOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
