package com.testgen.service;

import com.testgen.model.FrontendFlowLearning;
import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestType;
import com.testgen.repository.FrontendFlowLearningRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

/** Kalıcı, request-id izlenebilir frontend akış kanıtı deposu. */
@Slf4j
@Service
@RequiredArgsConstructor
public class FrontendFlowLearningService {

    public static final String OBSERVED_FLOW_TITLE = "## OBSERVED USER FLOW";
    public static final String LEARNED_FLOW_TITLE = "## LEARNED FRONTEND FLOWS";

    private final FrontendFlowLearningRepository repository;

    /** Aynı request içindeki gözlemi, kaynak request_id ile kalıcılaştırır. */
    public void record(TestGenerationRequest request) {
        if (request == null || request.getId() == null || request.getTestType() != TestType.FRONTEND_WEB) {
            return;
        }
        String flow = observedFlowOf(request.getAdditionalContext());
        if (flow == null) {
            return;
        }
        try {
            if (repository.existsByRequestIdAndObservedFlow(request.getId(), flow)) {
                return;
            }
            repository.save(FrontendFlowLearning.builder()
                    .request(request)
                    .serviceKey(serviceKeyOf(request.getApplicationUrl()))
                    .userIntent(compact(request.getUserStory(), 800))
                    .observedFlow(flow)
                    .build());
            log.info("🧭 Frontend akışı kaydedildi — requestId: {}, service: {}",
                    request.getId(), serviceKeyOf(request.getApplicationUrl()));
        } catch (Exception e) {
            // Öğrenim deposu üretimi engellemez; gözlem zaten request bağlamında durur.
            log.warn("Frontend akışı kaydedilemedi: {}", e.getMessage());
        }
    }

    /** Aynı origin için en fazla üç doğrulanmış akışı yeni LLM bağlamına ekler. */
    public String enrichWithLearnedFlows(TestGenerationRequest request) {
        String existing = request.getAdditionalContext() == null ? "" : request.getAdditionalContext();
        if (request.getTestType() != TestType.FRONTEND_WEB || existing.contains(LEARNED_FLOW_TITLE)) {
            return existing;
        }
        List<FrontendFlowLearning> flows = repository.findTop3ByServiceKeyAndRequestIdNotOrderByCreatedAtDesc(
                serviceKeyOf(request.getApplicationUrl()), request.getId());
        if (flows.isEmpty()) {
            return existing;
        }
        String section = LEARNED_FLOW_TITLE + "\n"
                + "Aynı origin'de önceki request'lerde gerçekten doğrulanmış akışlar. "
                + "Yalnız kullanıcının mevcut niyetiyle ilgili olanları kullan; yeni etkileşim UYDURMA:\n"
                + flows.stream().map(flow -> "### Kaynak requestId: " + flow.getRequest().getId() + "\n"
                        + flow.getObservedFlow()).collect(Collectors.joining("\n\n"));
        log.info("🧭 {} geçmiş frontend akışı prompt bağlamına eklendi — service: {}",
                flows.size(), serviceKeyOf(request.getApplicationUrl()));
        return existing.isBlank() ? section : existing + "\n\n" + section;
    }

    static String observedFlowOf(String context) {
        if (context == null) return null;
        int start = context.indexOf(OBSERVED_FLOW_TITLE);
        if (start < 0) return null;
        int nextSection = context.indexOf("\n## ", start + OBSERVED_FLOW_TITLE.length());
        String flow = (nextSection < 0 ? context.substring(start) : context.substring(start, nextSection)).trim();
        return flow.length() > OBSERVED_FLOW_TITLE.length() ? flow : null;
    }

    static String serviceKeyOf(String url) {
        try {
            URI uri = URI.create(url.trim());
            int port = uri.getPort();
            return uri.getScheme().toLowerCase() + "://" + uri.getHost().toLowerCase()
                    + (port < 0 ? "" : ":" + port);
        } catch (Exception e) {
            return "frontend:unknown";
        }
    }

    private static String compact(String value, int limit) {
        String result = value == null ? "(niyet belirtilmedi)" : value.replaceAll("\\s+", " ").trim();
        return result.length() <= limit ? result : result.substring(0, limit) + "…";
    }
}
