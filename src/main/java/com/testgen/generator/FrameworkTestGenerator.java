package com.testgen.generator;

import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;

import java.util.List;

/**
 * Bir otomasyon framework'u icin test artifact'i ureten ortak sozlesme.
 *
 * <p>Application/service katmani somut Karate, REST Assured veya Selenium siniflarini
 * bilmez. Yeni bir framework bu sozlesmeyi uygulayip registry'ye kaydolarak eklenir.
 */
public interface FrameworkTestGenerator {

    /** Bu generator'in tek sahibi oldugu framework. */
    TestFramework framework();

    /** Verilen istekten calistirilabilir test case'leri uretir. */
    List<GeneratedTestCase> generate(TestGenerationRequest request);
}
