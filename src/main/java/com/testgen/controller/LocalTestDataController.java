package com.testgen.controller;

import com.testgen.model.GeneratedTestCase;
import com.testgen.model.RequestStatus;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestRunStatus;
import com.testgen.model.TestType;
import com.testgen.repository.GeneratedTestCaseRepository;
import com.testgen.repository.TestGenerationRequestRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Profile("local")
@Tag(name = "4. Geliştirici Test Datası (Seeding)", description = "Sadece 'local' profilde aktif olan, test/demo veritabanını dolduran API'ler")
@RestController
@RequestMapping("/api/v1/dev")
@RequiredArgsConstructor
public class LocalTestDataController {

    private final TestGenerationRequestRepository requestRepository;
    private final GeneratedTestCaseRepository testCaseRepository;

    @Operation(summary = "Örnek Test Verilerini Veritabanına Doldur (Seed)", description = "Veritabanını sıfırlayarak yerine demo sunumu için hazır, zengin test istekleri ve case senaryoları yükler.")
    @PostMapping("/seed")
    public ResponseEntity<Map<String, Object>> seed() {
        testCaseRepository.deleteAllInBatch();
        requestRepository.deleteAllInBatch();

        TestGenerationRequest petstore = saveRequest(TestGenerationRequest.builder()
                .testType(TestType.BACKEND_API)
                .framework(TestFramework.KARATE)
                .swaggerUrl("https://petstore3.swagger.io/api/v3/openapi.json")
                .additionalContext("PetStore API icin CRUD ve hata senaryolari")
                .status(RequestStatus.GENERATED)
                .scheduledRun(true)
                .build());
        saveCase(petstore, "GetPetByIdTest", "GetPetByIdTest.feature", karateFeature(), "[LOCAL-SEED][LLM-GENERATED] Pet detay endpoint'i icin 200 OK, 404 Not Found ve response id dogrulamalarini ekledi.", TestFramework.KARATE, TestRunStatus.PASSED, 4, 4, 0);
        saveCase(petstore, "CreatePetValidationTest", "CreatePetValidationTest.feature", createPetFeature(), "[LOCAL-SEED][LLM-GENERATED] Pet olusturma akisi icin basarili payload ve eksik name validasyon senaryolarini ekledi.", TestFramework.KARATE, TestRunStatus.FAILED, 3, 2, 1);
        saveCase(petstore, "CreatePetValidationFixedTest", "CreatePetValidationFixedTest.feature", createPetFixedFeature(), "[AUTO-FIX][LLM-GENERATED] Basarisiz validasyon sonucundan sonra hata beklentisini API kontratina gore 400/405 kabul edecek sekilde daraltti ve response hata alanini kontrol etti.", TestFramework.KARATE, TestRunStatus.PASSED, 2, 2, 0);

        TestGenerationRequest paymentApi = saveRequest(TestGenerationRequest.builder()
                .testType(TestType.BACKEND_API)
                .framework(TestFramework.KARATE)
                .swaggerUrl("https://api.testgen.local/openapi/payments.json")
                .additionalContext("Odeme API: kart provizyonu, 3DS callback, iade ve idempotency kontrolleri. Merchant TR-ECOM-42, currency TRY.")
                .status(RequestStatus.GENERATED)
                .scheduledRun(true)
                .build());
        saveCase(paymentApi, "PaymentAuthorizationContractTest", "PaymentAuthorizationContractTest.feature", paymentAuthorizationFeature(), "[LOCAL-SEED][LLM-GENERATED] Odeme provizyonu icin basarili kart, yetersiz limit, idempotency-key tekrari ve 3DS required kontrat kontrollerini ekledi.", TestFramework.KARATE, TestRunStatus.PASSED, 6, 6, 0);
        saveCase(paymentApi, "RefundValidationTest", "RefundValidationTest.feature", refundValidationFeature(), "[LOCAL-SEED][LLM-GENERATED] Kismi iade, fazla tutar iadesi ve ayni refund request id ile tekrar deneme senaryolarini ekledi.", TestFramework.KARATE, TestRunStatus.PASSED, 4, 4, 0);

        TestGenerationRequest bankingApi = saveRequest(TestGenerationRequest.builder()
                .testType(TestType.BACKEND_API)
                .framework(TestFramework.KARATE)
                .swaggerUrl("https://api.testgen.local/openapi/retail-banking.json")
                .additionalContext("Bireysel bankacilik API: EFT/Havale limitleri, gunluk limit, hesap hareketleri filtreleme, KVKK maskeli response.")
                .status(RequestStatus.GENERATED)
                .scheduledRun(false)
                .build());
        saveCase(bankingApi, "MoneyTransferLimitTest", "MoneyTransferLimitTest.feature", moneyTransferFeature(), "[LOCAL-SEED][LLM-GENERATED] Para transferi icin gunluk limit, IBAN format hatasi, bakiye yetersizligi ve basarili EFT senaryolarini ekledi.", TestFramework.KARATE, TestRunStatus.FAILED, 5, 4, 1);
        saveCase(bankingApi, "TransactionHistoryFilterTest", "TransactionHistoryFilterTest.feature", transactionHistoryFeature(), "[LOCAL-SEED][LLM-GENERATED] Hesap hareketlerinde tarih araligi, tutar filtresi, sayfalama ve maskeli alici bilgisi kontrollerini ekledi.", TestFramework.KARATE, TestRunStatus.PASSED, 4, 4, 0);

        TestGenerationRequest webLogin = saveRequest(TestGenerationRequest.builder()
                .testType(TestType.FRONTEND_WEB)
                .framework(TestFramework.SELENIUM)
                .applicationUrl("https://example.testgen.local/login")
                .userStory("Kullanici gecerli bilgilerle login olup dashboard'a ulasabilmeli")
                .additionalContext("username=#username, password=#password, submit=button[type=submit]")
                .status(RequestStatus.GENERATED)
                .build());
        saveCase(webLogin, "LoginPageTest", "LoginPageTest.java", seleniumTest(), "[LOCAL-SEED][LLM-GENERATED] Login formu icin basarili giris, hatali sifre ve dashboard gorunurlugu kontrollerini Java/Selenium testine ekledi.", TestFramework.SELENIUM, TestRunStatus.NOT_RUN, null, null, null);

        TestGenerationRequest checkoutWeb = saveRequest(TestGenerationRequest.builder()
                .testType(TestType.FRONTEND_WEB)
                .framework(TestFramework.SELENIUM)
                .applicationUrl("https://shop.testgen.local/checkout")
                .userStory("Sepetinde iki urun olan kullanici adres secip kupon uygulayarak kredi karti ile siparisi tamamlayabilmeli")
                .additionalContext("cart=.cart-summary, coupon=#couponCode, address=[data-testid=address-card], pay=#payButton, success=.order-confirmation")
                .status(RequestStatus.GENERATED)
                .build());
        saveCase(checkoutWeb, "CheckoutHappyPathTest", "CheckoutHappyPathTest.java", checkoutSeleniumTest(), "[LOCAL-SEED][LLM-GENERATED] Checkout akisinda sepet ozeti, adres secimi, kart odemesi ve siparis onay numarasi kontrollerini ekledi.", TestFramework.SELENIUM, TestRunStatus.NOT_RUN, null, null, null);
        saveCase(checkoutWeb, "CouponValidationTest", "CouponValidationTest.java", couponSeleniumTest(), "[LOCAL-SEED][LLM-GENERATED] Kupon alaninda suresi gecmis kupon, minimum sepet tutari ve gecerli indirim hesaplama kontrollerini ekledi.", TestFramework.SELENIUM, TestRunStatus.NOT_RUN, null, null, null);

        TestGenerationRequest mobileLogin = saveRequest(TestGenerationRequest.builder()
                .testType(TestType.MOBILE)
                .framework(TestFramework.APPIUM)
                .appPackage("com.testgen.demoapp")
                .userStory("Mobil kullanici PIN ile giris yapip hesap ozetini gorebilmeli")
                .additionalContext("Android 13, emulator Pixel_6")
                .status(RequestStatus.GENERATED)
                .build());
        saveCase(mobileLogin, "MobilePinLoginAppiumTest", "MobilePinLoginAppiumTest.java", appiumTest(), "[LOCAL-SEED][LLM-GENERATED] PIN girisi, hesap ozeti ekrani ve Android emulator kosulunu Appium test akisi olarak ekledi.", TestFramework.APPIUM, TestRunStatus.SKIPPED, 1, 0, 0);

        TestGenerationRequest courierMobile = saveRequest(TestGenerationRequest.builder()
                .testType(TestType.MOBILE)
                .framework(TestFramework.APPIUM)
                .appPackage("com.testgen.courier")
                .userStory("Kurye yeni teslimat gorevini kabul edip restorandan teslim alarak musteride teslim edildi olarak kapatabilmeli")
                .additionalContext("Android 14, emulator Pixel_7; selectors: acceptTaskButton, pickupCodeInput, deliveredButton, proofPhotoButton")
                .status(RequestStatus.GENERATED)
                .build());
        saveCase(courierMobile, "CourierDeliveryFlowAppiumTest", "CourierDeliveryFlowAppiumTest.java", courierAppiumTest(), "[LOCAL-SEED][LLM-GENERATED] Kurye akisi icin gorev kabul, pickup kodu, teslimat fotografi ve teslim edildi durum kontrollerini ekledi.", TestFramework.APPIUM, TestRunStatus.NOT_RUN, null, null, null);
        saveCase(courierMobile, "CourierOfflineSyncAppiumTest", "CourierOfflineSyncAppiumTest.java", courierOfflineAppiumTest(), "[LOCAL-SEED][LLM-GENERATED] Mobil offline modda teslimat notu kaydi, tekrar online olunca senkronizasyon ve hata mesaji kontrollerini ekledi.", TestFramework.APPIUM, TestRunStatus.SKIPPED, 3, 0, 0);

        List<TestGenerationRequestResponseDto> requests = requestRepository.findAll()
                .stream()
                .map(TestGenerationRequestResponseDto::from)
                .toList();

        return ResponseEntity.ok(Map.of(
                "message", "Local seed data olusturuldu",
                "requestCount", requests.size(),
                "testCaseCount", testCaseRepository.count(),
                "requests", requests
        ));
    }

    @Operation(summary = "Demo Verilerini Temizle", description = "Veritabanındaki tüm test istekleri ve test kodlarını tamamen siler.")
    @DeleteMapping("/seed")
    public ResponseEntity<Map<String, Object>> clear() {
        testCaseRepository.deleteAllInBatch();
        requestRepository.deleteAllInBatch();
        return ResponseEntity.ok(Map.of("message", "Local seed data temizlendi"));
    }

    private TestGenerationRequest saveRequest(TestGenerationRequest request) {
        return requestRepository.save(request);
    }

    private void saveCase(TestGenerationRequest request, String name, String fileName, String content,
                          String summary, TestFramework framework, TestRunStatus status,
                          Integer total, Integer passed, Integer failed) {
        testCaseRepository.save(GeneratedTestCase.builder()
                .request(request)
                .testName(name)
                .fileName(fileName)
                .testContent(content)
                .testSummary(summary)
                .framework(framework)
                .runStatus(status)
                .totalScenarios(total)
                .passedScenarios(passed)
                .failedScenarios(failed)
                .build());
    }

    private String karateFeature() {
        return """
                Feature: Get pet by id

                  Background:
                    * url 'https://petstore3.swagger.io/api/v3'
                    * header Accept = 'application/json'

                  Scenario: Existing pet can be fetched
                    Given path 'pet', 10
                    When method GET
                    Then status 200
                    And match response.id == 10

                  Scenario: Unknown pet returns not found
                    Given path 'pet', 999999999
                    When method GET
                    Then status 404
                """;
    }

    private String createPetFeature() {
        return """
                Feature: Create pet validation

                  Background:
                    * url 'https://petstore3.swagger.io/api/v3'
                    * header Accept = 'application/json'
                    * header Content-Type = 'application/json'

                  Scenario: Create pet with valid payload
                    Given path 'pet'
                    And request { id: 101, name: 'Mavi', status: 'available' }
                    When method POST
                    Then status 200
                    And match response.name == 'Mavi'

                  Scenario: Missing name is rejected
                    Given path 'pet'
                    And request { id: 102, status: 'available' }
                    When method POST
                    Then status 400
                """;
    }

    private String createPetFixedFeature() {
        return """
                Feature: Create pet validation after LLM improvement

                  Background:
                    * url 'https://petstore3.swagger.io/api/v3'
                    * header Accept = 'application/json'
                    * header Content-Type = 'application/json'

                  Scenario: Missing name returns a contract-level validation error
                    Given path 'pet'
                    And request { id: 102, status: 'available' }
                    When method POST
                    Then match [400, 405] contains responseStatus

                  Scenario: Invalid status value is rejected or normalized by the API
                    Given path 'pet'
                    And request { id: 103, name: 'Boncuk', status: 'invalid-status' }
                    When method POST
                    Then match [200, 400, 405] contains responseStatus
                """;
    }

    private String paymentAuthorizationFeature() {
        return """
                Feature: Payment authorization contract

                  Background:
                    * url 'https://api.testgen.local/payments'
                    * header Accept = 'application/json'
                    * header Content-Type = 'application/json'
                    * header Idempotency-Key = 'pay-seed-1001'

                  Scenario: Domestic card payment can be authorized
                    Given path 'authorizations'
                    And request { merchantId: 'TR-ECOM-42', amount: 1299.90, currency: 'TRY', cardToken: 'tok_visa_approved' }
                    When method POST
                    Then status 201
                    And match response.status == 'AUTHORIZED'

                  Scenario: Insufficient limit is rejected with business error
                    Given path 'authorizations'
                    And request { merchantId: 'TR-ECOM-42', amount: 45000, currency: 'TRY', cardToken: 'tok_limit_low' }
                    When method POST
                    Then status 402
                    And match response.errorCode == 'INSUFFICIENT_LIMIT'
                """;
    }

    private String refundValidationFeature() {
        return """
                Feature: Refund validation contract

                  Background:
                    * url 'https://api.testgen.local/payments'
                    * header Accept = 'application/json'
                    * header Content-Type = 'application/json'

                  Scenario: Partial refund can be created
                    Given path 'payments', 'pay_20260607_1001', 'refunds'
                    And request { refundRequestId: 'refund-1001-A', amount: 250.00, reason: 'CUSTOMER_RETURN' }
                    When method POST
                    Then status 201
                    And match response.status == 'REFUND_CREATED'

                  Scenario: Refund amount cannot exceed captured amount
                    Given path 'payments', 'pay_20260607_1001', 'refunds'
                    And request { refundRequestId: 'refund-1001-B', amount: 99999.00, reason: 'MANUAL_REVIEW' }
                    When method POST
                    Then status 400
                    And match response.errorCode == 'REFUND_AMOUNT_EXCEEDED'
                """;
    }

    private String moneyTransferFeature() {
        return """
                Feature: Retail banking money transfer

                  Background:
                    * url 'https://api.testgen.local/banking'
                    * header Accept = 'application/json'
                    * header Content-Type = 'application/json'
                    * header X-Customer-Id = 'cust-88421'

                  Scenario: EFT transfer succeeds under daily limit
                    Given path 'transfers'
                    And request { fromAccount: 'TR120006200119000006672315', toIban: 'TR330006100519786457841326', amount: 2500.00, description: 'Kira katkisi' }
                    When method POST
                    Then status 201
                    And match response.status == 'PENDING_APPROVAL'

                  Scenario: Daily transfer limit is enforced
                    Given path 'transfers'
                    And request { fromAccount: 'TR120006200119000006672315', toIban: 'TR330006100519786457841326', amount: 150000.00, description: 'Limit testi' }
                    When method POST
                    Then status 403
                    And match response.errorCode == 'DAILY_LIMIT_EXCEEDED'
                """;
    }

    private String transactionHistoryFeature() {
        return """
                Feature: Transaction history filters

                  Background:
                    * url 'https://api.testgen.local/banking'
                    * header Accept = 'application/json'
                    * header X-Customer-Id = 'cust-88421'

                  Scenario: Transactions can be filtered by date and amount
                    Given path 'accounts', 'acc-TR-001', 'transactions'
                    And param from = '2026-05-01'
                    And param to = '2026-05-31'
                    And param minAmount = 100
                    When method GET
                    Then status 200
                    And match response.items == '#[]'
                    And match each response.items contains { maskedCounterparty: '#string' }
                """;
    }

    private String seleniumTest() {
        return """
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                public class LoginPageTest {
                    @Test
                    void shouldShowDashboardAfterSuccessfulLogin() {
                        assertTrue(true, "Seed data: Selenium happy path placeholder");
                    }
                }
                """;
    }

    private String checkoutSeleniumTest() {
        return """
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                public class CheckoutHappyPathTest {
                    @Test
                    void shouldCompleteCheckoutWithSavedAddressAndCard() {
                        assertTrue(true, "Seed data: checkout happy path with order confirmation placeholder");
                    }
                }
                """;
    }

    private String couponSeleniumTest() {
        return """
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                public class CouponValidationTest {
                    @Test
                    void shouldValidateExpiredAndMinimumBasketCoupons() {
                        assertTrue(true, "Seed data: coupon validation placeholder");
                    }
                }
                """;
    }

    private String appiumTest() {
        return """
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                public class MobilePinLoginAppiumTest {
                    @Test
                    void shouldOpenAccountSummaryAfterPinLogin() {
                        assertTrue(true, "Seed data: Appium scenario placeholder");
                    }
                }
                """;
    }

    private String courierAppiumTest() {
        return """
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                public class CourierDeliveryFlowAppiumTest {
                    @Test
                    void shouldAcceptPickupAndCompleteDelivery() {
                        assertTrue(true, "Seed data: courier delivery flow placeholder");
                    }
                }
                """;
    }

    private String courierOfflineAppiumTest() {
        return """
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                public class CourierOfflineSyncAppiumTest {
                    @Test
                    void shouldSyncDeliveryNoteAfterNetworkRestored() {
                        assertTrue(true, "Seed data: courier offline sync placeholder");
                    }
                }
                """;
    }
}
