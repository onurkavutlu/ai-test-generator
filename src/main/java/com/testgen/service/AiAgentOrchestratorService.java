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

    /**
     * Ajan katmanının genişliği. LEAN (varsayılan) yalnızca çıktısı test üretiminde
     * doğrudan kullanılan ajanları koşar; FULL önceki davranışı geri getirir.
     */
    @org.springframework.beans.factory.annotation.Value("${test-generator.agents.mode:LEAN}")
    private com.testgen.agent.AgentRouting.Mode agentMode = com.testgen.agent.AgentRouting.Mode.LEAN;

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
        // Yönlendirme planı Supervisor için de bağlayıcı: plan dışı tool çağrısı koşturulmaz
        AgentTools tools = new AgentTools(request, agents, context,
                com.testgen.agent.AgentRouting.resolve(request, effectiveMode(request)));
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

        // Sessiz devre dışı kalma koruması: tool calling'i tam desteklemeyen modeller
        // (örn. llama3.1) istisna fırlatmadan, tool ÇAĞIRMAK yerine tool çağrısını
        // ANLATAN metin döndürebiliyor. Bu durumda hiçbir ajan koşmaz ama sistem
        // başarılı görünür — çok-ajanlı analiz sessizce kaybolur. Ajan tanımlı olduğu
        // hâlde hiçbiri çağrılmadıysa deterministik sıralı koşuma düşülür.
        if (!agents.isEmpty() && context.results().isEmpty()) {
            log.warn("Supervisor hiçbir ajanı çağırmadı (tool call yerine düz metin döndü) — "
                    + "deterministik sıralı fallback devrede.");
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
        // Yönlendirme TEK kaynaktan gelir: Supervisor'a verilen planla birebir aynı liste.
        // Önceden burada sabit bir liste vardı ve plan pratikte bağlayıcı değildi.
        List<AiAgentRole> order = com.testgen.agent.AgentRouting.resolve(request, effectiveMode(request));

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
     * Supervisor'a verilen yönlendirme planı.
     *
     * Plan ve gerçekte koşan ajan listesi AYNI kaynaktan ({@link com.testgen.agent.AgentRouting})
     * türetilir; böylece plan artık yalnızca bir öneri değil, bağlayıcı bir sözleşmedir.
     */
    String buildRoutingPlan(TestGenerationRequest request) {
        return com.testgen.agent.AgentRouting.buildPlanText(request, effectiveMode(request));
    }

    /**
     * Bu istek için geçerli ajan modu.
     * İstek üzerinde açıkça belirtilmişse o kullanılır (ölçüm koşumu LEAN/FULL kollarını
     * böyle ayırır); aksi hâlde konfigürasyondaki varsayılan geçerlidir.
     */
    private com.testgen.agent.AgentRouting.Mode effectiveMode(TestGenerationRequest request) {
        return request.getAgentMode() != null ? request.getAgentMode() : agentMode;
    }
}
