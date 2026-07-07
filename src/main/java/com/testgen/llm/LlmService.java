package com.testgen.llm;

import com.testgen.model.TestFramework;
import com.testgen.model.TestType;

/**
 * LLM servis soyutlama katmanı.
 * OpenAI, Ollama veya herhangi bir provider için implement edilebilir.
 */
public interface LlmService {

    /**
     * Verilen bağlama göre test case üretir.
     *
     * @param prompt LLM'e gönderilecek tam prompt
     * @return LLM'in ürettiği raw metin (feature/java kodu)
     */
    String generateTestCase(String prompt);

    /**
     * Çağrı tipini belirterek test case üretir (raporlama için).
     * callType: KARATE | SELENIUM | FAILURE_ANALYSIS | AGENT | GENERIC
     */
    default String generateTestCase(String prompt, String callType) {
        return generateTestCase(prompt);
    }

    /**
     * Swagger/OpenAPI spec'ini okuyup Karate feature üretir.
     */
    String generateFromSwagger(String swaggerContent, String endpoint, String method, String context);

    /**
     * Raw payload (cURL/JSON/XML) okuyup Karate feature üretir.
     */
    String generateFromRawPayload(String rawPayload, String payloadType, String context);

    /**
     * GraphQL payload okuyup Karate feature üretir.
     */
    String generateFromGraphQL(String graphqlDetails, String context);

    /**
     * SOAP XML payload okuyup Karate feature üretir.
     */
    String generateFromSoap(String soapXml, String context);

    /**
     * UI screenshot veya selector bilgisine göre Selenium test üretir.
     */
    String generateSeleniumTest(String pageUrl, String userStory, String htmlHint);

}
