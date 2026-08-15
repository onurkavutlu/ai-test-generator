package com.testgen.metrics;

import com.testgen.generator.TestCaseClassifier;
import com.testgen.model.TestCategory;
import com.testgen.model.TestFramework;
import com.testgen.model.TestRunStatus;
import com.testgen.model.ValidationStatus;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * Uygulamaya özgü Prometheus metrikleri.
 *
 * Neden bu metrikler: ajan ölçüm koşumu (benchmark) pahalı ve seyrek çalışan bir KARAR
 * aracıdır; sürekli izlenmesi gereken şey ise her üretimde bedava ve deterministik
 * olarak elde edilen kalite sinyalidir — üretilen test makine doğrulamasından geçti mi?
 *
 * Tüm kayıtlar best-effort: metrik hatası iş akışını asla durdurmaz.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TestGenMetrics {

    public static final String GENERATED_CASES   = "testgen_generated_cases_total";
    public static final String GENERATION_TIME   = "testgen_generation_duration";
    public static final String TEST_RUNS         = "testgen_test_runs_total";
    public static final String TEST_RUN_TIME     = "testgen_test_run_duration";
    public static final String SCENARIOS         = "testgen_scenarios_total";
    public static final String LLM_CALLS         = "testgen_llm_calls_total";
    public static final String LLM_CALL_TIME     = "testgen_llm_call_duration";
    public static final String LLM_PROMPT_CHARS  = "testgen_llm_prompt_chars_total";

    private final MeterRegistry registry;

    /**
     * Üretilen bir test case'in doğrulama sonucu.
     * Kalite oranı bu sayaçtan türetilir:
     *   sum(rate(testgen_generated_cases_total{validation_status="VALID"}[1h]))
     *     / sum(rate(testgen_generated_cases_total[1h]))
     */
    public void recordValidation(TestFramework framework, ValidationStatus status) {
        safely(() -> registry.counter(GENERATED_CASES,
                "framework", tag(framework),
                "validation_status", tag(status)).increment());
    }

    /**
     * Bir üretim isteğinin toplam süresi ve ürettiği case sayısı.
     *
     * Case sayısı gauge değil DistributionSummary: gauge son değeri tutar ve
     * Micrometer'da zayıf referansla saklanır — dağılım bilgisi kaybolurdu.
     */
    public void recordGeneration(TestFramework framework, long durationMs, int caseCount) {
        safely(() -> {
            Timer.builder(GENERATION_TIME)
                    .tag("framework", tag(framework))
                    .register(registry)
                    .record(Duration.ofMillis(Math.max(0, durationMs)));
            DistributionSummary.builder("testgen_generation_cases")
                    .tag("framework", tag(framework))
                    .register(registry)
                    .record(Math.max(0, caseCount));
        });
    }

    /**
     * Bir test case koşumunun sonucu, süresi ve içindeki senaryoların dağılımı.
     *
     * İKİ AYRI GRANÜLARİTE bilerek kaydedilir:
     *  - TEST_RUNS: case bazında. Bir case'in TEK senaryosu bile düşse case FAILED'dir.
     *  - SCENARIOS: senaryo bazında. 10 senaryodan 5'i geçen case burada 5 passed / 5 failed olur.
     *
     * Yalnızca case bazında ölçmek geçme oranını olduğundan kötü gösterir; yalnızca senaryo
     * bazında ölçmek ise "hiçbir case tam geçmiyor" gerçeğini gizler. İkisi birlikte anlamlıdır.
     */
    public void recordTestRun(TestFramework framework, TestRunStatus status, long durationMs,
                              int passedScenarios, int failedScenarios) {
        recordTestRun(framework, status, durationMs, passedScenarios, failedScenarios, null, false);
    }

    /**
     * @param category     ISTQB kategorisi — fonksiyonel/fonksiyonel olmayan ayrımı buradan gelir
     * @param deterministic içerik LLM'den mi gözlemden mi üretildi
     *
     * NEDEN BU İKİ ETİKET: Hangi üretim stratejisinin işe yaradığı ancak sonuçlar sınıf
     * bazında ayrıldığında görülebilir. Ölçümde LLM üretimi testlerin tamamı düşerken
     * gözlemden üretilenler geçiyordu — bu fark tek bir toplam sayının içinde kayboluyordu.
     */
    public void recordTestRun(TestFramework framework, TestRunStatus status, long durationMs,
                              int passedScenarios, int failedScenarios,
                              TestCategory category, boolean deterministic) {
        safely(() -> {
            String testClass = TestCaseClassifier.isNonFunctional(category)
                    ? "non_functional" : "functional";
            String source = deterministic ? "observed" : "llm";

            registry.counter(TEST_RUNS,
                    "framework", tag(framework),
                    "run_status", tag(status),
                    "test_class", testClass,
                    "source", source).increment();
            Timer.builder(TEST_RUN_TIME)
                    .tag("framework", tag(framework))
                    .register(registry)
                    .record(Duration.ofMillis(Math.max(0, durationMs)));

            // 0 artış Micrometer'da seriyi yine de oluşturur — panelde "veri yok" yerine 0 görünür.
            registry.counter(SCENARIOS, "framework", tag(framework), "outcome", "passed",
                            "test_class", testClass, "source", source)
                    .increment(Math.max(0, passedScenarios));
            registry.counter(SCENARIOS, "framework", tag(framework), "outcome", "failed",
                            "test_class", testClass, "source", source)
                    .increment(Math.max(0, failedScenarios));
        });
    }

    /**
     * Bir LLM çağrısı — tip bazında (KARATE, SELENIUM, AGENT_*, VALIDATION_REPAIR …).
     * Ajan katmanının maliyeti bu metrikten izlenir:
     *   sum by (call_type) (rate(testgen_llm_call_duration_seconds_sum[1h]))
     */
    public void recordLlmCall(String callType, boolean success, long durationMs, int promptChars) {
        safely(() -> {
            String type = callType == null || callType.isBlank() ? "UNKNOWN" : callType;
            registry.counter(LLM_CALLS,
                    "call_type", type,
                    "outcome", success ? "success" : "failure").increment();
            Timer.builder(LLM_CALL_TIME)
                    .tag("call_type", type)
                    .register(registry)
                    .record(Duration.ofMillis(Math.max(0, durationMs)));
            registry.counter(LLM_PROMPT_CHARS, "call_type", type).increment(Math.max(0, promptChars));
        });
    }

    private static String tag(Enum<?> value) {
        return value == null ? "unknown" : value.name().toLowerCase(Locale.ROOT);
    }

    /** Metrik kaydı hiçbir zaman iş akışını bozmamalı. */
    private void safely(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.debug("Metrik kaydedilemedi: {}", e.getMessage());
        }
    }
}
