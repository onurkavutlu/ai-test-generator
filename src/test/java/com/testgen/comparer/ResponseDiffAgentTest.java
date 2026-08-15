package com.testgen.comparer;

import com.testgen.llm.LlmService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class ResponseDiffAgentTest {

    private final LlmService llmService = Mockito.mock(LlmService.class);

    private ResponseDiffAgent agent(boolean enabled) {
        ResponseDiffAgent a = new ResponseDiffAgent(llmService);
        ReflectionTestUtils.setField(a, "enabled", enabled);
        return a;
    }

    private RequestComparisonResult identical() {
        return new RequestComparisonResult("ayni", "GET", "/health", 200, 200, true,
                1, 1, true, List.of(), List.of(), "{}", "{}", null, null);
    }

    private RequestComparisonResult different() {
        return new RequestComparisonResult("farkli", "GET", "/health", 200, 200, true,
                1, 1, false,
                List.of(new FieldDifference("/service", FieldDifference.DifferenceType.MISSING_IN_B,
                        "AI Test Generator", null)),
                List.of(), "{\"service\":\"x\"}", "{}", null, null);
    }

    private RequestComparisonResult errored() {
        return new RequestComparisonResult("hatali", "GET", "/health", 200, null, false,
                1, 0, false, List.of(), List.of(), null, null, null, "Connection refused");
    }

    @Test
    public void noDifferenceMeansNoLlmCall() {
        // Fark yoksa ajan hic cagrilmaz — sifir maliyet
        assertNull(agent(true).analyze(List.of(identical())));
        verifyNoInteractions(llmService);
    }

    @Test
    public void erroredRequestsAreNotAnalyzed() {
        // Hedefe ulasilamadiysa yorumlanacak govde yok
        assertNull(agent(true).analyze(List.of(errored())));
        verifyNoInteractions(llmService);
    }

    @Test
    public void disabledAgentNeverCallsLlm() {
        assertNull(agent(false).analyze(List.of(different())));
        verifyNoInteractions(llmService);
    }

    @Test
    public void differenceIsAnalyzedWithOnlyGivenFields() {
        when(llmService.generateTestCase(anyString(), anyString()))
                .thenReturn("/service | KIRICI | alan B'de yok\nSONUÇ: tuketiciyi bozar");

        String analysis = agent(true).analyze(List.of(different()));

        assertNotNull(analysis);
        assertTrue(analysis.contains("/service"));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        verify(llmService).generateTestCase(prompt.capture(), type.capture());

        assertEquals("AGENT_RESPONSE_DIFF", type.getValue(), "cagri tipi telemetride ayirt edilebilmeli");
        assertTrue(prompt.getValue().contains("/service"), "gercek fark prompt'ta olmali");
        assertTrue(prompt.getValue().contains("MISSING_IN_B"));
        assertTrue(prompt.getValue().contains("UYDURMA"), "uydurma yasagi prompt'ta olmali");
        assertTrue(prompt.getValue().contains("TEK BİR satır"), "alan basina tek etiket istenmeli");
    }

    @Test
    public void llmFailureDoesNotBreakComparison() {
        // Ajan opsiyoneldir: yorum uretilemezse karsilastirma sonucu yine donmeli
        when(llmService.generateTestCase(anyString(), anyString()))
                .thenThrow(new RuntimeException("ollama erisilemedi"));

        assertNull(agent(true).analyze(List.of(different())));
    }

    @Test
    public void blankLlmOutputIsTreatedAsNoAnalysis() {
        when(llmService.generateTestCase(anyString(), anyString())).thenReturn("   ");
        assertNull(agent(true).analyze(List.of(different())));
    }
}
