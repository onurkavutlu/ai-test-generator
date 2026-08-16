package com.testgen.agent;

import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestType;

import java.util.ArrayList;
import java.util.List;

/**
 * Ajan yönlendirmesinin TEK doğruluk kaynağı.
 *
 * Hem Supervisor'a verilen yönlendirme planı metni hem de deterministik sıralı koşum
 * bu sınıftan türetilir. Önceden plan yalnızca prompt'ta bir "öneri"ydi; asıl koşan
 * fallback kendi sabit listesini kullanıyordu — yani plan pratikte bağlayıcı değildi.
 *
 * Katmanlar (yapı korunur, yalnızca çağrılan ajan SAYISI değişir):
 *   ZORUNLU  → test üretimi için çıktısı doğrudan kullanılan ajanlar
 *   ÖNERİLEN → değerli ama üretim için zorunlu olmayan ajanlar
 *   ÖZET     → yalnızca yönetici özeti üretir; test içeriğini etkilemez
 */
public final class AgentRouting {

    /** Ajan katmanının genişliği. */
    public enum Mode {
        /** Yalnızca ZORUNLU ajanlar koşar (varsayılan). */
        LEAN,
        /** ZORUNLU + ÖNERİLEN + ÖZET — önceki davranış. */
        FULL
    }

    private AgentRouting() {
    }

    /** Çıktısı test üretiminde doğrudan kullanılan ajanlar. */
    public static List<AiAgentRole> mandatory(TestGenerationRequest request) {
        boolean hasUserStory = request.getUserStory() != null && !request.getUserStory().isBlank();
        List<AiAgentRole> roles = new ArrayList<>();

        if (request.getTestType() == TestType.FRONTEND_WEB) {
            roles.add(AiAgentRole.PRODUCT_MANAGER);
            roles.add(AiAgentRole.AI_LLM_TEST_ANALYST);
            roles.add(AiAgentRole.TEST_AUTOMATION);
            return roles;
        }

        // BACKEND_API
        if (hasUserStory) {
            roles.add(AiAgentRole.PRODUCT_MANAGER);
        }
        roles.add(AiAgentRole.DEVELOPER);
        roles.add(AiAgentRole.AI_LLM_TEST_ANALYST);
        roles.add(AiAgentRole.TEST_AUTOMATION);
        roles.add(AiAgentRole.SECOPS);
        return roles;
    }

    /** Değerli ama üretim için zorunlu olmayan ajanlar. */
    public static List<AiAgentRole> recommended(TestGenerationRequest request) {
        return request.getTestType() == TestType.FRONTEND_WEB
                ? List.of(AiAgentRole.SECOPS, AiAgentRole.PERFORMANCE, AiAgentRole.DEVOPS)
                : List.of(AiAgentRole.PERFORMANCE, AiAgentRole.DEVOPS);
    }

    /** Bu istekte gerçekten koşacak ajanlar — koşum sırası korunur. */
    public static List<AiAgentRole> resolve(TestGenerationRequest request, Mode mode) {
        List<AiAgentRole> roles = new ArrayList<>(mandatory(request));
        if (mode == Mode.FULL) {
            recommended(request).stream().filter(r -> !roles.contains(r)).forEach(roles::add);
            roles.add(AiAgentRole.REPORT); // özet en sonda
        }
        return roles;
    }

    /** Supervisor'a verilen yönlendirme planı — koşacak ajan listesiyle AYNI kaynaktan üretilir. */
    public static String buildPlanText(TestGenerationRequest request, Mode mode) {
        List<AiAgentRole> active = resolve(request, mode);
        List<AiAgentRole> skipped = new ArrayList<>();
        for (AiAgentRole role : AiAgentRole.values()) {
            if (!active.contains(role)) {
                skipped.add(role);
            }
        }

        StringBuilder plan = new StringBuilder("YÖNLENDİRME PLANI (bu plana uy):\n");
        plan.append("- ZORUNLU: ")
            .append(String.join(", ", active.stream().map(AgentRouting::toolNameOf).toList()))
            .append("\n");
        if (!skipped.isEmpty()) {
            plan.append("- ÇAĞIRMA: ")
                .append(String.join(", ", skipped.stream().map(AgentRouting::toolNameOf).toList()))
                .append(" (bu istek için gereksiz)\n");
        }
        if (request.getRawPayload() != null && !request.getRawPayload().isBlank()) {
            plan.append("- NOT: Swagger yerine ham payload (")
                .append(request.getPayloadType() != null ? request.getPayloadType() : "RAW")
                .append(") verildi — kontratı bu payload'dan çıkar; endpoint uydurma.\n");
        }
        if (request.getAdditionalContext() != null
                && request.getAdditionalContext().contains("## OBSERVED")) {
            plan.append("- NOT: Bağlamda OBSERVED bölümü var — hedeften canlı toplanan GERÇEK veridir. ")
                .append("Analiz ve assertion önerilerini YALNIZCA bu gözlemlere dayandır; ")
                .append("gözlenmeyen alan/status/selector uydurma.\n");
        }
        plan.append("- KURAL: Aynı ajanı birden fazla kez çağırma. ÇAĞIRMA işaretli ajanları çağırma.");
        return plan.toString();
    }

    /** Rol → AgentTools üzerindeki tool adı. */
    public static String toolNameOf(AiAgentRole role) {
        return switch (role) {
            case PRODUCT_MANAGER      -> "askProductManager";
            case DEVELOPER            -> "askDeveloper";
            case AI_LLM_TEST_ANALYST  -> "askTestAnalyst";
            case TEST_AUTOMATION      -> "askTestAutomation";
            case PERFORMANCE          -> "askPerformance";
            case DEVOPS               -> "askDevOps";
            case SECOPS               -> "askSecOps";
            case REPORT               -> "askReportAgent";
        };
    }
}
