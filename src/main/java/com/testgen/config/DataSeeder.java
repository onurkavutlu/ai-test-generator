package com.testgen.config;

import com.testgen.model.*;
import com.testgen.repository.GeneratedTestCaseRepository;
import com.testgen.repository.TestGenerationRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Slf4j
@Component
@ConditionalOnProperty(name = "test-generator.seeding.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final TestGenerationRequestRepository requestRepository;
    private final GeneratedTestCaseRepository testCaseRepository;

    @Override
    public void run(String... args) {
        if (requestRepository.count() == 0) {
            log.info("Veritabanı boş, örnek seed verileri otomatik yükleniyor...");

            // 1. PetStore API Request
            TestGenerationRequest petstore = requestRepository.save(TestGenerationRequest.builder()
                    .testType(TestType.BACKEND_API)
                    .framework(TestFramework.KARATE)
                    .swaggerUrl("https://petstore3.swagger.io/api/v3/openapi.json")
                    .additionalContext("PetStore API icin CRUD ve hata senaryolari")
                    .status(RequestStatus.GENERATED)
                    .scheduledRun(true)
                    .build());

            saveCase(petstore, "GetPetByIdTest", "GetPetByIdTest.feature", karateFeature(), 
                    "Pet detay endpoint'i icin pozitif ve negatif Karate senaryolari", TestFramework.KARATE, TestRunStatus.PASSED, 4, 4, 0);
            saveCase(petstore, "CreatePetValidationTest", "CreatePetValidationTest.feature", createPetFeature(), 
                    "Pet olusturma validasyon senaryolari", TestFramework.KARATE, TestRunStatus.FAILED, 3, 2, 1);

            // 2. Web Login Request
            TestGenerationRequest webLogin = requestRepository.save(TestGenerationRequest.builder()
                    .testType(TestType.FRONTEND_WEB)
                    .framework(TestFramework.SELENIUM)
                    .applicationUrl("https://example.testgen.local/login")
                    .userStory("Kullanici gecerli bilgilerle login olup dashboard'a ulasabilmeli")
                    .additionalContext("username=#username, password=#password, submit=button[type=submit]")
                    .status(RequestStatus.GENERATED)
                    .build());

            saveCase(webLogin, "LoginPageTest", "LoginPageTest.java", seleniumTest(), 
                    "Login formu icin happy path ve hatali sifre senaryosu", TestFramework.SELENIUM, TestRunStatus.NOT_RUN, null, null, null);

            // 3. Mobile Login Request
            TestGenerationRequest mobileLogin = requestRepository.save(TestGenerationRequest.builder()
                    .testType(TestType.MOBILE)
                    .framework(TestFramework.APPIUM)
                    .appPackage("com.testgen.demoapp")
                    .userStory("Mobil kullanici PIN ile giris yapip hesap ozetini gorebilmeli")
                    .additionalContext("Android 13, emulator Pixel_6")
                    .status(RequestStatus.GENERATED)
                    .build());

            saveCase(mobileLogin, "MobilePinLoginAppiumTest", "MobilePinLoginAppiumTest.java", appiumTest(), 
                    "PIN login ve hesap ozeti goruntuleme mobil testi", TestFramework.APPIUM, TestRunStatus.SKIPPED, 1, 0, 0);

            log.info("Örnek seed verileri başarıyla yüklendi: {} istek, {} test case.", 
                    requestRepository.count(), testCaseRepository.count());
        } else {
            log.info("Veritabanı zaten dolu, otomatik seed işlemi atlanıyor.");
        }
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
}
