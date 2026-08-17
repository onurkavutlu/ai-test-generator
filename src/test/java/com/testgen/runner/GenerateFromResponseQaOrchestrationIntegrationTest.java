package com.testgen.runner;

import com.testgen.controller.WebLayerTest;
import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.orchestration.OrchestrationRequest;
import com.testgen.orchestration.QaOrchestrator;
import com.testgen.service.TestGenerationService;
import com.testgen.service.TestSuiteService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gerçek planlayıcı/registry'lerle web sınırı arasındaki dikey dilim sözleşmesi.
 * Ağ ve uzun üretim burada mock'ludur; planlama çekirdeği ise gerçek Spring bean'idir.
 */
@WebLayerTest
class GenerateFromResponseQaOrchestrationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DirectRequestService directRequestService;

    @MockitoBean
    private TestGenerationService testGenerationService;

    @MockitoBean
    private TestRunnerService testRunnerService;

    @MockitoBean
    private TestSuiteService testSuiteService;

    @MockitoSpyBean
    private QaOrchestrator qaOrchestrator;

    @Test
    void observedResponseIsPlannedBeforeTheExistingAcceptedEndpointContractIsReturned() throws Exception {
        when(directRequestService.execute(any())).thenReturn(new DirectRequestService.DirectRunResult(
                200, 42L, Map.of("Content-Type", "application/json"),
                "{\"id\":7,\"name\":\"Pamuk\"}", null, List.of()));
        TestGenerationRequest saved = TestGenerationRequest.builder().id("req-plan-1").build();
        when(testGenerationService.createRequest(any())).thenReturn(saved);
        when(testGenerationService.generateTests(anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.<GeneratedTestCase>of()));

        mockMvc.perform(post("/api/v1/runner/generate-from-response")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://localhost:9/api\",\"method\":\"GET\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requestId").value("req-plan-1"))
                .andExpect(jsonPath("$.observedStatus").value(200))
                .andExpect(jsonPath("$.observedLatencyMs").value(42))
                .andExpect(jsonPath("$.autoRun").value(true));

        ArgumentCaptor<OrchestrationRequest> planRequest = ArgumentCaptor.forClass(OrchestrationRequest.class);
        verify(qaOrchestrator).execute(planRequest.capture());
        assertEquals(TestFramework.KARATE, planRequest.getValue().framework());
        assertTrue(planRequest.getValue().agentsEnabled());

        ArgumentCaptor<TestGenerationRequest> generationRequest =
                ArgumentCaptor.forClass(TestGenerationRequest.class);
        verify(testGenerationService).createRequest(generationRequest.capture());
        assertTrue(generationRequest.getValue().getAdditionalContext().contains("OBSERVED RESPONSE"));
        assertTrue(generationRequest.getValue().getAdditionalContext().contains("Pamuk"));

        InOrder observationFirst = inOrder(directRequestService, qaOrchestrator, testGenerationService);
        observationFirst.verify(directRequestService).execute(any());
        observationFirst.verify(qaOrchestrator).execute(any());
        observationFirst.verify(testGenerationService).createRequest(any());
    }
}
