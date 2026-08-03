package com.testgen.agent;

import com.testgen.model.TestGenerationRequest;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class AgentTools {

    private final TestGenerationRequest request;
    private final List<AiAgent> agents;
    private final AiAgentContext context;

    @Tool("Ürün yöneticisi (Product Manager) ajanına danış. İş kuralları, kabul kriterleri ve riskler hakkında analiz talep et.")
    public String askProductManager(@P("Ajana iletilecek kısa analiz odağı") String focus) {
        return invokeAgent(AiAgentRole.PRODUCT_MANAGER, focus);
    }

    @Tool("Geliştirici (Developer) ajanına danış. Teknik kısıtlamalar, veritabanı kuralları ve API endpoint davranışları hakkında analiz talep et.")
    public String askDeveloper(@P("Ajana iletilecek kısa analiz odağı") String focus) {
        return invokeAgent(AiAgentRole.DEVELOPER, focus);
    }

    @Tool("Test Analisti (Test Analyst) ajanına danış. ISTQB standartlarına uygun pozitif/negatif test senaryo stratejisi oluşturmasını talep et.")
    public String askTestAnalyst(@P("Ajana iletilecek kısa analiz odağı") String focus) {
        return invokeAgent(AiAgentRole.AI_LLM_TEST_ANALYST, focus);
    }

    @Tool("Test Otomasyon (Test Automation) ajanına danış. Kullanılacak framework kod blokları ve otomasyon tasarımı hakkında analiz talep et.")
    public String askTestAutomation(@P("Ajana iletilecek kısa analiz odağı") String focus) {
        return invokeAgent(AiAgentRole.TEST_AUTOMATION, focus);
    }

    @Tool("Performans (Performance) ajanına danış. SLA metrikleri ve yük testi gereksinimlerini talep et.")
    public String askPerformance(@P("Ajana iletilecek kısa analiz odağı") String focus) {
        return invokeAgent(AiAgentRole.PERFORMANCE, focus);
    }

    @Tool("DevOps ajanına danış. CI/CD entegrasyonu, pipeline ayarları ve raporlama süreçleri hakkında analiz talep et.")
    public String askDevOps(@P("Ajana iletilecek kısa analiz odağı") String focus) {
        return invokeAgent(AiAgentRole.DEVOPS, focus);
    }

    @Tool("SecOps (Güvenlik) ajanına danış. Olası güvenlik zafiyetleri (OWASP, SQLi vs.) ve güvenlik test stratejisi hakkında analiz talep et.")
    public String askSecOps(@P("Ajana iletilecek kısa analiz odağı") String focus) {
        return invokeAgent(AiAgentRole.SECOPS, focus);
    }

    @Tool("Raporlama (Report) ajanına danış. Nihai test kapsam oranlarını ve yönetici özetini çıkarır.")
    public String askReportAgent(@P("Ajana iletilecek kısa analiz odağı") String focus) {
        return invokeAgent(AiAgentRole.REPORT, focus);
    }

    private String invokeAgent(AiAgentRole role, String focus) {
        log.info("Supervisor → {} ajanına danışıyor (odak: {})", role, focus);
        AiAgent agent = agents.stream()
                .filter(a -> a.role() == role)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Agent not found: " + role));
        
        AiAgentResult result = agent.analyze(context);
        context.addResult(result);
        return result.output();
    }
}
