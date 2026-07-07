package com.testgen.service;

import com.testgen.agent.AgentTools;
import com.testgen.agent.AiAgent;
import com.testgen.agent.AiAgentContext;
import com.testgen.agent.AiAgentResult;
import com.testgen.agent.AiAgentRole;
import com.testgen.agent.SupervisorAgent;
import com.testgen.model.AgentAnalysis;
import com.testgen.model.TestGenerationRequest;
import com.testgen.repository.AgentAnalysisRepository;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class AiAgentOrchestratorService {

    public static final String SECTION_TITLE = "## AI AGENT ANALYSIS";

    private final List<AiAgent> agents;
    private final ChatLanguageModel chatModel;
    private final AgentAnalysisRepository agentAnalysisRepository;

    public AiAgentOrchestratorService(List<AiAgent> agents, ChatLanguageModel chatModel,
                                      AgentAnalysisRepository agentAnalysisRepository) {
        this.agents = agents;
        this.chatModel = chatModel;
        this.agentAnalysisRepository = agentAnalysisRepository;
    }

    public String enrichAdditionalContext(TestGenerationRequest request) {
        String existingContext = request.getAdditionalContext() == null ? "" : request.getAdditionalContext().trim();
        if (existingContext.contains(SECTION_TITLE)) {
            return existingContext;
        }

        AiAgentContext context = new AiAgentContext(request);

        // AiServices ile Langchain4j native Tool Calling kullanımı
        AgentTools tools = new AgentTools(request, agents, context);
        SupervisorAgent supervisor = AiServices.builder(SupervisorAgent.class)
                .chatLanguageModel(chatModel)
                .tools(tools)
                .build();

        String taskDescription = String.format("""
                Lütfen bu test isteği için bir plan ve analiz oluştur.

                Kullanıcı Hikayesi: %s
                Framework: %s
                Test Tipi: %s
                Ekstra Bağlam: %s

                %s
                """,
                request.getUserStory() != null ? request.getUserStory() : "-",
                request.getFramework(),
                request.getTestType(),
                request.getAdditionalContext() != null ? request.getAdditionalContext() : "-",
                buildRoutingPlan(request));

        log.info("Supervisor Agent otonom süreci (Native Tool Calling ile) başlatılıyor...");
        String supervisorReport;
        try {
            supervisorReport = supervisor.orchestrateTask(taskDescription);
        } catch (Exception e) {
            // Kurumsal dayanıklılık: tool-calling desteklemeyen model / LLM kesintisi
            // sistemin tamamını durdurmasın — zorunlu ajanlar deterministik sırayla koşulur.
            log.warn("Supervisor tool-calling başarısız ({}) — deterministik sıralı fallback devrede.",
                    e.getMessage());
            supervisorReport = runSequentialFallback(request, context);
        }

        log.info("\n--------------------------------------------------\n" +
                 " 👑 SUPERVISOR NİHAİ RAPORU:\n" +
                 "--------------------------------------------------\n" +
                 "{}\n" +
                 "--------------------------------------------------",
                 supervisorReport);

        String finalContext = existingContext.isBlank() 
                ? SECTION_TITLE + "\n\n" + supervisorReport
                : existingContext + "\n\n" + SECTION_TITLE + "\n\n" + supervisorReport;

        String agentSection = context.toContextSection();
        if (!agentSection.isBlank()) {
            finalContext += "\n\n### DETAYLI AJAN ÇIKTILARI\n" + agentSection;
        }

        persistAnalyses(request, context);

        return finalContext;
    }

    /** Ajan çıktılarını sorgulanabilir şekilde agent_analyses tablosuna yazar (best-effort). */
    private void persistAnalyses(TestGenerationRequest request, AiAgentContext context) {
        if (request.getId() == null || context.results().isEmpty()) {
            return;
        }
        try {
            List<AgentAnalysis> rows = context.results().stream()
                    .map(r -> AgentAnalysis.builder()
                            .requestId(request.getId())
                            .role(r.role().name())
                            .title(r.title())
                            .output(r.output())
                            .build())
                    .toList();
            agentAnalysisRepository.saveAll(rows);
        } catch (Exception e) {
            log.warn("Ajan analizleri DB'ye yazılamadı: {}", e.getMessage());
        }
    }

    /**
     * Supervisor devre dışı kaldığında zorunlu ajanları yönlendirme planındaki
     * sırayla koşan deterministik fallback. Tek bir ajanın hatası zinciri durdurmaz.
     */
    private String runSequentialFallback(TestGenerationRequest request, AiAgentContext context) {
        boolean hasUserStory = request.getUserStory() != null && !request.getUserStory().isBlank();

        List<AiAgentRole> order = switch (request.getTestType()) {
            case BACKEND_API -> hasUserStory
                    ? List.of(AiAgentRole.PRODUCT_MANAGER, AiAgentRole.DEVELOPER,
                              AiAgentRole.AI_LLM_TEST_ANALYST, AiAgentRole.TEST_AUTOMATION,
                              AiAgentRole.SECOPS, AiAgentRole.PERFORMANCE, AiAgentRole.REPORT)
                    : List.of(AiAgentRole.DEVELOPER, AiAgentRole.AI_LLM_TEST_ANALYST,
                              AiAgentRole.TEST_AUTOMATION, AiAgentRole.SECOPS,
                              AiAgentRole.PERFORMANCE, AiAgentRole.REPORT);
            case FRONTEND_WEB -> List.of(AiAgentRole.PRODUCT_MANAGER, AiAgentRole.AI_LLM_TEST_ANALYST,
                              AiAgentRole.TEST_AUTOMATION, AiAgentRole.SECOPS,
                              AiAgentRole.PERFORMANCE, AiAgentRole.REPORT);
        };

        StringBuilder report = new StringBuilder("(Fallback: sıralı ajan koşumu — Supervisor devre dışı)\n");
        for (AiAgentRole role : order) {
            agents.stream().filter(a -> a.role() == role).findFirst().ifPresent(agent -> {
                try {
                    AiAgentResult result = agent.analyze(context);
                    context.addResult(result);
                    report.append("\n### ").append(result.title()).append("\n").append(result.output()).append("\n");
                } catch (Exception ex) {
                    log.warn("Fallback ajan hatası ({}): {} — zincir devam ediyor.", role, ex.getMessage());
                }
            });
        }
        return report.toString();
    }

    /**
     * İsteğin tipine göre Supervisor'ın hangi uzman ajanlara danışacağını belirler.
     * Amaç: alakasız ajan çağrılarını (ve LLM maliyetini/latency'yi) önlemek,
     * kritik uzmanlıkların atlanmamasını garanti etmek.
     */
    String buildRoutingPlan(TestGenerationRequest request) {
        boolean hasRawPayload = request.getRawPayload() != null && !request.getRawPayload().isBlank();
        boolean hasUserStory  = request.getUserStory() != null && !request.getUserStory().isBlank();

        StringBuilder plan = new StringBuilder("YÖNLENDİRME PLANI (bu plana uy):\n");

        switch (request.getTestType()) {
            case BACKEND_API -> {
                plan.append("- ZORUNLU: askDeveloper (API kontratı ve kısıtlar), askTestAnalyst (ISTQB stratejisi), ")
                    .append("askTestAutomation (Karate feature tasarımı), askSecOps (401/403, injection).\n");
                plan.append(hasUserStory
                        ? "- ZORUNLU: askProductManager (user story'den kabul kriterleri).\n"
                        : "- GEREKSİZ: askProductManager (user story yok, iş bağlamı çıkarılamaz).\n");
                plan.append("- ÖNERİLEN: askPerformance (SLA ve büyük payload senaryoları).\n");
                plan.append("- GEREKSİZ: askDevOps (pipeline analizi test içeriğini değiştirmez; sadece CI/CD sorusu varsa çağır).\n");
                if (hasRawPayload) {
                    plan.append("- NOT: Swagger yerine ham payload (")
                        .append(request.getPayloadType() != null ? request.getPayloadType() : "RAW")
                        .append(") verildi — askDeveloper'dan kontratı bu payload'dan çıkarmasını iste; endpoint uydurma.\n");
                }
            }
            case FRONTEND_WEB -> {
                plan.append("- ZORUNLU: askProductManager (kullanıcı yolculuğu ve kabul kriterleri), ")
                    .append("askTestAnalyst (UI test stratejisi), askTestAutomation (Selenium POM, selector kararlılığı).\n");
                plan.append("- ÖNERİLEN: askSecOps (XSS/form injection), askPerformance (sayfa yükleme süresi).\n");
                plan.append("- GEREKSİZ: askDeveloper (backend kontratı UI testinin odağı değil; API doğrulaması gerekiyorsa ")
                    .append("ayrı bir BACKEND_API isteği açılmasını raporda öner).\n");
            }
        }

        plan.append("- EN SON: askReportAgent (tüm çıktılar toplandıktan sonra, bir kez).\n");
        plan.append("- KURAL: Aynı ajanı birden fazla kez çağırma. GEREKSİZ işaretli ajanları çağırma.");
        return plan.toString();
    }
}
