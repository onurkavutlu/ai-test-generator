package com.testgen.agent;

import com.testgen.llm.LlmService;
import org.springframework.stereotype.Component;

@Component
public class DeveloperAgent extends AbstractLlmAgent {

    public DeveloperAgent(LlmService llmService) {
        super(llmService);
    }

    @Override
    public AiAgentRole role() {
        return AiAgentRole.DEVELOPER;
    }

    @Override
    public AiAgentResult analyze(AiAgentContext context) {
        return runAgent(context, "Developer Agent",
                "Kod kontratı, entegrasyon noktaları, edge case ve assertion gereksinimlerini çıkar.",
                """
                API/UI kontratı status, response field, selector ve state transition seviyesinde doğrulanmalı.
                Edge case'ler null/empty input, boundary değer, duplicate request ve not-found durumlarını kapsamalı.
                Entegrasyon riski olan dış servisler mock veya test data ile deterministic hale getirilmeli.
                """);
    }
}
