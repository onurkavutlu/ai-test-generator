package com.testgen.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.io.IOException;

@RestController
@RequestMapping("/api/v1/logs")
public class LogController {

    private static final String LOG_FILE_PATH = "logs/application.log";

    @GetMapping
    public List<String> getSystemLogs() {
        Path path = Paths.get(LOG_FILE_PATH);
        if (!Files.exists(path)) {
            return Collections.singletonList("Log dosyası henüz oluşturulmadı (veya bulunamadı).");
        }

        try {
            List<String> allLines = Files.readAllLines(path);
            int start = Math.max(0, allLines.size() - 500); // Son 500 satır
            return allLines.subList(start, allLines.size());
        } catch (IOException e) {
            return Collections.singletonList("Log dosyası okunamadı: " + e.getMessage());
        }
    }
}
