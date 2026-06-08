package com.testgen.service;

import com.testgen.agent.AiAgent;
import com.testgen.agent.AiAgentContext;
import com.testgen.agent.AiAgentRole;
import com.testgen.agent.AiAgentResult;
import com.testgen.model.TestGenerationRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AiAgentOrchestratorService {

    public static final String SECTION_TITLE = "## AI AGENT ANALYSIS";

    private static final Map<AiAgentRole, Integer> ORDER = Map.of(
            AiAgentRole.PRODUCT_MANAGER, 10,
            AiAgentRole.DEVELOPER, 20,
            AiAgentRole.AI_LLM_TEST_ANALYST, 30,
            AiAgentRole.TEST_AUTOMATION, 40,
            AiAgentRole.PERFORMANCE, 50,
            AiAgentRole.DEVOPS, 60,
            AiAgentRole.SECOPS, 70,
            AiAgentRole.REPORT, 80
    );

    private final List<AiAgent> agents;

    public AiAgentOrchestratorService(List<AiAgent> agents) {
        this.agents = agents.stream()
                .sorted(Comparator.comparingInt(agent -> ORDER.getOrDefault(agent.role(), 999)))
                .toList();
    }

    public String enrichAdditionalContext(TestGenerationRequest request) {
        String existingContext = request.getAdditionalContext() == null ? "" : request.getAdditionalContext().trim();
        if (existingContext.contains(SECTION_TITLE)) {
            return existingContext;
        }

        AiAgentContext context = new AiAgentContext(request);
        for (AiAgent agent : agents) {
            log.info("AI agent çalışıyor - role: {}, requestType: {}, framework: {}",
                    agent.role(), request.getTestType(), request.getFramework());
            AiAgentResult result = agent.analyze(context);
            context.addResult(result);
            
            // Her agent'ın kendi analiz raporunu detaylı şekilde loga yazdır
            log.info("\n--------------------------------------------------\n" +
                     " 🤖 AGENT RAPORU: {}\n" +
                     "--------------------------------------------------\n" +
                     "{}\n" +
                     "--------------------------------------------------", 
                     result.title(), result.output());

            log.info("AI agent tamamlandı - role: {}, completedCount: {}",
                    agent.role(), context.results().size());
        }

        String agentSection = context.toContextSection();
        if (agentSection.isBlank()) {
            return existingContext;
        }

        if (existingContext.isBlank()) {
            return SECTION_TITLE + "\n" + agentSection;
        }
        return existingContext + "\n\n" + SECTION_TITLE + "\n" + agentSection;
    }
}
