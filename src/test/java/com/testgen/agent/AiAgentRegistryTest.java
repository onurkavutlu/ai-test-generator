package com.testgen.agent;

import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiAgentRegistryTest {

    @Test
    void resolvesEveryRegisteredRoleDeterministically() {
        List<AiAgent> agents = allAgents();
        AiAgentRegistry registry = new AiAgentRegistry(agents);

        for (AiAgent agent : agents) {
            assertSame(agent, registry.required(agent.role()));
        }
    }

    @Test
    void rejectsMissingRoleAtStartup() {
        List<AiAgent> agents = allAgents();
        agents.removeIf(agent -> agent.role() == AiAgentRole.DEVOPS);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AiAgentRegistry(agents));

        assertEquals("Agent kaydı eksik roller: [DEVOPS]", error.getMessage());
    }

    @Test
    void rejectsDuplicateRoleAtStartup() {
        List<AiAgent> agents = allAgents();
        agents.add(agent(AiAgentRole.DEVELOPER));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AiAgentRegistry(agents));

        assertEquals("Aynı rol için birden fazla agent kayıtlı: DEVELOPER", error.getMessage());
    }

    @Test
    void rejectsNullAgentRegistration() {
        List<AiAgent> agents = new ArrayList<>(allAgents());
        agents.set(0, null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AiAgentRegistry(agents));

        assertEquals("Agent kaydı ve rolü null olamaz.", error.getMessage());
    }

    @Test
    void contextRejectsDuplicateRoleResult() {
        AiAgentContext context = new AiAgentContext(TestGenerationRequest.builder()
                .testType(TestType.BACKEND_API)
                .framework(TestFramework.KARATE)
                .build());
        AiAgentResult result = new AiAgentResult(AiAgentRole.DEVELOPER,
                "Developer Agent", "gerçek çıktı");
        context.addResult(result);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> context.addResult(result));

        assertEquals("Agent sonucu zaten kayıtlı: DEVELOPER", error.getMessage());
    }

    private static List<AiAgent> allAgents() {
        EnumMap<AiAgentRole, AiAgent> agents = new EnumMap<>(AiAgentRole.class);
        Arrays.stream(AiAgentRole.values()).forEach(role -> agents.put(role, agent(role)));
        return new ArrayList<>(agents.values());
    }

    private static AiAgent agent(AiAgentRole role) {
        return new AiAgent() {
            @Override
            public AiAgentRole role() {
                return role;
            }

            @Override
            public AiAgentResult analyze(AiAgentContext context) {
                return new AiAgentResult(role, role + " Agent", "çıktı");
            }
        };
    }
}
