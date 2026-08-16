package com.testgen.agent;

import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAgentContextTest {

    @Test
    void emptyContextHasExplicitPreviousOutputState() {
        AiAgentContext context = context();

        assertEquals("No previous agent output.", context.previousOutputs());
        assertEquals("", context.toContextSection());
        assertEquals(List.of(), context.results());
    }

    @Test
    void previousOutputsKeepsOnlyLastThreeAndTruncatesLongOutput() {
        AiAgentContext context = context();
        context.addResult(result(AiAgentRole.PRODUCT_MANAGER, "one", "first"));
        context.addResult(result(AiAgentRole.DEVELOPER, "two", "x".repeat(1_201)));
        context.addResult(result(AiAgentRole.AI_LLM_TEST_ANALYST, "three", null));
        context.addResult(result(AiAgentRole.TEST_AUTOMATION, "four", "last"));

        String previous = context.previousOutputs();

        assertTrue(previous.startsWith("(1 önceki ajan çıktısı özet dışı bırakıldı)"));
        assertTrue(!previous.contains("one:\nfirst"));
        assertTrue(previous.contains("two:\n" + "x".repeat(1_200) + "…[kısaltıldı]"));
        assertTrue(previous.contains("three:\n"));
        assertTrue(previous.endsWith("four:\nlast"));
    }

    @Test
    void resultViewIsImmutableAndContextSectionPreservesInsertionOrder() {
        AiAgentContext context = context();
        context.addResult(result(AiAgentRole.DEVELOPER, "Developer", "contract"));
        context.addResult(result(AiAgentRole.SECOPS, "SecOps", "auth"));

        assertEquals("### Developer\ncontract\n\n### SecOps\nauth", context.toContextSection());
        assertThrows(UnsupportedOperationException.class,
                () -> context.results().add(result(AiAgentRole.REPORT, "Report", "summary")));
    }

    private static AiAgentResult result(AiAgentRole role, String title, String output) {
        return new AiAgentResult(role, title, output);
    }

    private static AiAgentContext context() {
        return new AiAgentContext(TestGenerationRequest.builder()
                .testType(TestType.BACKEND_API)
                .framework(TestFramework.KARATE)
                .build());
    }
}
