package com.testgen.orchestration;

import com.testgen.agent.AiAgentRole;

/** Zorunlu agent registry'de bulunmadığında kullanılan açık hata. */
public class AgentUnavailableException extends RuntimeException {
    public AgentUnavailableException(AiAgentRole role) {
        super("Zorunlu agent kullanılamıyor: " + role);
    }
}
