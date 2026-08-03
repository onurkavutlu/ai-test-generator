package com.testgen.agent;

import com.testgen.llm.LlmService;
import org.springframework.stereotype.Component;

@Component
public class TestAutomationAgent extends AbstractLlmAgent {

    public TestAutomationAgent(LlmService llmService) {
        super(llmService);
    }

    @Override
    public AiAgentRole role() {
        return AiAgentRole.TEST_AUTOMATION;
    }

    @Override
    public AiAgentResult analyze(AiAgentContext context) {
        return runAgent(context, "Test Automation Agent",
                "Karate/Selenium için koşulabilir otomasyon kapsamı, locator/endpoint ve assertion öner.",
                """
                Otomasyon kapsamı frameworke göre koşulabilir dosya üretmeli: Karate feature, Selenium JUnit test.
                Case seti API, smoke, regression, negative, edge, security ve performance kapsamlarını etiketli senaryolarla kapsamalı.
                Assertionlar status, response schema, UI mesajı, ekran state, hata senaryoları ve süre/timeout beklentisini kapsamalı.
                Test çıktısı Allure ve email raporunda okunabilir summary ile dönmeli.
                """);
    }
}
