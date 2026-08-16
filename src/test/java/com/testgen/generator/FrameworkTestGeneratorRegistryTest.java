package com.testgen.generator;

import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FrameworkTestGeneratorRegistryTest {

    @Test
    void routesRequestToItsFrameworkGenerator() {
        FrameworkTestGenerator karate = generator(TestFramework.KARATE);
        FrameworkTestGenerator restAssured = generator(TestFramework.REST_ASSURED);
        FrameworkTestGenerator selenium = generator(TestFramework.SELENIUM);
        FrameworkTestGeneratorRegistry registry = registry(karate, restAssured, selenium);

        TestGenerationRequest request = TestGenerationRequest.builder()
                .framework(TestFramework.REST_ASSURED)
                .build();
        List<GeneratedTestCase> expected = List.of(GeneratedTestCase.builder()
                .testName("ContractTest")
                .framework(TestFramework.REST_ASSURED)
                .build());
        when(restAssured.generate(request)).thenReturn(expected);

        assertEquals(expected, registry.generate(request));
        verify(restAssured).generate(request);
    }

    @Test
    void rejectsMissingFrameworkRegistrationAtStartup() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> registry(generator(TestFramework.KARATE), generator(TestFramework.SELENIUM)));

        assertEquals("Generator kaydi eksik framework'ler: [REST_ASSURED]", error.getMessage());
    }

    @Test
    void rejectsDuplicateFrameworkRegistrationAtStartup() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> registry(generator(TestFramework.KARATE), generator(TestFramework.KARATE),
                        generator(TestFramework.REST_ASSURED), generator(TestFramework.SELENIUM)));

        assertEquals("Ayni framework icin birden fazla generator kayitli: KARATE", error.getMessage());
    }

    @Test
    void rejectsRequestWithoutFramework() {
        FrameworkTestGeneratorRegistry registry = registry(generator(TestFramework.KARATE),
                generator(TestFramework.REST_ASSURED), generator(TestFramework.SELENIUM));

        assertThrows(NullPointerException.class,
                () -> registry.generate(TestGenerationRequest.builder().build()));
    }

    private static FrameworkTestGenerator generator(TestFramework framework) {
        FrameworkTestGenerator generator = mock(FrameworkTestGenerator.class);
        when(generator.framework()).thenReturn(framework);
        return generator;
    }

    private static FrameworkTestGeneratorRegistry registry(FrameworkTestGenerator... generators) {
        return new FrameworkTestGeneratorRegistry(List.of(generators));
    }
}
