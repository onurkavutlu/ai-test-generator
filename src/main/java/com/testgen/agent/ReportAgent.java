package com.testgen.agent;

import com.testgen.llm.LlmService;
import org.springframework.stereotype.Component;

@Component
public class ReportAgent extends AbstractLlmAgent {

    public ReportAgent(LlmService llmService) {
        super(llmService);
    }

    @Override
    public AiAgentRole role() {
        return AiAgentRole.REPORT;
    }

    @Override
    public AiAgentResult analyze(AiAgentContext context) {
        return runAgent(context, "Report Agent",
                "Tüm agent çıktılarını email/Allure özetine girecek kısa rapor formatına dönüştür.",
                """
                Final rapor her agent'ın ne yaptığını, test kapsamını, riskleri ve iyileştirme önerilerini kısa özetlemeli.
                Emailde Product, Test Automation, Performance, SecOps ve DevOps etkisi ayrı okunabilmeli.
                Başarısız testler için LLM iyileştirme adayları açıkça belirtilmeli.
                """);
    }
}
