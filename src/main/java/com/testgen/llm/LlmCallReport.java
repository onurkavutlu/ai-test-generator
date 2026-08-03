package com.testgen.llm;

import java.time.LocalDateTime;

/**
 * Tek bir LLM çağrısının metriklerini ve üretilen içeriği tutar.
 * OllamaLlmService her generateXxx çağrısında bu nesneyi üretir.
 */
public record LlmCallReport(
                String model,
                String callType, // KARATE | SELENIUM | FAILURE_ANALYSIS | AGENT
                String promptSummary, // prompt'un ilk 200 karakteri
                int promptChars,
                int responseChars,
                long durationMs,
                boolean success,
                String errorMessage, // null ise başarılı
                String rawResponse,
                LocalDateTime calledAt) {
        /** Başarılı çağrı için kısa fabrika. */
        public static LlmCallReport success(String model, String callType,
                        String prompt, String response, long durationMs) {
                return new LlmCallReport(
                                model, callType,
                                prompt.length() > 200 ? prompt.substring(0, 200) + "…" : prompt,
                                prompt.length(), response.length(),
                                durationMs, true, null, response,
                                LocalDateTime.now());
        }

        /** Başarısız çağrı için kısa fabrika. */
        public static LlmCallReport failure(String model, String callType,
                        String prompt, String error, long durationMs) {
                return new LlmCallReport(
                                model, callType,
                                prompt.length() > 200 ? prompt.substring(0, 200) + "…" : prompt,
                                prompt.length(), 0,
                                durationMs, false, error, null,
                                LocalDateTime.now());
        }

        /** Tahmini token sayısı (yaklaşık: 1 token ≈ 4 karakter). */
        public int estimatedPromptTokens() {
                return promptChars / 4;
        }

        public int estimatedResponseTokens() {
                return responseChars / 4;
        }
}
