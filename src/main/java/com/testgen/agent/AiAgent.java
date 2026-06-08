package com.testgen.agent;

public interface AiAgent {
    AiAgentRole role();
    AiAgentResult analyze(AiAgentContext context);
}
