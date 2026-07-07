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
            @Value("${llm.ollama.timeout-seconds:300}") long timeoutSeconds) {
        
        log.info("Creating OllamaChatModel Bean - model: {}, url: {}", model, baseUrl);
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(model)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .numPredict(2048)
                .numCtx(8192)
                .build();
    }
}
