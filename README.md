# 🤖 AI Test Generator

**Büyük Dil Modelleri (LLM) ve Multi-Agent (Çoklu Ajan) mimarisi kullanan otonom yazılım test üretim laboratuvarı.**  
Karate DSL · Selenium · Appium · ISTQB Standartları · Self-Healing · Dark Dashboard

---

## 🚀 Projenin Amacı ve Temel Yetenekleri

Bu proje, geleneksel manuel test yazımını otomatize etmekle kalmaz; yazılım geliştirme yaşam döngüsündeki farklı rolleri (İş Analisti, Test Mühendisi, Güvenlik Uzmanı vb.) simüle eden **8 farklı yapay zeka ajanı** kullanarak uçtan uca, uluslararası **ISTQB standartlarında** testler üretir.

### 🌟 Öne Çıkan Özellikler

1. **Multi-Agent Mimari (8 Ajanlı Yapı):** 
   Sistem, tek bir prompt yerine birbirini besleyen 8 farklı uzman yapay zeka ajanından oluşur.
2. **ISTQB Standartları Entegrasyonu:** 
   Oluşturulan testler basit "Happy Path" testleri değildir. Sistem Sınır Değer Analizi (BVA), Denklik Sınıfları (EP) ve Hata Tahminleme (Error Guessing) gibi mühendislik yaklaşımlarını kullanarak negatif ve uç durum senaryoları tasarlar.
3. **Anlık Oto-Onarım (Self-Healing):** 
   API kontratlarındaki değişiklikler veya anlık hatalar nedeniyle bir test patladığında (`FAILED`), sistem durmaz. Hata logunu (stacktrace) okuyarak LLM'e geri gönderir, hatayı tespit edip kodu otomatik düzeltir ve (`_Fixed_v1` olarak) tekrar çalıştırır.
4. **Karanlık (Dark Glassmorphism) Dashboard:** 
   Sistemi komut satırından kullanmak yerine modern bir arayüzden yönetebilirsiniz. Swagger URL ve User Story girerek anında test tetikleyebilir, ajanların analiz detaylarını rapor sekmesinden inceleyebilirsiniz.

### 🤖 Multi-Agent Mimarisi (Ajan Rolleri)

Sistemdeki 8 farklı LLM ajanı, yazılım döngüsünü taklit ederek sıralı bir şekilde çalışır:
* **Product Manager:** Gereksinimleri analiz edip iş risklerini ve kabul kriterlerini çıkarır.
* **Developer:** Swagger/API dokümanını teknik açıdan inceler ve veri/kısıtlama kurallarını belirler.
* **Test Analyst:** ISTQB kurallarına uygun pozitif ve negatif (BVA, EP) test stratejisi oluşturur.
* **Test Automation:** Analizleri çalıştırılabilir test kodlarına (Örn: Karate DSL) dönüştürür.
* **Performance:** SLA metriklerini belirleyip yük/performans testi gereksinimlerini raporlar.
* **DevOps:** Testlerin CI/CD ve Allure raporlama süreçlerine entegrasyon planını tasarlar.
* **SecOps:** OWASP güvenlik zafiyetleri (SQL Injection, XSS, 401/403) için koruma kuralları uygular.
* **Report:** Tüm verileri konsolide ederek nihai test kapsam oranlarını ve yönetici özetini çıkarır.

---

## 🧠 Kullanılan LLM Modeli

Projede varsayılan olarak **`gemma4:31b-cloud`** modeli kullanılmaktadır. Google'ın güçlü Gemma 4 serisinden olan bu model, 256K geniş bağlam (context window) kapasitesi sayesinde karmaşık Swagger dosyalarını ve çoklu ajan iletişimlerini tek seferde eksiksiz analiz edebilir. Altyapı istenirse Ollama üzerinden farklı yerel modellere de kolayca adapte edilebilecek şekilde geliştirilmiştir.

---

## ⚡ Hızlı Başlangıç

### Gereksinimler
- Java 17+
- Maven
- (İsteğe Bağlı) Docker & Ollama (Yerel LLM kullanımı için)

```bash
# Projeyi derleme ve çalıştırma
./mvnw clean install -DskipTests
./mvnw spring-boot:run -Dspring.profiles.active=local
```

### Uygulama Arayüzüne Erişim
Sistem ayağa kalktıktan sonra tarayıcınızdan aşağıdaki linke giderek sistemi kullanmaya başlayabilirsiniz:
**👉 http://localhost:8080**

---

## 📡 API ve Entegrasyonlar

Arayüz (Dashboard) arkasında çalışan güçlü REST API sayesinde sistemi kendi CI/CD pipeline'larınıza da entegre edebilirsiniz:

| Endpoint | HTTP | Açıklama |
|----------|------|----------|
| `/api/v1/tests/generate` | POST | Yeni bir test üretim isteği başlatır |
| `/api/v1/tests/{id}/run-all` | POST | Üretilen testleri derler ve koşar |
| `/api/v1/tests/{id}/llm-report`| GET | Her bir ajanın çıkardığı detaylı analizleri döndürür |

---

## 🔄 Self-Healing Akışı Nasıl Çalışır?

Kullanıcı arayüzünden veya Zamanlayıcıdan (Scheduler) tetiklenen testler, koşum sonrası `TestRunnerService` tarafından kontrol edilir. 
Eğer başarısız (FAILED) bir test bulunursa:
1. `FailureAnalysisService` hatayı analiz eder.
2. Orijinal kod ve hata çıktısı (stacktrace) bir araya getirilerek düzeltilmiş test senaryosu üretilir.
3. Yeni senaryo anında koşulur ve sonuçları nihai rapora eklenir.

---

## 💻 Geliştirme Ortamı

Spring Boot altyapısıyla geliştirilen projede, varsayılan olarak in-memory (H2) veritabanı kullanılır. Herhangi bir ekstra veritabanı kurulumuna ihtiyaç duymadan `local` profiliyle hemen çalıştırıp test edebilirsiniz.
