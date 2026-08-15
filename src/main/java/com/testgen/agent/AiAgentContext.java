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

    /** Bir sonraki ajana taşınacak en fazla önceki çıktı sayısı. */
    private static final int MAX_PREVIOUS_OUTPUTS = 3;
    /** Taşınan her çıktının en fazla karakter uzunluğu. */
    private static final int MAX_OUTPUT_CHARS = 1_200;

    /**
     * Bir sonraki ajana taşınacak bağlam — SINIRLI.
     *
     * Önceden tüm önceki çıktılar olduğu gibi taşınıyordu ve prompt zincir boyunca
     * büyüyordu (canlıda ölçüldü: ilk ajan ~1.300 karakter, son ajan ~12.900 karakter;
     * üretim prompt'u 30–33 bin karaktere çıkıyordu). Zincirleme yapısı korunur,
     * yalnızca taşınan hacim sınırlanır: en yakın {@value #MAX_PREVIOUS_OUTPUTS} çıktı,
     * her biri en fazla {@value #MAX_OUTPUT_CHARS} karakter.
     */
    public String previousOutputs() {
        if (results.isEmpty()) {
            return "No previous agent output.";
        }
        int from = Math.max(0, results.size() - MAX_PREVIOUS_OUTPUTS);
        List<AiAgentResult> recent = results.subList(from, results.size());

        String body = recent.stream()
                .map(result -> result.title() + ":\n" + truncate(result.output()))
                .collect(Collectors.joining("\n\n"));

        int omitted = results.size() - recent.size();
        return omitted == 0 ? body
                : "(" + omitted + " önceki ajan çıktısı özet dışı bırakıldı)\n\n" + body;
    }

    private static String truncate(String output) {
        if (output == null) {
            return "";
        }
        return output.length() <= MAX_OUTPUT_CHARS
                ? output
                : output.substring(0, MAX_OUTPUT_CHARS) + "…[kısaltıldı]";
    }

    public String toContextSection() {
        return results.stream()
                .map(result -> "### " + result.title() + "\n" + result.output())
                .collect(Collectors.joining("\n\n"));
    }
}
