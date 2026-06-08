package com.testgen.agent;

import com.testgen.llm.LlmService;
import org.springframework.stereotype.Component;

@Component
public class AiLlmTestAnalystAgent extends AbstractLlmAgent {

    public AiLlmTestAnalystAgent(LlmService llmService) {
        super(llmService);
    }

    @Override
    public AiAgentRole role() {
        return AiAgentRole.AI_LLM_TEST_ANALYST;
    }

    @Override
    public AiAgentResult analyze(AiAgentContext context) {
        return runAgent(context, "AI LLM Test Analyst Agent",
                "LLM test data stratejisi, varsayımlar ve hallucination guardrail'lerini çıkar.",
                """
                Test datası API, smoke, regression, negative, edge, security ve performance kapsamlarına ayrılmalı.
                Regression kapsamı birden fazla gerçekçi veri varyasyonu, negative kapsamı birden fazla hata varyasyonu içermeli.
                LLM varsayımları request context içinde açık yazılmalı; bilinmeyen endpoint/selector uydurulmamalı.
                Guardrail: framework uyumu, çalıştırılabilir kod ve deterministic assertion zorunlu olmalı.
                """);
    }
}
