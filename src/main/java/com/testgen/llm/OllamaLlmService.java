package com.testgen.llm;

import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Ollama yerel LLM entegrasyonu.
 * Her çağrı LlmReportStore'a kaydedilir (model, süre, token tahmini, içerik özeti).
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "llm.provider", havingValue = "ollama", matchIfMissing = true)
public class OllamaLlmService implements LlmService {

    private final ChatLanguageModel chatModel;
    private final String modelName;
    private final LlmReportStore reportStore;

    private static final String SYSTEM_PROMPT = """
            Sen uzman bir test otomasyon mühendisisin.
            Görevin: Karate DSL ve Selenium (Java/Page Object Model) kullanarak
            production-ready, kapsamlı test case'leri üretmek.

            KURALLAR:
            1. Sadece kod üret — açıklama satırları kod içinde Türkçe yorum olarak
            2. Page Object Model (POM) pattern'i kullan
            3. Pozitif VE negatif senaryolar ekle
            4. Edge case'leri dahil et
            5. Her test bağımsız çalışabilir olsun
            6. Markdown ```code``` bloğu içinde döndür
            """;

    /** Bağlam penceresi — prompt bunu aşarsa Ollama sessizce kırpar (bkz. warnIfPromptExceedsContext). */
    private final int numCtx;

    public OllamaLlmService(
            ChatLanguageModel chatModel,
            @Value("${llm.ollama.model}") String model,
            @Value("${llm.ollama.num-ctx:16384}") int numCtx,
            LlmReportStore reportStore) {

        this.modelName   = model;
        this.reportStore = reportStore;
        this.chatModel   = chatModel;
        this.numCtx      = numCtx;

        log.info("Ollama LLM Service başlatıldı — model: {}, numCtx: {}", model, numCtx);
    }

    @Override
    public String generateTestCase(String prompt) {
        return callLlm(prompt, "GENERIC");
    }

    /** Framework tipiyle çağır (KARATE / SELENIUM / FAILURE_ANALYSIS). */
    public String generateTestCase(String prompt, String callType) {
        return callLlm(prompt, callType != null ? callType : "GENERIC");
    }

    @Override
    public String generateFromSwagger(String swaggerContent, String endpoint, String method, String context) {
        String prompt = PromptTemplates.buildKaratePrompt(swaggerContent, endpoint, method, context);
        return callLlm(prompt, "KARATE");
    }

    @Override
    public String generateFromRawPayload(String rawPayload, String payloadType, String context) {
        String prompt = PromptTemplates.buildRawPayloadPrompt(rawPayload, payloadType, context);
        return callLlm(prompt, "KARATE");
    }

    @Override
    public String generateFromGraphQL(String graphqlDetails, String context) {
        String prompt = PromptTemplates.buildGraphQLPrompt(graphqlDetails, context);
        return callLlm(prompt, "KARATE");
    }

    @Override
    public String generateFromSoap(String soapXml, String context) {
        String prompt = PromptTemplates.buildSoapPrompt(soapXml, context);
        return callLlm(prompt, "KARATE");
    }

    @Override
    public String generateSeleniumTest(String pageUrl, String userStory, String htmlHint) {
        String prompt = PromptTemplates.buildSeleniumPrompt(pageUrl, userStory, htmlHint);
        return callLlm(prompt, "SELENIUM");
    }

    // ─────────────────────────────────────────────────────────
    // Merkezi LLM çağrı metodu — her çağrıyı raporlar
    // ─────────────────────────────────────────────────────────
    private String callLlm(String prompt, String callType) {
        warnIfPromptExceedsContext(prompt, callType);
        long start = System.currentTimeMillis();
        try {
            var response = chatModel.generate(
                    SystemMessage.from(SYSTEM_PROMPT),
                    UserMessage.from(prompt)
            );
            String result = response.content().text();
            long durationMs = System.currentTimeMillis() - start;

            reportStore.record(LlmCallReport.success(modelName, callType, prompt, result, durationMs));
            return result;

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - start;
            log.warn("Ollama çağrısı başarısız ({}ms): {}. Lütfen Ollama'yı başlatın ve gemma4:31b-cloud modelini yükleyin.", durationMs, e.getMessage());
            reportStore.record(LlmCallReport.failure(modelName, callType, prompt, e.getMessage(), durationMs));
            throw new RuntimeException("LLM bağlantı hatası: " + e.getMessage() + ". Lütfen Ollama'yı başlatın!");
        }
    }

    /**
     * Prompt bağlam penceresine sığmıyorsa uyarır.
     *
     * NEDEN: Ollama sığmayan prompt'u HATA VERMEDEN kırpar. Ölçülen bir koşumda
     * gözlem katmanı prompt'u 34.000+ karaktere çıkardı, pencere 8192 token'dı ve
     * "Return ONLY the .feature file content" talimatı kırpılan kısımda kaldı —
     * model kod yerine açıklama metni döndürdü. Üretilen case'lerin çoğunda hiç
     * Gherkin/Java olmamasının sebebi buydu ve hiçbir yerde iz bırakmıyordu.
     */
    private void warnIfPromptExceedsContext(String prompt, String callType) {
        if (prompt == null) {
            return;
        }
        // Türkçe metinde token başına ~3 karakter; İngilizce'de ~4. Temkinli taraf seçildi.
        int estimatedTokens = prompt.length() / 3;
        if (estimatedTokens > numCtx * 0.9) {
            log.warn("⚠ Prompt bağlam penceresini zorluyor [{}]: ~{} token / numCtx {} "
                            + "({} karakter). Ollama fazlasını SESSİZCE KIRPAR; talimatlar düşebilir. "
                            + "llm.ollama.num-ctx değerini artırın ya da bağlamı küçültün.",
                    callType, estimatedTokens, numCtx, prompt.length());
        }
    }
}
