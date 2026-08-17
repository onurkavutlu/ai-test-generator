package com.testgen.generator;

import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Framework secimini tek noktada yapan, baslangicta eksik veya cift kaydi reddeden registry.
 */
@Component
public class FrameworkTestGeneratorRegistry {

    private final Map<TestFramework, FrameworkTestGenerator> generators;

    public FrameworkTestGeneratorRegistry(List<FrameworkTestGenerator> discoveredGenerators) {
        Objects.requireNonNull(discoveredGenerators, "discoveredGenerators");

        EnumMap<TestFramework, FrameworkTestGenerator> registered = new EnumMap<>(TestFramework.class);
        for (FrameworkTestGenerator generator : discoveredGenerators) {
            if (generator == null || generator.framework() == null) {
                throw new IllegalStateException("Framework generator kaydi ve framework degeri null olamaz.");
            }
            FrameworkTestGenerator previous = registered.putIfAbsent(generator.framework(), generator);
            if (previous != null) {
                throw new IllegalStateException("Ayni framework icin birden fazla generator kayitli: "
                        + generator.framework());
            }
        }

        EnumSet<TestFramework> missing = EnumSet.allOf(TestFramework.class);
        missing.removeAll(registered.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Generator kaydi eksik framework'ler: " + missing);
        }

        this.generators = Map.copyOf(registered);
    }

    public List<GeneratedTestCase> generate(TestGenerationRequest request) {
        Objects.requireNonNull(request, "request");
        TestFramework framework = Objects.requireNonNull(request.getFramework(), "request.framework");
        return generators.get(framework).generate(request);
    }

    /**
     * Framework-bağımsız planlama katmanının, somut generator sınıflarını bilmeden
     * bir framework yeteneğini doğrulamasını sağlar.
     */
    public boolean supports(TestFramework framework) {
        return framework != null && generators.containsKey(framework);
    }
}
