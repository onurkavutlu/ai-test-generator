package com.testgen.agent;

import com.testgen.llm.LlmService;
import org.springframework.stereotype.Component;

@Component
public class SecOpsAgent extends AbstractLlmAgent {

    public SecOpsAgent(LlmService llmService) {
        super(llmService);
    }

    @Override
    public AiAgentRole role() {
        return AiAgentRole.SECOPS;
    }

    @Override
    public AiAgentResult analyze(AiAgentContext context) {
        return runAgent(context, "SecOps Agent",
                "Auth, input validation, sensitive data, OWASP ve risk kontrollerini çıkar.");
    }
}
