package com.testgen.runner;

import com.intuit.karate.Results;
import com.intuit.karate.Runner;
import com.testgen.model.GeneratedTestCase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Karate feature dosyalarını programatik olarak çalıştırır.
 * Karate Runner API kullanır – JUnit runner gerektirmez.
 */
@Slf4j
@Component
public class KarateRunner {

    @Value("${test-generator.output.karate-path}")
    private String karateOutputPath;

    @Value("${test-generator.runner.parallel-threads}")
    private int parallelThreads;

    public TestRunResult run(GeneratedTestCase testCase) {
        Path runDir = null;
        try {
            // Feature dosyasını geçici dizine yaz (gözden geçirilebilir "son üretim" kopyası)
            Path featureDir = Path.of(karateOutputPath);
            Files.createDirectories(featureDir);
            Files.writeString(featureDir.resolve(testCase.getFileName()), testCase.getTestContent());

            // Koşum, case'e özel izole bir dizinde yapılır. Aynı sabit rapor dizinini
            // paylaşan paralel koşumlar birbirinin karate-json dosyasını siliyor ve
            // FileNotFoundException ile "0/0 FAILED" gibi sahte sonuçlar üretiyordu.
            runDir = featureDir.resolve("runs").resolve(runKeyOf(testCase));
            Files.createDirectories(runDir);
            Path featureFile = runDir.resolve(testCase.getFileName());
            Files.writeString(featureFile, testCase.getTestContent());

            log.info("Karate feature çalıştırılıyor: {}", featureFile);

            long startMs = System.currentTimeMillis();

            // Karate Runner API ile çalıştır
            Results results = Runner.path(featureFile.toString())
                    .reportDir(runDir.resolve("karate-reports").toString())
                    .parallel(parallelThreads);

            long durationMs = System.currentTimeMillis() - startMs;

            int total   = results.getScenariosTotal();
            int failed  = results.getFailCount();
            int passed  = total - failed;
            boolean success = failed == 0;

            String output = String.format(
                    "Karate Results:\n  Scenarios: %d\n  Passed: %d\n  Failed: %d\n  Duration: %dms",
                    total, passed, failed, durationMs);

            if (results.getErrorMessages() != null && !results.getErrorMessages().isBlank()) {
                output += "\n\nErrors:\n" + results.getErrorMessages();
            }

            log.info("Karate tamamlandı - total: {}, passed: {}, failed: {}, süre: {}ms",
                    total, passed, failed, durationMs);
            return TestRunResult.of(success, output, total, passed, failed, durationMs);

        } catch (IOException e) {
            log.error("Karate feature dosyası yazılamadı", e);
            return TestRunResult.of(false, "Dosya yazma hatası: " + e.getMessage(), 0, 0, 0, 0);
        } catch (Exception e) {
            log.error("Karate çalıştırma hatası", e);
            return TestRunResult.of(false, "Karate hatası: " + e.getMessage(), 0, 0, 0, 0);
        } finally {
            deleteQuietly(runDir);
        }
    }

    /** Koşum dizini anahtarı: case id varsa o, yoksa dosya adı + thread (manuel/mock case'ler için). */
    private String runKeyOf(GeneratedTestCase testCase) {
        String base = testCase.getId() != null
                ? testCase.getId()
                : testCase.getFileName() + "-" + Thread.currentThread().getId();
        return base.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** İzole koşum dizinini temizler; başarısız olursa koşum sonucunu etkilemez. */
    private void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // geçici dosya kalması koşumu etkilemez
                }
            });
        } catch (IOException e) {
            log.debug("Karate koşum dizini temizlenemedi: {}", dir);
        }
    }
}
