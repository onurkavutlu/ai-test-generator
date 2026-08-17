package com.testgen.service;

import com.testgen.model.FrontendFlowLearning;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestType;
import com.testgen.repository.FrontendFlowLearningRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class FrontendFlowLearningServiceTest {

    private final FrontendFlowLearningRepository repository = mock(FrontendFlowLearningRepository.class);
    private final FrontendFlowLearningService service = new FrontendFlowLearningService(repository);

    @Test
    void recordsObservedFlowWithSourceRequestId() {
        TestGenerationRequest request = request("request-42", "https://www.vodafone.com.tr/net/redbox",
                "## OBSERVED PAGE\n## OBSERVED USER FLOW\n1. tıkla: 5G RedBox | locator: visible link text '5G RedBox' | sonuç: URL=x\n## OBSERVED UI CONTRACT");
        when(repository.existsByRequestIdAndObservedFlow(eq("request-42"), any())).thenReturn(false);

        service.record(request);

        ArgumentCaptor<FrontendFlowLearning> saved = ArgumentCaptor.forClass(FrontendFlowLearning.class);
        verify(repository).save(saved.capture());
        assertEquals(request, saved.getValue().getRequest());
        assertEquals("https://www.vodafone.com.tr", saved.getValue().getServiceKey());
        assertTrue(saved.getValue().getObservedFlow().contains("visible link text '5G RedBox'"));
        assertTrue(!saved.getValue().getObservedFlow().contains("OBSERVED UI CONTRACT"));
    }

    @Test
    void addsOnlyPreviousSameOriginEvidenceToNewRequest() {
        TestGenerationRequest current = request("current", "https://www.vodafone.com.tr/net/redbox", "mevcut gözlem");
        TestGenerationRequest priorRequest = request("prior-11", "https://www.vodafone.com.tr/", "");
        FrontendFlowLearning prior = FrontendFlowLearning.builder().request(priorRequest)
                .serviceKey("https://www.vodafone.com.tr").userIntent("RedBox")
                .observedFlow("## OBSERVED USER FLOW\n1. tıkla: 5G RedBox").build();
        when(repository.findTop3ByServiceKeyAndRequestIdNotOrderByCreatedAtDesc(
                "https://www.vodafone.com.tr", "current")).thenReturn(List.of(prior));

        String context = service.enrichWithLearnedFlows(current);

        assertTrue(context.contains("## LEARNED FRONTEND FLOWS"));
        assertTrue(context.contains("Kaynak requestId: prior-11"));
        verify(repository).findTop3ByServiceKeyAndRequestIdNotOrderByCreatedAtDesc(
                "https://www.vodafone.com.tr", "current");
    }

    private static TestGenerationRequest request(String id, String url, String context) {
        return TestGenerationRequest.builder().id(id).applicationUrl(url).additionalContext(context)
                .userStory("RedBox akışı").testType(TestType.FRONTEND_WEB)
                .framework(TestFramework.SELENIUM).build();
    }
}
