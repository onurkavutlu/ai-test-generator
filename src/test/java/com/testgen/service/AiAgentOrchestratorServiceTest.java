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
        // Sira dogrulamasi ozet ajanini da icerdigi icin FULL mod
        org.springframework.test.util.ReflectionTestUtils.setField(service, "agentMode",
                com.testgen.agent.AgentRouting.Mode.FULL);

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
    @SuppressWarnings("unchecked")
    public void supervisorThatCallsNoToolFallsBackToSequentialAgents() {
        // llama3.1 canlıda istisna fırlatmadan, tool ÇAĞIRMAK yerine tool çağrısını
        // ANLATAN metin döndürebiliyor — bu durumda hiçbir ajan koşmaz ama sistem
        // başarılı görünürdü. Ajanlar tanımlıysa fallback devreye girmeli.
        ChatLanguageModel mockModel = Mockito.mock(ChatLanguageModel.class);
        Response<AiMessage> pseudoToolCall = Response.from(
                AiMessage.from("```json\n{ \"name\": \"askDeveloper\", \"parameters\": {} }\n```"),
                new TokenUsage(10, 10));
        when(mockModel.generate(anyList())).thenReturn(pseudoToolCall);
        when(mockModel.generate(anyList(), anyList())).thenReturn(pseudoToolCall);

        AiAgent developer = fixedAgent(AiAgentRole.DEVELOPER, "Developer Agent", "api kontrati");
        AiAgent report    = fixedAgent(AiAgentRole.REPORT, "Report Agent", "yonetici ozeti");

        AiAgentOrchestratorService service = new AiAgentOrchestratorService(
                List.of(developer, report), mockModel, Mockito.mock(AgentAnalysisRepository.class));

        String enriched = service.enrichAdditionalContext(backendRequest);

        assertTrue(enriched.contains("Fallback"), "ajan çağrılmadıysa fallback koşmalı");
        assertTrue(enriched.contains("api kontrati"));
        // LEAN (varsayilan): ozet ajani test uretimini etkilemedigi icin kosmaz
        assertTrue(!enriched.contains("yonetici ozeti"), "LEAN modda REPORT ajani kosmamali");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void leanModeRunsFewerAgentsThanFull() {
        // Ajan katmani KUCULTULDU ama yapi korundu: ayni siniflar, ayni zincir,
        // yalnizca cagrilan ajan sayisi azaldi.
        TestGenerationRequest frontend = TestGenerationRequest.builder()
                .testType(TestType.FRONTEND_WEB)
                .framework(TestFramework.SELENIUM)
                .build();

        var lean = com.testgen.agent.AgentRouting.resolve(frontend, com.testgen.agent.AgentRouting.Mode.LEAN);
        var full = com.testgen.agent.AgentRouting.resolve(frontend, com.testgen.agent.AgentRouting.Mode.FULL);

        assertEquals(3, lean.size(), "FRONTEND_WEB LEAN: PM + Analyst + Automation");
        assertEquals(6, full.size(), "FULL: + SecOps, Performance, Report");
        assertTrue(!lean.contains(AiAgentRole.REPORT), "ozet ajani LEAN'de kosmaz");
        assertTrue(full.contains(AiAgentRole.REPORT));
        // Zorunlu ajanlar her iki modda da korunur
        assertTrue(lean.containsAll(com.testgen.agent.AgentRouting.mandatory(frontend)));
        assertTrue(full.containsAll(com.testgen.agent.AgentRouting.mandatory(frontend)));
    }

    @Test
    public void routingPlanMatchesTheAgentsThatActuallyRun() {
        // Plan artik yalnizca bir oneri degil: kosan liste ile AYNI kaynaktan uretilir.
        AiAgentOrchestratorService service = new AiAgentOrchestratorService(
                List.of(), Mockito.mock(ChatLanguageModel.class), Mockito.mock(AgentAnalysisRepository.class));

        String plan = service.buildRoutingPlan(backendRequest);
        var running = com.testgen.agent.AgentRouting.resolve(backendRequest, com.testgen.agent.AgentRouting.Mode.LEAN);

        for (AiAgentRole role : running) {
            assertTrue(plan.contains(com.testgen.agent.AgentRouting.toolNameOf(role)),
                    "plan kosacak ajani icermeli: " + role);
        }
        assertTrue(plan.contains("ÇAĞIRMA: "), "kosmayacak ajanlar acikca yasaklanmali");
        assertTrue(plan.contains("askReportAgent"), "LEAN'de REPORT cagrilmamali olarak listelenmeli");
    }

    @Test
    public void routingPlanAdaptsToRequestType() {
        AiAgentOrchestratorService service =
                new AiAgentOrchestratorService(List.of(), Mockito.mock(ChatLanguageModel.class), Mockito.mock(AgentAnalysisRepository.class));

        // BACKEND_API + user story yok → PM gereksiz
        String backendPlan = service.buildRoutingPlan(backendRequest);
        assertTrue(backendPlan.contains("askDeveloper"));
        assertTrue(backendPlan.contains("ÇAĞIRMA: ") && backendPlan.contains("askProductManager"));

        // BACKEND_API + user story var → PM zorunlu
        TestGenerationRequest withStory = TestGenerationRequest.builder()
                .testType(TestType.BACKEND_API)
                .framework(TestFramework.KARATE)
                .userStory("Kullanıcı pet ekleyebilmeli")
                .build();
        assertTrue(service.buildRoutingPlan(withStory).contains("askProductManager"));

        // FRONTEND_WEB → PM zorunlu, Developer gereksiz
        TestGenerationRequest frontend = TestGenerationRequest.builder()
                .testType(TestType.FRONTEND_WEB)
                .framework(TestFramework.SELENIUM)
                .build();
        String frontendPlan = service.buildRoutingPlan(frontend);
        assertTrue(frontendPlan.contains("askProductManager"));
        assertTrue(frontendPlan.contains("ÇAĞIRMA: ") && frontendPlan.contains("askDeveloper"));

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

    @Test
    @SuppressWarnings("unchecked")
    public void supervisorCannotCallAgentsOutsideThePlan() {
        // CANLIDA OLCULDU: LEAN modda plan "cagirma" dedigi halde model askReportAgent'i
        // yine cagirdi. Plan artik tool yolunda da baglayici.
        AiAgent developer = fixedAgent(AiAgentRole.DEVELOPER, "Developer Agent", "api kontrati");
        AiAgent report    = fixedAgent(AiAgentRole.REPORT, "Report Agent", "yonetici ozeti");
        AiAgentContext context = new AiAgentContext(backendRequest);

        var allowed = com.testgen.agent.AgentRouting.resolve(
                backendRequest, com.testgen.agent.AgentRouting.Mode.LEAN);
        com.testgen.agent.AgentTools tools = new com.testgen.agent.AgentTools(
                backendRequest, List.of(developer, report), context, allowed);

        String devOut = tools.askDeveloper("kontrat");
        String reportOut = tools.askReportAgent("ozet");

        assertEquals("api kontrati", devOut, "plandaki ajan normal kosmali");
        assertTrue(reportOut.contains("çağrılmayacak"), "plan disi ajan kosturulmamali");
        assertEquals(1, context.results().size(), "yalnizca plandaki ajan baglama eklenmeli");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void sameAgentIsNotRunTwice() {
        AiAgent developer = fixedAgent(AiAgentRole.DEVELOPER, "Developer Agent", "api kontrati");
        AiAgentContext context = new AiAgentContext(backendRequest);
        com.testgen.agent.AgentTools tools = new com.testgen.agent.AgentTools(
                backendRequest, List.of(developer), context,
                com.testgen.agent.AgentRouting.resolve(backendRequest,
                        com.testgen.agent.AgentRouting.Mode.LEAN));

        tools.askDeveloper("bir");
        String ikinci = tools.askDeveloper("iki");

        assertTrue(ikinci.contains("zaten çağrıldı"));
        assertEquals(1, context.results().size(), "tekrar cagri maliyet uretmemeli");
    }
}
