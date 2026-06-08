# 🤖 AI Test Generator — Ollama Edition

**LLM-powered otomatik test case üreticisi**  
Karate DSL · Selenium · Appium · Allure · Email · OpenShift · Jenkins

> **OpenAI key gerekmez.** Ollama ile tamamen yerel çalışır.

---

## 💻 IntelliJ IDEA ile Yerel Kurulum (Hızlı & Kolay - H2 Database)

Projeyi bilgisayarınızda (PostgreSQL kurmadan) doğrudan IntelliJ IDEA üzerinde çalıştırmak için:

1. **Projeyi Açın:** IntelliJ IDEA'yı açın ve `Open` diyerek projenin kök dizinini seçin. IDE, `pom.xml` dosyasını otomatik olarak algılayıp Maven bağımlılıklarını indirecektir.
2. **Hazır Çalıştırma Profilini Kullanın:** IntelliJ içinde otomatik olarak yüklenen `AiTestGeneratorApplication` çalıştırma profilini (Run Configuration) seçin ve çalıştırın. 
   - Bu profil, `-Dspring.profiles.active=local` parametresi ile uygulamayı **in-memory H2 Database** modunda başlatır. PostgreSQL kurulumu gerektirmez.
3. **Otomatik Seed Verileri:** Uygulama yerel modda ilk kez çalıştığında veritabanı boş ise `DataSeeder` devreye girerek örnek API, Web ve Mobil test isteklerini ve test case'lerini otomatik olarak yükler.
4. **Yapay Zeka (Ollama) Entegrasyonu (Opsiyonel):** LLM ile dinamik testler üretmek istiyorsanız, bilgisayarınızda **Ollama** uygulamasının kurulu olması ve `qwen2.5-coder:1.5b` modelinin çalışıyor olması yeterlidir. Model yoksa seed datası üzerinden testleri koşturabilir ve sistemi inceleyebilirsiniz.

---

## ⚡ Hızlı Başlangıç (3 adım)

### 1. Kopyala
```bash
cp .env.example .env
# .env dosyasını isteğe göre düzenle (opsiyonel)
```

### 2. Başlat
```bash
docker compose up -d
```

> İlk çalıştırmada `qwen2.5-coder:1.5b` (~900MB) otomatik indirilir.  
> İndirme bitene kadar bekle: `docker compose logs -f ollama-model-puller`

### 3. Test et
```bash
# Backend API test üret (Karate)
curl -X POST http://localhost:8080/api/v1/tests/generate \
  -H 'Content-Type: application/json' \
  -d '{
    "testType": "BACKEND_API",
    "framework": "KARATE",
    "swaggerUrl": "https://petstore3.swagger.io/api/v3/openapi.json",
    "additionalContext": "PetStore API test suite"
  }'

# Frontend test üret (Selenium)
curl -X POST http://localhost:8080/api/v1/tests/generate \
  -H 'Content-Type: application/json' \
  -d '{
    "testType": "FRONTEND_WEB",
    "framework": "SELENIUM",
    "applicationUrl": "http://localhost:3000",
    "userStory": "Kullanıcı login olup dashboard görüntüleyebilmeli"
  }'

# Mobil test üret (Appium)
curl -X POST http://localhost:8080/api/v1/tests/generate \
  -H 'Content-Type: application/json' \
  -d '{
    "testType": "MOBILE",
    "framework": "APPIUM",
    "appPackage": "com.example.myapp",
    "userStory": "Kullanıcı mobil uygulamaya giriş yapabilmeli",
    "additionalContext": "Android 13"
  }'
```

---

## 🌐 Servisler

| Servis | URL | Açıklama |
|--------|-----|----------|
| **API** | http://localhost:8080 | Ana REST API |
| **Swagger UI** | http://localhost:8080/swagger-ui.html | API dökümantasyon |
| **Ollama** | http://localhost:11434 | Yerel LLM |
| **MailHog** | http://localhost:8025 | Email önizleme |
| **Allure** | http://localhost:8888 | Test raporları |
| **Selenium Grid** | http://localhost:4444 | Browser grid |
| **PgAdmin** | http://localhost:5050 | DB yönetimi\* |

\* PgAdmin: `docker compose --profile tools up -d pgadmin`

---

## 📡 API Referansı

### `POST /api/v1/tests/generate`
```json
{
  "testType": "BACKEND_API | FRONTEND_WEB | MOBILE",
  "framework": "KARATE | SELENIUM | APPIUM",
  "userStory": "Kullanıcı X yapabilmeli",
  "swaggerUrl": "https://api.example.com/v3/openapi.json",
  "applicationUrl": "http://localhost:3000",
  "appPackage": "com.example.app",
  "additionalContext": "Ek bilgi"
}
```

### `GET /api/v1/tests/{requestId}` — Durum sorgula
### `GET /api/v1/tests/{requestId}/cases` — Üretilen testleri getir
### `POST /api/v1/tests/{requestId}/run-all` — Tümünü çalıştır + rapor + email
### `POST /api/v1/tests/cases/{id}/run` — Tek test çalıştır

---

## 🔄 LLM Provider Değiştirme

### Ollama → Daha güçlü model
`.env` dosyasında:
```
OLLAMA_MODEL=llama3:8b
# veya
OLLAMA_MODEL=mistral:7b-instruct
```
Sonra: `docker compose restart app`

### OpenAI'ya geçiş
`.env` dosyasında:
```
LLM_PROVIDER=openai
OPENAI_API_KEY=sk-...
```
Sonra: `docker compose restart app`

---

## 📊 Test Akışı

```
curl POST /generate
       ↓
  TestGenerationService (@Async)
       ↓
  LlmService (Ollama/OpenAI)
       ↓
  Generator (Karate/Selenium/Appium)
       ↓
  TestRunnerService
       ↓
  ReportOrchestrator
    ├── AllureReportService → /tmp/allure-report
    └── EmailNotificationService → MailHog (localhost:8025)
```

---

## 🏗️ OpenShift Deploy

```bash
# Namespace + tüm kaynaklar
oc apply -f k8s/deployment.yaml
oc apply -f k8s/allure-server.yaml

# Secret güncelle
oc create secret generic ai-test-generator-secrets \
  --from-literal=OPENAI_API_KEY=not-needed \
  --from-literal=DB_USER=testgen \
  --from-literal=DB_PASS=secure-password \
  -n ai-test-generator

# Durum
oc rollout status deployment/ai-test-generator -n ai-test-generator
```

---

## 🚀 Jenkins Pipeline

`jenkins/Jenkinsfile` — 11 stage:

1. Checkout
2. Build & Unit Tests (JaCoCo)
3. SonarQube (main/develop)
4. Docker Build
5. Push OCP Registry
6. Deploy Dev
7. Smoke Tests
8. 🤖 AI Test Generation (LLM)
9. ▶ Run AI Tests (parallel)
10. 📊 Allure Report + Email
11. Deploy Staging (manual onay)

---

## 📁 Proje Yapısı

```
ai-test-generator/
├── src/main/java/com/testgen/
│   ├── controller/     REST API (7 endpoint)
│   ├── service/        İş mantığı
│   ├── llm/            OllamaLlmService (default) / OpenAiLlmService
│   ├── generator/      Karate / Selenium / Appium üreticiler
│   ├── runner/         KarateRunner + TestRunnerService
│   ├── report/         AllureReportService + ReportOrchestrator
│   ├── notification/   EmailNotificationService (Thymeleaf)
│   ├── model/          JPA entity'ler
│   └── repository/     Spring Data JPA
├── src/main/resources/
│   ├── application.yml
│   └── templates/email/  HTML email şablonları
├── nginx/allure.conf    Allure nginx config
├── k8s/                 OCP manifest'leri
├── jenkins/Jenkinsfile  CI/CD pipeline
├── Dockerfile           Multi-stage build
├── docker-compose.yml   Tüm servisler
├── .env.example         Konfigürasyon şablonu
└── README.md
```
