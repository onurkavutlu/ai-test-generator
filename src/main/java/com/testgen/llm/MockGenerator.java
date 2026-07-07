package com.testgen.llm;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fallback Mock Generator to simulate high-quality LLM outputs when Ollama is offline/timeout.
 */
public class MockGenerator {

    public static String generateFallback(String prompt) {
        return generateFallback(prompt, null);
    }

    public static String generateFallback(String prompt, String frameworkOverride) {
        // Extract fields from prompt
        String userStory = extractField(prompt, "User story:");
        String framework = "KARATE"; // default fallback

        if (frameworkOverride != null && !frameworkOverride.isBlank()) {
            framework = frameworkOverride;
        } else if (prompt.contains("Selenium") || prompt.contains("SELENIUM")) {
            framework = "SELENIUM";
        } else if (prompt.contains("Karate") || prompt.contains("KARATE")) {
            framework = "KARATE";
        } else {
            String extracted = extractField(prompt, "Framework:");
            if (!"Bilinmeyen".equals(extracted)) {
                framework = extracted;
            }
        }

        // If userStory wasn't matched properly, fallback to a sensible default
        if ("-".equals(userStory) || "Bilinmeyen".equals(userStory)) {
            userStory = "Kullanıcı talebine uygun iş akışı testi";
        }

        // 1. Check if the prompt is asking for an Agent Analysis
        boolean isAgent = prompt.contains("Sen bir");

        if (isAgent) {
            if (prompt.contains("PRODUCT_MANAGER")) {
                return """
                        1. PM Analizi: Kullanıcı gereksinimi olan "%s" özelliği doğrulandı. İş akışı adımları netleştirildi.
                        2. Risk Değerlendirmesi: İşlem sırasında oluşabilecek ağ kesintileri ve hatalı giriş denemeleri en büyük riskler olarak belirlendi.
                        3. Kabul Kriterleri: Başarılı senaryoda işlem tamamlanmalı, negatif senaryolarda ise kullanıcıya açıklayıcı hata mesajı dönmelidir.
                        """.formatted(userStory);
            }
            if (prompt.contains("DEVELOPER")) {
                return """
                        1. Geliştirici Analizi: "%s" özelliği için gerekli backend servisleri ve controller katmanı hazırlandı.
                        2. Veri Yapısı: İstek gövdesinde gerekli validation kuralları (@NotNull, @Size) uygulandı.
                        3. Teknik Borç: API yanıt sürelerini optimize etmek için cache mekanizması (Redis) devreye alınabilir.
                        """.formatted(userStory);
            }
            if (prompt.contains("AI_LLM_TEST_ANALYST")) {
                return """
                        1. Test Stratejisi: "%s" işlevselliği için 2 pozitif, 2 negatif test senaryosu belirlendi.
                        2. Test Verisi: Geçerli ve geçersiz payload şablonları hazırlandı.
                        3. Sınır Değer Analizi: Gönderilen parametrelerin boş, maksimum karakter limiti ve uyumsuz format durumları test kapsamına eklendi.
                        """.formatted(userStory);
            }
            if (prompt.contains("TEST_AUTOMATION")) {
                return """
                        1. Otomasyon Kapsamı: "%s" senaryoları %s framework'ü ile otomatize edilmeye hazır.
                        2. Metodoloji: Karate feature dosyaları veya Selenium POM sınıfları standartlara uygun şekilde yapılandırıldı.
                        3. Assertions: Response status code ve JSON/HTML gövde elemanlarının doğrulanması için assert kuralları eklendi.
                        """.formatted(userStory, framework);
            }
            if (prompt.contains("PERFORMANCE")) {
                return """
                        1. Performans Analizi: "%s" iş akışında ortalama yanıt süresi 200ms altında olmalıdır.
                        2. Yük Testi: 100 eşzamanlı sanal kullanıcı (VU) ile yük simülasyonu yapılması önerilir.
                        3. Darboğaz: Veritabanı index eksikliğinden dolayı olası gecikmeler sorgu optimizasyonuyla giderilmiştir.
                        """.formatted(userStory);
            }
            if (prompt.contains("DEVOPS")) {
                return """
                        1. CI/CD Entegrasyonu: Projenin pipeline aşamalarına otomatik test tetikleme adımı eklendi.
                        2. Raporlama: Koşum bittiğinde Allure sonuçlarının Docker volume üzerinden nginx web sunucusuna kopyalanması yapılandırıldı.
                        3. Docker Ortamı: Selenium Grid Hub ve Chrome Node konteynerleri test ortamı için hazır hale getirildi.
                        """;
            }
            if (prompt.contains("SECOPS")) {
                return """
                        1. Güvenlik Denetimi: Yetkisiz erişim (401 Unauthorized) ve yetki aşımı (403 Forbidden) senaryoları test edildi.
                        2. OWASP Riskleri: SQL Injection ve XSS saldırılarına karşı API giriş parametreleri sanitize ediliyor.
                        3. Hassas Veri: Response gövdesinde şifre, token gibi hassas bilgilerin maskelenmiş olduğu doğrulandı.
                        """;
            }
            if (prompt.contains("REPORT")) {
                return """
                        1. Yönetici Özeti: Çoklu ajan analizi sonucu "%s" özelliği için test otomasyon paketi başarıyla hazırlandı.
                        2. Kapsam Oranı: Fonksiyonel gereksinimlerin %%100'ü kapsandı.
                        3. Öneri: Projenin CI/CD hattına entegre edilerek her commit sonrasında otomatik çalıştırılması tavsiye edilmektedir.
                        """.formatted(userStory);
            }
        }

        // 2. Default fallback: Code generation matching the framework type
        if ("KARATE".equalsIgnoreCase(framework)) {
            return """
                    ```gherkin
                    Feature: %s
                    
                      Background:
                        * url 'https://petstore3.swagger.io/api/v3'
                        * header Accept = 'application/json'
                        * header Content-Type = 'application/json'
                    
                      Scenario: Basarili Senaryo
                        Given path 'pet'
                        And request { id: 105, name: 'Kuru Fasulye', status: 'available' }
                        When method POST
                        Then status 200
                        And match response.name == 'Kuru Fasulye'
                    
                      Scenario: Gecersiz Parametre ile Hata Senaryosu
                        Given path 'pet'
                        And request { id: 106, status: 'sold' }
                        When method POST
                        Then status 400
                    ```
                    """.formatted(userStory);
        } else if ("SELENIUM".equalsIgnoreCase(framework)) {
            return """
                    ```java
                    package com.testgen.generated;
                    
                    import org.junit.jupiter.api.Test;
                    import org.openqa.selenium.By;
                    import org.openqa.selenium.WebDriver;
                    import org.openqa.selenium.chrome.ChromeDriver;
                    import static org.junit.jupiter.api.Assertions.assertTrue;
                    
                    public class SeleniumAutomationTest {
                        @Test
                        public void testUserStory() {
                            // %s
                            WebDriver driver = new ChromeDriver();
                            driver.get("https://example.com/login");
                            driver.findElement(By.id("username")).sendKeys("testuser");
                            driver.findElement(By.id("password")).sendKeys("pass123");
                            driver.findElement(By.cssSelector("button[type=submit]")).click();
                            assertTrue(driver.getCurrentUrl().contains("dashboard"));
                            driver.quit();
                        }
                    }
                    ```
                    """.formatted(userStory);
        }

        // Bilinmeyen framework — basit Karate iskeletiyle dön
        return """
                ```gherkin
                Feature: %s

                  Scenario: Temel Senaryo
                    * print 'mock fallback'
                ```
                """.formatted(userStory);
    }

    private static String extractField(String prompt, String fieldName) {
        try {
            Pattern pattern = Pattern.compile(fieldName + "\\s*([^\n]+)");
            Matcher matcher = pattern.matcher(prompt);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        } catch (Exception e) {
            // ignore
        }
        return "Bilinmeyen";
    }
}
