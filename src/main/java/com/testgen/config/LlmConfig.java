package com.testgen.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Slf4j
@Configuration
public class LlmConfig {

    @Bean
    @ConditionalOnProperty(name = "llm.provider", havingValue = "ollama", matchIfMissing = true)
    public ChatLanguageModel ollamaChatModel(
            @Value("${llm.ollama.base-url}") String baseUrl,
            @Value("${llm.ollama.model}") String model,
            @Value("${llm.ollama.temperature}") double temperature,
            @Value("${llm.ollama.timeout-seconds:300}") long timeoutSeconds,
            @Value("${llm.ollama.num-ctx:16384}") int numCtx,
            @Value("${llm.ollama.num-predict:2048}") int numPredict) {

        log.info("Creating OllamaChatModel Bean - model: {}, url: {}, numCtx: {}", baseUrl, model, numCtx);
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(model)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .numPredict(numPredict)
                /*
                 * ÖLÇÜLEN ARIZA: numCtx 8192'de sabitti; gözlem katmanı her endpoint için
                 * gerçek yanıt gövdesi eklediğinden prompt'lar 34.000+ karaktere (~10k token)
                 * çıkıyor ve pencereye SIĞMIYORDU. Ollama fazlasını sessizce kırpıyor,
                 * "Return ONLY the .feature file content" gibi talimatlar düşüyor ve model
                 * kod yerine açıklama metni döndürüyordu — üretilen case'lerin çoğunun
                 * içinde hiç Gherkin/Java olmamasının sebebi buydu.
                 *
                 * llama3.1 131.072 token destekliyor; darboğaz modelin değil bu ayarındı.
                 * Bellek kısıtlı ortamlarda llm.ollama.num-ctx ile düşürülebilir.
                 */
                .numCtx(numCtx)
                .build();
    }
}
