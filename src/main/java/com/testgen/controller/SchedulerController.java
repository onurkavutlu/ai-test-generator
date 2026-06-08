package com.testgen.controller;

import com.testgen.model.TestGenerationRequest;
import com.testgen.repository.TestGenerationRequestRepository;
import com.testgen.scheduler.DailySchedulerService;
import com.testgen.scheduler.SchedulerRunSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Scheduler yönetim API'si
 *
 * POST /api/v1/scheduler/{requestId}/enable        → schedule'a ekle
 * POST /api/v1/scheduler/{requestId}/disable       → schedule'dan çıkar
 * POST /api/v1/scheduler/{requestId}/trigger-now   → hemen koştur (test/demo)
 * GET  /api/v1/scheduler/list                      → tüm schedule'daki request'ler
 */
@Slf4j
@Tag(name = "2. Scheduler (Zamanlayıcı & Kendi Kendini Düzeltme)", description = "Periyodik test koşumları ve başarısızlıklar sonrası self-healing API'leri")
@RestController
@RequestMapping("/api/v1/scheduler")
@RequiredArgsConstructor
public class SchedulerController {

    private final TestGenerationRequestRepository requestRepository;
    private final DailySchedulerService           schedulerService;

    /**
     * Bir request'i günlük schedule'a ekle.
     * autoGenerate=true ise başarısız testler için LLM otomatik yeni test üretir.
     */
    @Operation(summary = "İsteği Günlük Otomatik Takvime Ekle", description = "İlgili test üretim isteğini her gün 02:00'de otomatik olarak çalışacak şekilde takvimlendirir. autoGenerate=true ise başarısız olan testler için LLM otomatik yeni test üretip iyileştirir.")
    @PostMapping("/{requestId}/enable")
    public ResponseEntity<Map<String, Object>> enable(
            @PathVariable String requestId,
            @RequestParam(defaultValue = "true") boolean autoGenerate) {

        var request = findRequest(requestId);
        request.setScheduledRun(true);
        request.setAutoGenerateOnFailure(autoGenerate);
        requestRepository.save(request);

        log.info("Schedule aktif edildi - requestId: {}, autoGenerate: {}",
                requestId, autoGenerate);

        return ResponseEntity.ok(Map.of(
                "requestId",     requestId,
                "scheduledRun",  true,
                "autoGenerate",  autoGenerate,
                "message",       "Günlük 02:00'de otomatik koşacak. Başarısız testler için LLM yeni test üretecek."
        ));
    }

    /**
     * Bir request'i schedule'dan çıkar.
     */
    @Operation(summary = "İsteği Günlük Otomatik Takvimden Çıkar", description = "İlgili test isteğinin otomatik gece koşumlarını sonlandırır.")
    @PostMapping("/{requestId}/disable")
    public ResponseEntity<Map<String, Object>> disable(@PathVariable String requestId) {
        var request = findRequest(requestId);
        request.setScheduledRun(false);
        requestRepository.save(request);

        log.info("Schedule devre dışı bırakıldı - requestId: {}", requestId);

        return ResponseEntity.ok(Map.of(
                "requestId",    requestId,
                "scheduledRun", false,
                "message",      "Günlük schedule'dan çıkarıldı."
        ));
    }

    /**
     * İstenen request'i hemen koştur — günlük saati beklemeden.
     * CI/CD'den veya manüel test için kullanılır.
     */
    @Operation(summary = "Zamanlayıcıyı Hemen Tetikle", description = "Geceyi beklemeden testi hemen koşturur, hata analizi ve self-healing (kendi kendini iyileştirme) adımlarını anında başlatır. CI/CD boru hatları veya demolar için idealdir.", tags = { "3. Sunum ve Demo Akışı (Adım Adım)", "2. Scheduler (Zamanlayıcı & Kendi Kendini Düzeltme)" })
    @PostMapping("/{requestId}/trigger-now")
    public ResponseEntity<SchedulerRunSummary> triggerNow(@PathVariable String requestId) {
        log.info("Manuel scheduler tetiklendi - requestId: {}", requestId);
        SchedulerRunSummary summary = schedulerService.triggerManually(requestId);
        return ResponseEntity.ok(summary);
    }

    /**
     * Schedule'daki tüm request'leri listele
     */
    @Operation(summary = "Otomatik Koşum Takvimindeki Tüm İstekleri Listele", description = "Zamanlanmış koşum listesinde yer alan tüm testlerin istatistiklerini listeler.")
    @GetMapping("/list")
    public ResponseEntity<List<Map<String, Object>>> listScheduled() {
        var requests = requestRepository.findAllScheduled();

        var result = requests.stream().map(r -> Map.<String, Object>of(
                "requestId",        r.getId(),
                "testType",         r.getTestType(),
                "framework",        r.getFramework(),
                "scheduledRun",     r.isScheduledRun(),
                "autoGenerate",     r.isAutoGenerateOnFailure(),
                "runCount",         r.getScheduledRunCount(),
                "totalFailures",    r.getTotalFailureCount(),
                "lastRun",          r.getLastScheduledRunAt() != null
                                        ? r.getLastScheduledRunAt().toString()
                                        : "hiç koşmadı",
                "swaggerUrl",       r.getSwaggerUrl() != null ? r.getSwaggerUrl() : ""
        )).toList();

        return ResponseEntity.ok(result);
    }

    private TestGenerationRequest findRequest(String requestId) {
        return requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Request bulunamadı: " + requestId));
    }
}
