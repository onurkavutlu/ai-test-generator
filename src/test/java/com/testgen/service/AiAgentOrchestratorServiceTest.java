package com.testgen.service;

import com.testgen.agent.AiAgent;
import com.testgen.agent.AiAgentContext;
import com.testgen.agent.AiAgentResult;
import com.testgen.agent.AiAgentRole;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;


public class AiAgentOrchestratorServiceTest {

    @Test
    public void testAgentsRunInRoleOrderAndAppendContext() {
        AiAgent devOps = fixedAgent(AiAgentRole.DEVOPS, "DevOps Agent", "artifact plan");
        AiAgent product = fixedAgent(AiAgentRole.PRODUCT_MANAGER, "Product Manager Agent", "acceptance criteria");
        AiAgent secOps = fixedAgent(AiAgentRole.SECOPS, "SecOps Agent", "auth risk");

        AiAgentOrchestratorService service = new AiAgentOrchestratorService(List.of(devOps, secOps, product));

        TestGenerationRequest request = TestGenerationRequest.builder()
                .testType(TestType.BACKEND_API)
                .framework(TestFramework.KARATE)
                .additionalContext("base context")
                .build();

        String enriched = service.enrichAdditionalContext(request);

        assertTrue(enriched.contains("base context"));
        assertTrue(enriched.contains("## AI AGENT ANALYSIS"));
        assertTrue(enriched.indexOf("Product Manager Agent") < enriched.indexOf("DevOps Agent"));
        assertTrue(enriched.indexOf("DevOps Agent") < enriched.indexOf("SecOps Agent"));
    }

    private AiAgent fixedAgent(AiAgentRole role, String title, String output) {
        return new AiAgent() {
            @Override
            public AiAgentRole role() {
                return role;
            }

            @Override
            public AiAgentResult analyze(AiAgentContext context) {
                return new AiAgentResult(role, title, output);
            }
        };
    }
}
