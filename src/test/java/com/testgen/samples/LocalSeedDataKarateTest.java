package com.testgen.samples;

import com.intuit.karate.junit5.Karate;

public class LocalSeedDataKarateTest {

    @Karate.Test
    Karate runLocalSeedDataSamples() {
        return Karate.run(
                        "classpath:samples/local-seed-data.feature",
                        "classpath:samples/llm-generation-audit.feature",
                        "classpath:samples/improvement-report.feature"
                );
    }
}
