package com.testgen.metrics;

import com.testgen.model.TestFramework;
import com.testgen.model.TestRunStatus;
import com.testgen.model.ValidationStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Metrik sözleşmesi testi.
 *
 * Panellerin PromQL sorguları burada doğrulanan etiket adlarına ve değerlerine bağlıdır;
 * bir etiket sessizce değişirse dashboard "veri yok" gösterir ama uygulama çalışmaya devam eder.
 * Bu yüzden etiketler test edilir.
 */
class TestGenMetricsTest {

    private MeterRegistry registry;
    private TestGenMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new TestGenMetrics(registry);
    }

    @Test
    void caseBazliVeSenaryoBazliSayaclarAyriTutulur() {
        // 10 senaryodan 5'i geçen bir case: case bazında FAILED, senaryo bazında 5/5
        metrics.recordTestRun(TestFramework.KARATE, TestRunStatus.FAILED, 340, 5, 5);

        assertEquals(1.0, counter(TestGenMetrics.TEST_RUNS, "framework", "karate", "run_status", "failed"),
                "Case bazında tek bir başarısız koşum sayılmalı");
        assertEquals(5.0, counter(TestGenMetrics.SCENARIOS, "framework", "karate", "outcome", "passed"),
                "Geçen senaryolar case başarısız olsa da sayılmalı");
        assertEquals(5.0, counter(TestGenMetrics.SCENARIOS, "framework", "karate", "outcome", "failed"));
    }

    @Test
    void senaryoOraniCaseOranindanFarkliOlabilir() {
        // İki case, ikisi de FAILED; ama senaryoların yarısı geçiyor.
        metrics.recordTestRun(TestFramework.KARATE, TestRunStatus.FAILED, 300, 5, 5);
        metrics.recordTestRun(TestFramework.KARATE, TestRunStatus.FAILED, 300, 5, 5);

        // Hiç geçen case olmadığı için o seri HİÇ oluşmaz (0 değil, yok).
        // Panel sorguları bunu varsaymalı: sum(...) boş vektör döner, "0" değil.
        assertNull(registry.find(TestGenMetrics.TEST_RUNS)
                        .tags("framework", "karate", "run_status", "passed").counter(),
                "Geçen case olmadığında seri oluşmamalı — panel bunu 'veri yok' olarak görür");
        assertEquals(2.0, counter(TestGenMetrics.TEST_RUNS, "framework", "karate", "run_status", "failed"));
        assertEquals(10.0, counter(TestGenMetrics.SCENARIOS, "framework", "karate", "outcome", "passed"),
                "Senaryo seviyesinde 10 geçiş var — bu bilgi case bazlı sayaçta kayboluyordu");
    }

    @Test
    void negatifSenaryoSayisiSifiraKirpilir() {
        metrics.recordTestRun(TestFramework.SELENIUM, TestRunStatus.PASSED, 100, -3, -1);

        assertEquals(0.0, counter(TestGenMetrics.SCENARIOS, "framework", "selenium", "outcome", "passed"));
        assertEquals(0.0, counter(TestGenMetrics.SCENARIOS, "framework", "selenium", "outcome", "failed"));
    }

    @Test
    void dogrulamaSonucuKucukHarfEtiketleYazilir() {
        // Panel sorguları validation_status="valid" bekliyor — enum adı büyük harf olsa da.
        metrics.recordValidation(TestFramework.KARATE, ValidationStatus.VALID);
        metrics.recordValidation(TestFramework.KARATE, ValidationStatus.INVALID);

        assertEquals(1.0, counter(TestGenMetrics.GENERATED_CASES,
                "framework", "karate", "validation_status", "valid"));
        assertEquals(1.0, counter(TestGenMetrics.GENERATED_CASES,
                "framework", "karate", "validation_status", "invalid"));
    }

    @Test
    void llmCagrisiTipVeSonucaGoreAyrisir() {
        metrics.recordLlmCall("AGENT_DEVELOPER", true, 1200, 4697);
        metrics.recordLlmCall("KARATE", false, 800, 19135);

        assertEquals(1.0, counter(TestGenMetrics.LLM_CALLS, "call_type", "AGENT_DEVELOPER", "outcome", "success"));
        assertEquals(1.0, counter(TestGenMetrics.LLM_CALLS, "call_type", "KARATE", "outcome", "failure"));
        assertEquals(4697.0, counter(TestGenMetrics.LLM_PROMPT_CHARS, "call_type", "AGENT_DEVELOPER"));
    }

    @Test
    void bosCallTypeUnknownOlurVeCokmez() {
        metrics.recordLlmCall(null, true, 10, 5);
        metrics.recordLlmCall("   ", true, 10, 5);

        assertEquals(2.0, counter(TestGenMetrics.LLM_CALLS, "call_type", "UNKNOWN", "outcome", "success"));
    }

    @Test
    void metrikHatasiIsAkisiniDurdurmaz() {
        // Registry kapalıyken bile kayıt denemesi istisna fırlatmamalı.
        registry.close();
        assertDoesNotThrow(() -> metrics.recordTestRun(TestFramework.KARATE, TestRunStatus.PASSED, 1, 1, 0));
        assertDoesNotThrow(() -> metrics.recordGeneration(TestFramework.KARATE, 1, 1));
    }

    private double counter(String name, String... tags) {
        var c = registry.find(name).tags(tags).counter();
        return c == null ? Double.NaN : c.count();
    }
}
