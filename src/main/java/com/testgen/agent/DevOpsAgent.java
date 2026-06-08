package com.testgen.agent;

import com.testgen.llm.LlmService;
import org.springframework.stereotype.Component;

@Component
public class DevOpsAgent extends AbstractLlmAgent {

    public DevOpsAgent(LlmService llmService) {
        super(llmService);
    }

    @Override
    public AiAgentRole role() {
        return AiAgentRole.DEVOPS;
    }

    @Override
    public AiAgentResult analyze(AiAgentContext context) {
        return runAgent(context, "DevOps Agent",
                "Environment, pipeline, artifact, observability ve rapor gereksinimlerini çıkar.",
                """
                Koşum ortamı gerekli env var, browser/appium server ve mock servis bilgilerini açıkça taşımalı.
                Pipeline çıktıları test source, surefire/karate output, Allure result ve HTML raporu artifact olarak saklamalı.
                Health, log ve failure trend bilgileri rapor özetine bağlanmalı.
                """);
    }
}
