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
                "Auth, input validation, sensitive data, OWASP ve risk kontrollerini çıkar.",
                """
                Security kapsamı unauthorized/forbidden, input validation, injection payload ve sensitive data masking kontrollerini içermeli.
                Negatif testler güvenlik riskini istismar etmeden contract seviyesinde doğrulamalı.
                Rapor risk seviyesini ve remediation ipuçlarını kısa şekilde göstermeli.
                """);
    }
}
