package com.testgen.llm;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * OpenAI GPT-4 entegrasyonu.
 * LLM_PROVIDER=openai olarak set edildiğinde aktif olur.
 * Default provider Ollama olduğu için bu bean sadece explicit set'te yüklenir.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "llm.provider", havingValue = "openai")
public class OpenAiLlmService implements LlmService {

    private final ChatLanguageModel chatModel;

    private static final String SYSTEM_PROMPT = """
            Sen uzman bir test otomasyon mühendisisin.
            Görevin: Karate DSL ve Selenium (Java/Page Object Model) kullanarak
            production-ready, kapsamlı test case'leri üretmek.

            Kurallar:
            1. Sadece kod üret, açıklama ekleme (kod içi yorum hariç)
            2. Best practice pattern'leri kullan (POM, Data-Driven, BDD)
            3. Pozitif ve negatif senaryoları dahil et
            4. Edge case'leri göz önünde bulundur
            5. Her test bağımsız çalışabilir olsun
            6. Türkçe yorum satırları kullanabilirsin
            """;

    public OpenAiLlmService(
            @Value("${llm.openai.api-key}") String apiKey,
            @Value("${llm.openai.model}") String model,
            @Value("${llm.openai.base-url}") String baseUrl,
            @Value("${llm.openai.temperature}") double temperature,
            @Value("${llm.openai.max-tokens}") int maxTokens) {

        this.chatModel = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(model)
                .baseUrl(baseUrl)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();

        log.info("OpenAI LLM Service başlatıldı — model: {}", model);
    }

    /**
     * Test kurulumu için: hazır bir model enjekte eder.
     *
     * <p>Üretim yapıcısı modeli KENDİSİ kurduğu için (api-key, base-url ile) davranışı
     * ağ olmadan doğrulanamıyordu. Mock yedeği kaldırıldıktan sonra "LLM hatası sessizce
     * yutulmuyor" kuralının testle kilitlenmesi gerekiyor; bu yapıcı onun içindir.
     */
    OpenAiLlmService(ChatLanguageModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String generateTestCase(String prompt) {
        return generateTestCase(prompt, null);
    }

    /**
     * @param frameworkOverride yalnızca çağrı tipini etiketlemek için; üretim davranışını
     *                          değiştirmez (mock yedeği kaldırıldığından beri).
     */
    public String generateTestCase(String prompt, String frameworkOverride) {
        log.debug("OpenAI'ya istek gönderiliyor");
        try {
            var response = chatModel.generate(
                    SystemMessage.from(SYSTEM_PROMPT),
                    UserMessage.from(prompt));
            return response.content().text();
        } catch (Exception e) {
            // MOCK YEDEĞİ YOK — bilinçli karar. Önceden LLM erişilemediğinde sahte içerik
            // üretilip normal çıktı gibi döndürülüyordu: üretim "başarılı" görünüyor, testler
            // koşuyor ama doğruladıkları şey uydurma bir şablondu. Kök neden (LLM kapalı)
            // yalnızca WARN logunda kalıyor, kullanıcı asla görmüyordu.
            // Ollama yolu bu kararı zaten uyguluyordu; OpenAI yolu da aynı olmalı.
            log.error("OpenAI çağrısı başarısız — sahte içerik ÜRETİLMEZ: {}", e.getMessage());
            throw new LlmException("OpenAI servisi yanıt vermedi: " + e.getMessage(), e);
        }
    }

    @Override
    public String generateFromSwagger(String swaggerContent, String endpoint, String method, String context) {
        return generateTestCase(PromptTemplates.buildKaratePrompt(swaggerContent, endpoint, method, context), "KARATE");
    }

    @Override
    public String generateFromRawPayload(String rawPayload, String payloadType, String context) {
        return generateTestCase(PromptTemplates.buildRawPayloadPrompt(rawPayload, payloadType, context), "KARATE");
    }

    @Override
    public String generateFromGraphQL(String graphqlDetails, String context) {
        return generateTestCase(PromptTemplates.buildGraphQLPrompt(graphqlDetails, context), "KARATE");
    }

    @Override
    public String generateFromSoap(String soapXml, String context) {
        return generateTestCase(PromptTemplates.buildSoapPrompt(soapXml, context), "KARATE");
    }

    @Override
    public String generateSeleniumTest(String pageUrl, String userStory, String htmlHint) {
        return generateTestCase(PromptTemplates.buildSeleniumPrompt(pageUrl, userStory, htmlHint), "SELENIUM");
    }


}
