package com.testgen.controller;

import com.testgen.service.DataRetentionResult;
import com.testgen.service.DataRetentionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bakım uçları:
 *
 * DELETE /api/v1/maintenance/test-data?olderThanDays=30&amp;dryRun=true
 *   → saklama süresi dolmuş üretim isteklerini ve bağlı test case'leri temizler.
 *
 * <p><b>Neden ayrı bir controller:</b> bakım işlemleri iş akışı uçlarından farklı bir
 * kitleye (operasyon) hizmet eder ve ileride yetkilendirme geldiğinde tek bir yol öneki
 * ({@code /api/v1/maintenance/**}) ayrı kurala bağlanacaktır. Silmeyi
 * {@code /api/v1/tests} altına koymak, o kuralı uç uç yazmayı gerektirirdi.
 *
 * <p><b>Neden DELETE:</b> işlem silicidir ve {@code AuditFilter} yalnız durum değiştiren
 * metotları kaydeder. GET olarak yazılsaydı temizlik denetim izine hiç düşmezdi.
 */
@Tag(name = "10. Bakım (Veri Saklama)",
        description = "Saklama süresi dolmuş test datasının temizlenmesi")
@RestController
@RequestMapping("/api/v1/maintenance")
@RequiredArgsConstructor
public class DataRetentionController {

    private final DataRetentionService dataRetentionService;

    @Operation(
            summary = "Eski Test Datasını Sil",
            description = """
                    Belirtilen günden ESKİ üretim isteklerini ve onlara bağlı test case'leri siler.

                    Varsayılan olarak yalnız ÖNİZLEME yapar (dryRun=true): hiçbir kayıt silinmez,
                    yalnız etkilenecek sayılar döner. Gerçekten silmek için dryRun=false verin.

                    Atlanan kayıtlar:
                    • Bir test suite'e bağlı case'i olan istekler (yanıtta protectedRequestCount olarak raporlanır)
                    • PENDING / GENERATING / RUNNING durumundaki istekler (süren asenkron iş)

                    Kapsam yalnız geçerli kiracının verisidir. Koşum geçmişi (test_executions),
                    LLM çağrı logları ve denetim olayları bu temizliğe DAHİL DEĞİLDİR.
                    """)
    @DeleteMapping(value = "/test-data", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> purgeOldTestData(
            @Parameter(description = "Kaç günden eski kayıtlar silinsin (en az 1)", example = "30")
            @RequestParam(defaultValue = "30") int olderThanDays,
            @Parameter(description = "true ise hiçbir şey silinmez, yalnız sayılar döner", example = "true")
            @RequestParam(defaultValue = "true") boolean dryRun) {

        DataRetentionResult result = dataRetentionService.purge(olderThanDays, dryRun);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dryRun", result.dryRun());
        body.put("olderThanDays", result.retentionDays());
        body.put("cutoff", result.cutoff().toString());
        body.put("deletedRequests", result.requestCount());
        body.put("deletedTestCases", result.testCaseCount());
        body.put("protectedRequestCount", result.protectedRequestCount());
        body.put("protectedRequestIds", result.protectedRequestIds());
        body.put("message", result.dryRun()
                ? "Önizleme: hiçbir kayıt silinmedi. Silmek için dryRun=false gönderin."
                : "Temizlik tamamlandı.");
        return ResponseEntity.ok(body);
    }
}
