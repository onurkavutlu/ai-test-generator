package com.testgen.agent;

import com.testgen.llm.LlmService;
import org.springframework.stereotype.Component;

@Component
public class ProductManagerAgent extends AbstractLlmAgent {

    public ProductManagerAgent(LlmService llmService) {
        super(llmService);
    }

    @Override
    public AiAgentRole role() {
        return AiAgentRole.PRODUCT_MANAGER;
    }

    @Override
    public AiAgentResult analyze(AiAgentContext context) {
        return runAgent(context, "Product Manager Agent",
                "Acceptance criteria, business risk, müşteri etkisi ve öncelik çıkar.");
    }
}
