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

    protected AiAgentResult runAgent(AiAgentContext context, String title, String instructions, String fallback) {
        try {
            String response = llmService.generateTestCase(buildPrompt(context, instructions), "AGENT");
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
                App package: %s
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
                - Belirsiz endpoint, selector, veri veya ortam bilgisi uydurma; varsayım gerekiyorsa "varsayım" diye açık yaz.
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
                valueOrDash(request.getAppPackage()),
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
