package com.testgen.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sistem log ucu — dashboard'un "System Logs" sekmesini besler.
 *
 * <p>Bu uç göreli bir yola ({@code logs/application.log}) bakar ve dosya yoksa hata
 * DEĞİL açıklayıcı bir satır döner. Kritik davranış son 500 satır sınırı: sınır
 * kalkarsa günlerce çalışmış bir kurulumda yüzlerce MB'lık log tek yanıtta belleğe
 * alınır ve arayüzü kilitler.
 */
@WebLayerTest
class LogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private static final Path LOG_FILE = Path.of("logs/application.log");

    /** Testin yazdığı dosyayı geri alabilmek için önceki içerik saklanır. */
    private List<String> previousContent;
    private boolean existedBefore;

    private void writeLog(List<String> lines) throws IOException {
        existedBefore = Files.exists(LOG_FILE);
        if (existedBefore) {
            previousContent = Files.readAllLines(LOG_FILE);
        }
        Files.createDirectories(LOG_FILE.getParent());
        Files.write(LOG_FILE, lines);
    }

    @AfterEach
    void restoreLog() throws IOException {
        if (existedBefore && previousContent != null) {
            Files.write(LOG_FILE, previousContent);
        }
    }

    @Test
    @DisplayName("GET /api/v1/logs — log satırlarını döner")
    void returnsLogLines() throws Exception {
        writeLog(List.of("satir-1", "satir-2", "satir-3"));

        mockMvc.perform(get("/api/v1/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0]").value("satir-1"))
                .andExpect(jsonPath("$[2]").value("satir-3"));
    }

    /**
     * Sınır olmazsa uzun süre çalışmış bir kurulumda tüm log tek yanıtta döner ve
     * hem sunucu belleğini hem arayüzü boğar.
     */
    @Test
    @DisplayName("Yalnızca son 500 satır döner, tüm dosya değil")
    void returnsAtMostLastFiveHundredLines() throws Exception {
        List<String> many = new ArrayList<>(
                IntStream.rangeClosed(1, 700).mapToObj(i -> "satir-" + i).toList());
        writeLog(many);

        mockMvc.perform(get("/api/v1/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(500))
                // 700 satırın son 500'ü → 201'den başlar
                .andExpect(jsonPath("$[0]").value("satir-201"))
                .andExpect(jsonPath("$[499]").value("satir-700"));
    }

    @Test
    @DisplayName("500 satırdan az log varsa hepsi döner")
    void returnsAllWhenFewerThanLimit() throws Exception {
        writeLog(IntStream.rangeClosed(1, 10).mapToObj(i -> "satir-" + i).toList());

        mockMvc.perform(get("/api/v1/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10));
    }

    @Test
    @DisplayName("Boş log dosyası boş dizi döner, hata değil")
    void emptyLogFileReturnsEmptyArray() throws Exception {
        writeLog(List.of());

        mockMvc.perform(get("/api/v1/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * Log dosyası henüz oluşmamışken 500 dönmek yanlış olur — bu normal bir başlangıç
     * durumudur. Açıklayıcı tek satır dönmeli.
     */
    @Test
    @DisplayName("Log dosyası yokken açıklayıcı mesaj döner, hata değil")
    void missingLogFileReturnsExplanatoryMessage() throws Exception {
        boolean existed = Files.exists(LOG_FILE);
        List<String> backup = existed ? Files.readAllLines(LOG_FILE) : null;
        Path moved = Path.of("logs/application.log.testbackup");
        if (existed) {
            Files.move(LOG_FILE, moved, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            mockMvc.perform(get("/api/v1/logs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0]").value(containsString("Log dosyası")));
        } finally {
            if (existed) {
                Files.move(moved, LOG_FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.write(LOG_FILE, backup);
            }
        }
    }
}
