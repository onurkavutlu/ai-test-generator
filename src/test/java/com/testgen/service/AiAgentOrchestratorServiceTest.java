package com.testgen.service;

import com.testgen.agent.AiAgent;
import com.testgen.agent.AiAgentContext;
import com.testgen.agent.AiAgentResult;
import com.testgen.agent.AiAgentRole;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestType;
import com.testgen.repository.AgentAnalysisRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

public class AiAgentOrchestratorServiceTest {

    private final TestGenerationRequest backendRequest = TestGenerationRequest.builder()
            .testType(TestType.BACKEND_API)
            .framework(TestFramework.KARATE)
            .additionalContext("base context")
            .build();

    @Test
    @SuppressWarnings("unchecked")
    public void supervisorReportIsAppendedToContext() {
        ChatLanguageModel mockModel = Mockito.mock(ChatLanguageModel.class);
        Response<AiMessage> response = Response.from(
                AiMessage.from("SUPERVISOR PLAN RAPORU"), new TokenUsage(10, 10));
        when(mockModel.generate(anyList())).thenReturn(response);
        when(mockModel.generate(anyList(), anyList())).thenReturn(response);

        AiAgentOrchestratorService service = new AiAgentOrchestratorService(List.of(), mockModel, Mockito.mock(AgentAnalysisRepository.class));

        String enriched = service.enrichAdditionalContext(backendRequest);

        assertTrue(enriched.contains("base context"));
        assertTrue(enriched.contains("## AI AGENT ANALYSIS"));
        assertTrue(enriched.contains("SUPERVISOR PLAN RAPORU"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void supervisorFailureFallsBackToSequentialAgents() {
        ChatLanguageModel mockModel = Mockito.mock(ChatLanguageModel.class);
        when(mockModel.generate(anyList())).thenThrow(new RuntimeException("tool calling desteklenmiyor"));
        when(mockModel.generate(anyList(), anyList())).thenThrow(new RuntimeException("tool calling desteklenmiyor"));

        AiAgent developer = fixedAgent(AiAgentRole.DEVELOPER, "Developer Agent", "api kontrati");
        AiAgent secOps    = fixedAgent(AiAgentRole.SECOPS, "SecOps Agent", "auth riski");
        AiAgent report    = fixedAgent(AiAgentRole.REPORT, "Report Agent", "yonetici ozeti");

        AiAgentOrchestratorService service =
                new AiAgentOrchestratorService(List.of(secOps, report, developer), mockModel, Mockito.mock(AgentAnalysisRepository.class));

        String enriched = service.enrichAdditionalContext(backendRequest);

        // Fallback koşumu ajan çıktılarının context'e sırayla eklenmesini garanti eder
        assertTrue(enriched.contains("Fallback"));
        assertTrue(enriched.contains("api kontrati"));
        assertTrue(enriched.contains("auth riski"));
        assertTrue(enriched.contains("yonetici ozeti"));
        assertTrue(enriched.indexOf("Developer Agent") < enriched.indexOf("SecOps Agent"));
        assertTrue(enriched.indexOf("SecOps Agent") < enriched.indexOf("Report Agent"));
    }

    @Test
    public void routingPlanAdaptsToRequestType() {
        AiAgentOrchestratorService service =
                new AiAgentOrchestratorService(List.of(), Mockito.mock(ChatLanguageModel.class), Mockito.mock(AgentAnalysisRepository.class));

        // BACKEND_API + user story yok → PM gereksiz
        String backendPlan = service.buildRoutingPlan(backendRequest);
        assertTrue(backendPlan.contains("ZORUNLU: askDeveloper"));
        assertTrue(backendPlan.contains("GEREKSİZ: askProductManager"));

        // BACKEND_API + user story var → PM zorunlu
        TestGenerationRequest withStory = TestGenerationRequest.builder()
                .testType(TestType.BACKEND_API)
                .framework(TestFramework.KARATE)
                .userStory("Kullanıcı pet ekleyebilmeli")
                .build();
        assertTrue(service.buildRoutingPlan(withStory).contains("ZORUNLU: askProductManager"));

        // FRONTEND_WEB → PM zorunlu, Developer gereksiz
        TestGenerationRequest frontend = TestGenerationRequest.builder()
                .testType(TestType.FRONTEND_WEB)
                .framework(TestFramework.SELENIUM)
                .build();
        String frontendPlan = service.buildRoutingPlan(frontend);
        assertTrue(frontendPlan.contains("ZORUNLU: askProductManager"));
        assertTrue(frontendPlan.contains("GEREKSİZ: askDeveloper"));

        // Raw payload notu
        TestGenerationRequest rawPayload = TestGenerationRequest.builder()
                .testType(TestType.BACKEND_API)
                .framework(TestFramework.KARATE)
                .rawPayload("curl -X GET https://api.example.com/pets")
                .payloadType("CURL")
                .build();
        assertTrue(service.buildRoutingPlan(rawPayload).contains("endpoint uydurma"));
    }

    @Test
    public void existingAnalysisSectionIsNotRegenerated() {
        ChatLanguageModel mockModel = Mockito.mock(ChatLanguageModel.class);
        AiAgentOrchestratorService service = new AiAgentOrchestratorService(List.of(), mockModel, Mockito.mock(AgentAnalysisRepository.class));

        TestGenerationRequest alreadyEnriched = TestGenerationRequest.builder()
                .testType(TestType.BACKEND_API)
                .framework(TestFramework.KARATE)
                .additionalContext("önceki bağlam\n\n## AI AGENT ANALYSIS\n\neski rapor")
                .build();

        String result = service.enrichAdditionalContext(alreadyEnriched);
        assertEquals(alreadyEnriched.getAdditionalContext().trim(), result);
        Mockito.verifyNoInteractions(mockModel);
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
