package com.testgen.service;

import com.testgen.model.GeneratedTestCase;
import com.testgen.model.RequestStatus;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestSuite;
import com.testgen.model.TestType;
import com.testgen.repository.GeneratedTestCaseRepository;
import com.testgen.repository.TestGenerationRequestRepository;
import com.testgen.repository.TestSuiteRepository;
import com.testgen.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Saklama temizliğinin GERÇEK veritabanı üzerinde doğrulanması.
 *
 * <p><b>Neden birim testine ek olarak bu:</b> birim testi depo çağrılarının sırasını
 * kilitler ama üretilen SQL'i hiç çalıştırmaz. Bu temizliğin en olası kırılma biçimi
 * tam olarak SQL seviyesindedir: yabancı anahtar sırası, toplu {@code DELETE ... IN}
 * ifadesinin gerçekten çalışması ve suite bağlantı tablosunun korunması. Mock'la
 * yazılmış yeşil bir test, üretimde kısıt ihlaliyle patlayan bir temizliği gizler.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:retention_it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "notification.email.enabled=false",
        "scheduler.daily-run.cron=-"
})
@ActiveProfiles("local")
class DataRetentionIntegrationTest {

    @Autowired
    private DataRetentionService service;

    @Autowired
    private TestGenerationRequestRepository requestRepository;

    @Autowired
    private GeneratedTestCaseRepository testCaseRepository;

    @Autowired
    private TestSuiteRepository suiteRepository;

    @BeforeEach
    void clean() {
        suiteRepository.deleteAll();
        testCaseRepository.deleteAll();
        requestRepository.deleteAll();
    }

    private TestGenerationRequest request(LocalDateTime createdAt, RequestStatus status, String tenant) {
        TestGenerationRequest request = TestGenerationRequest.builder()
                .testType(TestType.BACKEND_API)
                .framework(TestFramework.KARATE)
                .userStory("Kullanıcı giriş yapabilmeli")
                .status(status)
                .createdAt(createdAt)
                .tenantId(tenant)
                .build();
        return requestRepository.save(request);
    }

    private GeneratedTestCase testCase(TestGenerationRequest request, String name) {
        return testCaseRepository.save(GeneratedTestCase.builder()
                .request(request)
                .testName(name)
                .fileName(name + ".feature")
                .testContent("Feature: " + name)
                .framework(TestFramework.KARATE)
                .createdAt(request.getCreatedAt())
                .tenantId(request.getTenantId())
                .build());
    }

    @Test
    @DisplayName("Yaşı dolmuş istek ve case'leri gerçekten silinir; yenisine dokunulmaz")
    void oldRowsAreDeletedAndRecentRowsSurvive() {
        TestGenerationRequest old = request(LocalDateTime.now().minusDays(60),
                RequestStatus.COMPLETED, TenantContext.DEFAULT_TENANT);
        testCase(old, "eski-1");
        testCase(old, "eski-2");

        TestGenerationRequest fresh = request(LocalDateTime.now().minusDays(2),
                RequestStatus.COMPLETED, TenantContext.DEFAULT_TENANT);
        testCase(fresh, "yeni-1");

        DataRetentionResult result = service.purge(30, false);

        assertEquals(1, result.requestCount());
        assertEquals(2, result.testCaseCount());
        assertFalse(requestRepository.existsById(old.getId()));
        assertTrue(requestRepository.existsById(fresh.getId()));
        assertEquals(0, testCaseRepository.findByRequestId(old.getId()).size());
        assertEquals(1, testCaseRepository.findByRequestId(fresh.getId()).size());
    }

    @Test
    @DisplayName("Önizleme hiçbir satırı silmez ama silinecek sayıyı doğru verir")
    void dryRunLeavesEverythingInPlace() {
        TestGenerationRequest old = request(LocalDateTime.now().minusDays(45),
                RequestStatus.COMPLETED, TenantContext.DEFAULT_TENANT);
        testCase(old, "eski-1");

        DataRetentionResult preview = service.purge(30, true);

        assertTrue(preview.dryRun());
        assertEquals(1, preview.requestCount());
        assertEquals(1, preview.testCaseCount());
        assertTrue(requestRepository.existsById(old.getId()));
        assertEquals(1, testCaseRepository.findByRequestId(old.getId()).size());
    }

    @Test
    @DisplayName("Suite'e bağlı case'i olan istek silinmez — bağlantı tablosu kırılmaz")
    void suiteLinkedRequestSurvives() {
        TestGenerationRequest old = request(LocalDateTime.now().minusDays(90),
                RequestStatus.COMPLETED, TenantContext.DEFAULT_TENANT);
        GeneratedTestCase linked = testCase(old, "pakete-alinmis");

        List<GeneratedTestCase> cases = new ArrayList<>();
        cases.add(linked);
        suiteRepository.save(TestSuite.builder()
                .name("Regresyon paketi")
                .testCases(cases)
                .tenantId(TenantContext.DEFAULT_TENANT)
                .build());

        DataRetentionResult result = service.purge(30, false);

        assertEquals(0, result.requestCount());
        assertEquals(1, result.protectedRequestCount());
        assertEquals(List.of(old.getId()), result.protectedRequestIds());
        assertTrue(requestRepository.existsById(old.getId()));
        assertTrue(testCaseRepository.existsById(linked.getId()));
    }

    @Test
    @DisplayName("Süren üretim (GENERATING) yaşı dolsa bile silinmez")
    void inFlightRequestIsNotDeleted() {
        TestGenerationRequest running = request(LocalDateTime.now().minusDays(120),
                RequestStatus.GENERATING, TenantContext.DEFAULT_TENANT);

        DataRetentionResult result = service.purge(30, false);

        assertEquals(0, result.requestCount());
        assertTrue(requestRepository.existsById(running.getId()));
    }

    @Test
    @DisplayName("Başka kiracının eski verisi bu kiracının temizliğiyle silinmez")
    void otherTenantDataIsUntouched() {
        TestGenerationRequest foreign = request(LocalDateTime.now().minusDays(200),
                RequestStatus.COMPLETED, "musteri-b");
        TestGenerationRequest mine = request(LocalDateTime.now().minusDays(200),
                RequestStatus.COMPLETED, TenantContext.DEFAULT_TENANT);

        DataRetentionResult result = service.purge(30, false);

        assertEquals(1, result.requestCount());
        assertTrue(requestRepository.existsById(foreign.getId()));
        assertFalse(requestRepository.existsById(mine.getId()));
    }
}
