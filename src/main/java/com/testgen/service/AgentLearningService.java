package com.testgen.service;

import com.testgen.model.AgentLearning;
import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestGenerationRequest;
import com.testgen.repository.AgentLearningRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Servis bazlı öğrenme deposu — halüsinasyon azaltma mekanizması.
 *
 * Akış:
 *  1. Koşum hataları ve self-healing düzeltmeleri ders olarak kaydedilir (serviceKey = hedef host).
 *  2. Aynı servise yönelik yeni test üretimlerinde son dersler prompt bağlamına enjekte edilir.
 *  3. LLM aynı hatalı varsayımı (yanlış status, olmayan alan, kırılgan selector) tekrarlamaz.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentLearningService {

    public static final String SECTION_TITLE = "## SERVICE LEARNINGS";

    private static final int MAX_LESSON_CHARS = 400;
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\"']+");

    private final AgentLearningRepository repository;

    // ─────────────────────────────────────────────────────────
    // Kayıt
    // ─────────────────────────────────────────────────────────

    public void recordRunFailure(TestGenerationRequest request, GeneratedTestCase failedCase) {
        String error = failedCase.getRunOutput() != null ? failedCase.getRunOutput() : "(çıktı yok)";
        String lesson = "Test '%s' bu serviste başarısız oldu. Hata özeti: %s".formatted(
                failedCase.getTestName(), truncate(error));
        save(request, AgentLearning.Source.RUN_FAILURE, lesson, failedCase.getTestName());
    }

    public void recordSelfHeal(TestGenerationRequest request, GeneratedTestCase failedCase, int healVersion) {
        String error = failedCase.getRunOutput() != null ? failedCase.getRunOutput() : "(çıktı yok)";
        String lesson = ("Test '%s' LLM düzeltmesi gerektirdi (v%d). Başarısızlık nedeni: %s "
                + "— bu servise yeni test üretirken aynı varsayımı tekrarlama.").formatted(
                failedCase.getTestName(), healVersion, truncate(error));
        save(request, AgentLearning.Source.SELF_HEAL, lesson, failedCase.getTestName());
    }

    private void save(TestGenerationRequest request, AgentLearning.Source source,
                      String lesson, String originTestName) {
        try {
            String serviceKey = serviceKeyOf(request);

            // Dedup: aynı ders zaten son kayıtlar arasındaysa tekrar yazma
            boolean duplicate = repository.findTop10ByServiceKeyOrderByCreatedAtDesc(serviceKey).stream()
                    .anyMatch(existing -> existing.getLesson().equals(lesson));
            if (duplicate) {
                return;
            }

            repository.save(AgentLearning.builder()
                    .serviceKey(serviceKey)
                    .source(source)
                    .lesson(lesson)
                    .originTestName(originTestName)
                    .build());
            log.info("📚 Öğrenim kaydedildi [{}] {} — {}", serviceKey, source, originTestName);
        } catch (Exception e) {
            // Öğrenim kaybı ana akışı durdurmamalı
            log.warn("Öğrenim kaydedilemedi: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────
    // Prompt enjeksiyonu
    // ─────────────────────────────────────────────────────────

    /**
     * İsteğin hedef servisi için birikmiş dersleri additionalContext'e ekler.
     * Ders yoksa veya bölüm zaten varsa mevcut bağlamı aynen döner.
     */
    public String enrichWithLearnings(TestGenerationRequest request) {
        String existing = request.getAdditionalContext() == null ? "" : request.getAdditionalContext();
        if (existing.contains(SECTION_TITLE)) {
            return existing;
        }

        List<AgentLearning> learnings =
                repository.findTop10ByServiceKeyOrderByCreatedAtDesc(serviceKeyOf(request));
        if (learnings.isEmpty()) {
            return existing;
        }

        String lessonBlock = learnings.stream()
                .map(l -> "- [" + l.getSource() + "] " + l.getLesson())
                .collect(Collectors.joining("\n"));

        String section = SECTION_TITLE + "\n"
                + "Bu servise ait geçmiş koşumlardan çıkarılan dersler — test üretirken bunlara uy:\n"
                + lessonBlock;

        log.info("📚 {} öğrenim prompt bağlamına enjekte edildi (serviceKey: {})",
                learnings.size(), serviceKeyOf(request));
        return existing.isBlank() ? section : existing + "\n\n" + section;
    }

    // ─────────────────────────────────────────────────────────
    // Servis anahtarı
    // ─────────────────────────────────────────────────────────

    /** Hedef servisin kimliği: swaggerUrl > applicationUrl > rawPayload içindeki ilk URL > global. */
    public static String serviceKeyOf(TestGenerationRequest request) {
        String host = hostOf(request.getSwaggerUrl());
        if (host == null) {
            host = hostOf(request.getApplicationUrl());
        }
        if (host == null && request.getRawPayload() != null) {
            Matcher m = URL_PATTERN.matcher(request.getRawPayload());
            if (m.find()) {
                host = hostOf(m.group());
            }
        }
        return host != null ? host : "global";
    }

    private static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return URI.create(url.trim()).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String truncate(String text) {
        String clean = text.replaceAll("\\s+", " ").trim();
        return clean.length() > MAX_LESSON_CHARS ? clean.substring(0, MAX_LESSON_CHARS) + "…" : clean;
    }
}
