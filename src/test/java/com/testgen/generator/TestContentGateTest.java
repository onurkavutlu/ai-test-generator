package com.testgen.generator;

import com.testgen.llm.LlmService;
import com.testgen.metrics.TestGenMetrics;
import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.model.ValidationStatus;
import com.testgen.runner.GeneratedJavaTestProjectService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Üretim kapısı — her case DB'ye yazılmadan önce makine ile doğrulanır.
 *
 * <p>Bu sınıf projenin en önemli kalite mekanizması: doğrulama olmadan bozuk bir test
 * sessizce kaydedilir ve koşumda <b>"0/0 FAILED"</b> olarak keşfedilir; kullanıcı
 * hatanın üretimde mi koşumda mı olduğunu ayırt edemez.
 *
 * <p>Kilitlenen en ince davranış deterministik case istisnasıdır: gözlemden türetilmiş
 * içerik LLM onarımına SOKULMAMALI. Sınıfın kendi yorumu yaşanmış hatayı anlatıyor —
 * LLM, doğru olan bir sınıfa var olmayan import'lar ekleyip içeriği bozmuştu.
 */
class TestContentGateTest {

    private GeneratedTestValidator validator;
    private LlmService llmService;
    private TestContentGate gate;

    @BeforeEach
    void setUp() {
        validator = mock(GeneratedTestValidator.class);
        llmService = mock(LlmService.class);
        GeneratedJavaTestProjectService projectService = mock(GeneratedJavaTestProjectService.class);
        when(projectService.supportSourcesFor(any(), anyString())).thenReturn(List.of());

        gate = new TestContentGate(validator, llmService, projectService,
                new TestGenMetrics(new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(gate, "maxRetries", 2);
    }

    private GeneratedTestCase karateCase(String content) {
        return GeneratedTestCase.builder()
                .testName("GetPet")
                .fileName("GetPet.feature")
                .framework(TestFramework.KARATE)
                .testContent(content)
                .build();
    }

    private GeneratedTestValidator.ValidationResult valid() {
        return new GeneratedTestValidator.ValidationResult(ValidationStatus.VALID, null);
    }

    private GeneratedTestValidator.ValidationResult invalid(String error) {
        return new GeneratedTestValidator.ValidationResult(ValidationStatus.INVALID, error);
    }

    private GeneratedTestValidator.ValidationResult skipped(String reason) {
        return new GeneratedTestValidator.ValidationResult(ValidationStatus.SKIPPED, reason);
    }

    @Nested
    @DisplayName("Geçerli içerik")
    class ValidContent {

        @Test
        @DisplayName("Doğrulamayı geçen case onarıma sokulmaz")
        void validCaseIsNotRepaired() {
            when(validator.validate(any(), anyString(), anyString(), any())).thenReturn(valid());
            var testCase = karateCase("Feature: pets");

            gate.apply(testCase);

            assertEquals(ValidationStatus.VALID, testCase.getValidationStatus());
            assertEquals(0, testCase.getValidationAttempts());
            verify(llmService, never()).generateTestCase(anyString(), anyString());
        }

        @Test
        @DisplayName("Atlanan doğrulama INVALID sayılmaz")
        void skippedValidationIsNotInvalid() {
            when(validator.validate(any(), anyString(), anyString(), any()))
                    .thenReturn(skipped("bu framework için doğrulayıcı yok"));
            var testCase = karateCase("Feature: pets");

            gate.apply(testCase);

            assertEquals(ValidationStatus.SKIPPED, testCase.getValidationStatus());
            verify(llmService, never()).generateTestCase(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("Onarım döngüsü")
    class RepairLoop {

        /**
         * Stub sıralı kurgulanıyor (önce geçersiz, sonra geçerli): onarılan içerik
         * doğrulamaya girmeden önce CodeCleaner'dan geçtiği için içeriğe göre
         * eşleştirme kırılgan olurdu.
         */
        @Test
        @DisplayName("Geçersiz içerik LLM'e geri verilip düzeltilir")
        void invalidContentIsRepaired() {
            when(validator.validate(any(), anyString(), anyString(), any()))
                    .thenReturn(invalid("parse hatası"))
                    .thenReturn(valid());
            when(llmService.generateTestCase(anyString(), anyString()))
                    .thenReturn("Feature: duzeltilmis\n  Scenario: x\n    * print 'x'");

            var testCase = karateCase("Feature: bozuk");
            gate.apply(testCase);

            assertEquals(ValidationStatus.VALID, testCase.getValidationStatus());
            assertEquals(1, testCase.getValidationAttempts());
            assertTrue(testCase.getTestContent().contains("duzeltilmis"),
                    "Onarılan içerik case'e yazılmamış: " + testCase.getTestContent());
        }

        /**
         * Onarım denemesi sınırsız olamaz: her deneme bir LLM çağrısıdır ve sınırsız
         * bırakılırsa tek bozuk case üretim hattını tekeline alır.
         */
        @Test
        @DisplayName("Deneme sayısı sınırı aşılmaz")
        void respectsMaxRetries() {
            when(validator.validate(any(), anyString(), anyString(), any()))
                    .thenReturn(invalid("hep bozuk"));
            when(llmService.generateTestCase(anyString(), anyString()))
                    .thenReturn("Feature: yine bozuk");

            var testCase = karateCase("Feature: bozuk");
            gate.apply(testCase);

            assertEquals(2, testCase.getValidationAttempts(), "maxRetries aşılmış");
            verify(llmService, times(2)).generateTestCase(anyString(), anyString());
        }

        /**
         * Onarılamayan case INVALID işaretlenmeli — sessizce geçerli sayılırsa koşumda
         * "0/0 FAILED" olarak keşfedilir ve kök neden kaybolur.
         */
        @Test
        @DisplayName("Onarılamayan case INVALID olarak işaretlenir")
        void unrepairableCaseIsMarkedInvalid() {
            when(validator.validate(any(), anyString(), anyString(), any()))
                    .thenReturn(invalid("derleme hatası"));
            when(llmService.generateTestCase(anyString(), anyString())).thenReturn("hâlâ bozuk");

            var testCase = karateCase("Feature: bozuk");
            gate.apply(testCase);

            assertEquals(ValidationStatus.INVALID, testCase.getValidationStatus());
            assertTrue(testCase.getValidationError().contains("derleme hatası"));
        }

        @Test
        @DisplayName("LLM boş yanıt dönerse döngü kırılır, sonsuza gitmez")
        void blankRepairBreaksLoop() {
            when(validator.validate(any(), anyString(), anyString(), any()))
                    .thenReturn(invalid("bozuk"));
            when(llmService.generateTestCase(anyString(), anyString())).thenReturn("   ");

            var testCase = karateCase("Feature: bozuk");
            gate.apply(testCase);

            assertEquals(1, testCase.getValidationAttempts());
            verify(llmService, times(1)).generateTestCase(anyString(), anyString());
        }

        /**
         * LLM erişilemezse üretim tamamen durmamalı; case INVALID işaretlenip akış sürer.
         */
        @Test
        @DisplayName("Onarım sırasında LLM patlarsa case INVALID kalır, akış sürer")
        void llmFailureDuringRepairDoesNotPropagate() {
            when(validator.validate(any(), anyString(), anyString(), any()))
                    .thenReturn(invalid("bozuk"));
            when(llmService.generateTestCase(anyString(), anyString()))
                    .thenThrow(new RuntimeException("LLM kapalı"));

            var testCase = karateCase("Feature: bozuk");
            gate.apply(testCase);

            assertEquals(ValidationStatus.INVALID, testCase.getValidationStatus());
        }

        @Test
        @DisplayName("Onarım prompt'u makinenin ürettiği hatayı taşır")
        void repairPromptCarriesMachineError() {
            when(validator.validate(any(), anyString(), anyString(), any()))
                    .thenReturn(invalid("mismatched input 'B' expecting <EOF>"));
            when(llmService.generateTestCase(anyString(), anyString())).thenReturn("");

            gate.apply(karateCase("Feature: bozuk"));

            var captor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(llmService).generateTestCase(captor.capture(), eq("VALIDATION_REPAIR"));
            assertTrue(captor.getValue().contains("mismatched input"),
                    "Somut hata mesajı onarım prompt'una girmemiş");
        }
    }

    @Nested
    @DisplayName("Deterministik case istisnası")
    class DeterministicCases {

        /**
         * Kilitlenen en ince davranış: deterministik içeriğin değerleri GÖZLEMDEN gelir,
         * düzeltilecek bir yanı yoktur. Geçersizse bu bizim üretici hatamızdır ve görünür
         * kalmalıdır. Yaşanmış hata: LLM, doğru olan bir sınıfa var olmayan import'lar
         * ekleyip içeriği bozmuştu.
         */
        @Test
        @DisplayName("Geçersiz deterministik case LLM onarımına SOKULMAZ")
        void invalidDeterministicCaseIsNeverRepaired() {
            when(validator.validate(any(), anyString(), anyString(), any()))
                    .thenReturn(invalid("üretici hatası"));
            var testCase = karateCase("Feature: gözlemden");
            testCase.setDeterministic(true);

            gate.apply(testCase);

            assertEquals(ValidationStatus.INVALID, testCase.getValidationStatus());
            assertEquals(0, testCase.getValidationAttempts());
            verify(llmService, never()).generateTestCase(anyString(), anyString());
        }

        @Test
        @DisplayName("Geçerli deterministik case olduğu gibi bırakılır")
        void validDeterministicCasePassesThrough() {
            when(validator.validate(any(), anyString(), anyString(), any())).thenReturn(valid());
            var testCase = karateCase("Feature: gözlemden");
            testCase.setDeterministic(true);
            String original = testCase.getTestContent();

            gate.apply(testCase);

            assertEquals(ValidationStatus.VALID, testCase.getValidationStatus());
            assertEquals(original, testCase.getTestContent());
        }
    }

    @Nested
    @DisplayName("Savunmacı girdi")
    class DefensiveInput {

        @Test
        @DisplayName("null case çökme yaratmaz")
        void nullCaseIsIgnored() {
            gate.apply(null);
            verify(validator, never()).validate(any(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("Framework'ü olmayan case doğrulanmaz")
        void caseWithoutFrameworkIsSkipped() {
            var testCase = GeneratedTestCase.builder().testName("X").build();

            gate.apply(testCase);

            verify(validator, never()).validate(any(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("Doğrulama sonucu her durumda case'e yazılır")
        void validationResultIsAlwaysRecorded() {
            when(validator.validate(any(), anyString(), anyString(), any())).thenReturn(valid());
            var testCase = karateCase("Feature: pets");

            gate.apply(testCase);

            assertNotNull(testCase.getValidationStatus(),
                    "Doğrulama durumu yazılmamış — case'in geçip geçmediği bilinemez");
        }
    }
}
