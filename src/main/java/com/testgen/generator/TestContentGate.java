package com.testgen.generator;

import com.testgen.llm.LlmService;
import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.model.ValidationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Üretim kapısı: bir test case DB'ye yazılmadan önce makine ile doğrulanır.
 *
 * Akış:
 *   doğrula → geçerse bitti
 *          → geçmezse: parser/derleyici hatasını LLM'e GERİ VEREREK yeniden üret
 *          → yine geçmezse: içerik yine saklanır ama INVALID olarak işaretlenir
 *
 * Neden: önceden sözdizimi/derleme hataları ancak koşumda "0/0 FAILED" olarak
 * görülüyordu; burada üretim anında yakalanır ve hata makinenin kendi çıktısıyla
 * (tahminle değil) düzeltilmeye çalışılır.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TestContentGate {

    private final GeneratedTestValidator validator;
    private final LlmService llmService;
    private final com.testgen.runner.GeneratedJavaTestProjectService javaTestProjectService;
    private final com.testgen.metrics.TestGenMetrics metrics;

    @Value("${test-generator.generation.max-validation-retries:1}")
    private int maxRetries;

    /** Case'i doğrular, gerekiyorsa düzeltir ve doğrulama alanlarını case üzerine yazar. */
    public void apply(GeneratedTestCase testCase) {
        if (testCase == null || testCase.getFramework() == null) {
            return;
        }

        var result = validate(testCase, testCase.getTestContent());

        // Deterministik içerik LLM onarımına SOKULMAZ. Değerleri gözlemden geldiği için
        // düzeltilecek bir yanı yoktur; geçersizse bu BİZİM üretici hatamızdır ve
        // görünür kalmalıdır. Canlı koşumda LLM, doğru olan bir sınıfa var olmayan
        // import'lar ekleyip içeriği bozmuştu — bu kapı onu engeller.
        if (testCase.isDeterministic()) {
            testCase.setValidationStatus(result.status());
            testCase.setValidationError(result.error());
            testCase.setValidationAttempts(0);
            metrics.recordValidation(testCase.getFramework(), result.status());
            if (result.isInvalid()) {
                log.error("DETERMİNİSTİK case doğrulamayı geçemedi — bu bir ÜRETİCİ HATASIDIR, "
                        + "LLM onarımı uygulanmaz: {} → {}", testCase.getTestName(), firstLine(result.error()));
            }
            return;
        }

        int attempts = 0;
        while (result.isInvalid() && attempts < maxRetries) {
            attempts++;
            log.warn("Üretim doğrulaması başarısız ({}. deneme) — {}: {}",
                    attempts, testCase.getTestName(), firstLine(result.error()));

            // Onarım çağrısı üretimin içinden yapılır: requestId korunur, yalnızca faz
            // değişir. Böylece "hangi üretimde kaç kez doğrulama onarımı gerekti"
            // çağrı geçmişinden ölçülebilir.
            var previousScope = com.testgen.llm.LlmCallContext
                    .enterPhase(com.testgen.llm.LlmCallContext.Phase.VALIDATION_REPAIR);
            String repaired;
            try {
                repaired = regenerate(testCase, result.error());
            } finally {
                com.testgen.llm.LlmCallContext.restore(previousScope);
            }
            if (repaired == null || repaired.isBlank()) {
                break;
            }

            var next = validate(testCase, repaired);
            if (!next.isInvalid()) {
                testCase.setTestContent(repaired);
                result = next;
                log.info("Doğrulama {}. denemede geçti — {}", attempts, testCase.getTestName());
                break;
            }
            result = next;
        }

        testCase.setValidationStatus(result.status());
        testCase.setValidationError(result.error());
        testCase.setValidationAttempts(attempts);
        // Sürekli izlenen kalite sinyali — her üretimde bedava ve deterministik
        metrics.recordValidation(testCase.getFramework(), result.status());

        if (result.status() == ValidationStatus.INVALID) {
            log.error("Case doğrulamayı GEÇEMEDİ, koşulamaz olarak işaretlendi — {}: {}",
                    testCase.getTestName(), firstLine(result.error()));
        } else if (result.status() == ValidationStatus.SKIPPED) {
            log.debug("Doğrulama atlandı — {}: {}", testCase.getTestName(), firstLine(result.error()));
        }
    }

    /**
     * Doğrulamayı, testin kullandığı destek sınıflarıyla (Page Object'ler) birlikte yapar.
     * Aksi hâlde LLM'in aynı üretimde çıkardığı Page Object derlemeye girmez ve test
     * haksız yere geçersiz sayılır.
     */
    private GeneratedTestValidator.ValidationResult validate(GeneratedTestCase testCase, String content) {
        var support = javaTestProjectService.supportSourcesFor(testCase.getFramework(), content).stream()
                .map(s -> new GeneratedTestValidator.SupportSource(s.className(), s.content()))
                .toList();
        return validator.validate(testCase.getFramework(), testCase.getFileName(), content, support);
    }

    /**
     * Hatayı LLM'e geri vererek içeriği yeniden ürettirir.
     * Yalnızca makinenin ürettiği somut hata mesajı bağlam olarak verilir — yorum eklenmez.
     */
    private String regenerate(GeneratedTestCase testCase, String validationError) {
        try {
            String raw = llmService.generateTestCase(
                    buildRepairPrompt(testCase, validationError), "VALIDATION_REPAIR");
            return testCase.getFramework() == TestFramework.KARATE
                    ? CodeCleaner.cleanFeatureContent(raw)
                    : normalizeJava(testCase, raw);
        } catch (Exception e) {
            log.warn("Doğrulama onarımı için LLM çağrısı başarısız — {}: {}",
                    testCase.getTestName(), e.getMessage());
            return null;
        }
    }

    private String normalizeJava(GeneratedTestCase testCase, String raw) {
        String cleaned = CodeCleaner.cleanJavaContent(raw);
        return testCase.getFramework() == TestFramework.SELENIUM
                ? CodeCleaner.normalizeSeleniumTest(cleaned, testCase.getTestName())
                : CodeCleaner.normalizeRestAssuredTest(cleaned, testCase.getTestName());
    }

    private String buildRepairPrompt(GeneratedTestCase testCase, String validationError) {
        String kind = testCase.getFramework() == TestFramework.KARATE
                ? "Karate feature dosyası" : "Java (JUnit 5) test sınıfı";
        return """
                Aşağıdaki %s DERLENMİYOR / AYRIŞTIRILAMIYOR. Hata çıktısı makineden alınmıştır.

                ## Mevcut İçerik
                ```
                %s
                ```

                ## Makine Hatası
                ```
                %s
                ```

                ## Görev
                1. Yalnızca yukarıdaki hatayı gider; test kapsamını ve senaryoları DEĞİŞTİRME.
                2. Eksik import/tanım varsa ekle; olmayan sınıf, metot veya alan UYDURMA.
                3. Sınıf adı %s olarak kalmalı.
                4. Yalnızca düzeltilmiş içeriği döndür — açıklama, başlık veya markdown ekleme.
                """.formatted(kind,
                testCase.getTestContent(),
                validationError == null ? "(hata metni yok)" : validationError,
                testCase.getTestName());
    }

    private static String firstLine(String text) {
        if (text == null) return "(mesaj yok)";
        int nl = text.indexOf('\n');
        return nl > 0 ? text.substring(0, nl) : text;
    }
}
