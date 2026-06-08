package com.testgen.agent;

import com.testgen.model.TestGenerationRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class AiAgentContext {

    private final TestGenerationRequest request;
    private final List<AiAgentResult> results = new ArrayList<>();

    public AiAgentContext(TestGenerationRequest request) {
        this.request = request;
    }

    public TestGenerationRequest request() {
        return request;
    }

    public List<AiAgentResult> results() {
        return List.copyOf(results);
    }

    public void addResult(AiAgentResult result) {
        results.add(result);
    }

    public String previousOutputs() {
        if (results.isEmpty()) {
            return "No previous agent output.";
        }
        return results.stream()
                .map(result -> result.title() + ":\n" + result.output())
                .collect(Collectors.joining("\n\n"));
    }

    public String toContextSection() {
        return results.stream()
                .map(result -> "### " + result.title() + "\n" + result.output())
                .collect(Collectors.joining("\n\n"));
    }
}
