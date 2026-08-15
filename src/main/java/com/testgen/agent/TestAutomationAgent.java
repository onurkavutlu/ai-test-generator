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
                "Karate/Selenium için koşulabilir otomasyon kapsamı, locator/endpoint ve assertion öner.");
    }
}
