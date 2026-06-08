package com.testgen.agent;

import com.testgen.llm.LlmService;
import org.springframework.stereotype.Component;

@Component
public class PerformanceAgent extends AbstractLlmAgent {

    public PerformanceAgent(LlmService llmService) {
        super(llmService);
    }

    @Override
    public AiAgentRole role() {
        return AiAgentRole.PERFORMANCE;
    }

    @Override
    public AiAgentResult analyze(AiAgentContext context) {
        return runAgent(context, "Performance Agent",
                "Latency, throughput, boundary, retry ve yük profili senaryolarını çıkar.",
                """
                Performance kapsamı p95 latency, büyük payload, yüksek tutar ve eşzamanlı tekrar deneme senaryolarını içermeli.
                Smoke seviyesinde timeout ve response time assertion eklenmeli; ileri aşamada k6/JMeter/Gatling profili üretilebilir.
                Boundary veri setleri normal test datasıyla birlikte LLM'e verilerek regression kapsamına bağlanmalı.
                """);
    }
}
