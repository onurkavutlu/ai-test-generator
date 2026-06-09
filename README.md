# 🤖 AI Test Generator

**LLM destekli otomatik test case üreticisi**  
Karate DSL · Selenium · Appium · Allure · Self-Healing · Docker

> Ollama ile tamamen yerel veya `gemma4:31b-cloud` ile bulut tabanlı çalışır. OpenAI key gerekmez.

---

## ⚡ Hızlı Başlangıç

```bash
# 1. Kurulum (Docker + Ollama gerekli)
chmod +x setup.sh && ./setup.sh

# 2. Tam demo akışı
chmod +x demo-full-flow.sh && ./demo-full-flow.sh
```

---

## 🌐 Servisler

| Servis | URL | Açıklama |
|--------|-----|----------|
| **API** | http://localhost:8080 | REST API |
| **Swagger UI** | http://localhost:8080/swagger-ui.html | API dokümantasyonu |
| **MailHog** | http://localhost:8025 | Email önizleme |
| **Allure** | http://localhost:8888 | Test raporları |
| **Selenium Grid** | http://localhost:4444 | Browser grid |
| **LLM Raporu** | http://localhost:8080/api/v1/llm/summary | LLM istatistikleri |

---

## 📡 API

### Test Üretimi
```bash
curl -X POST http://localhost:8080/api/v1/tests/generate \
  -H 'Content-Type: application/json' \
  -d '{
    "testType": "BACKEND_API",
    "framework": "KARATE",
    "swaggerUrl": "https://petstore3.swagger.io/api/v3/openapi.json",
    "additionalContext": "PetStore CRUD ve hata senaryoları"
  }'
```

| Endpoint | Açıklama |
|----------|----------|
| `POST /api/v1/tests/generate` | Test üret |
| `GET /api/v1/tests/{id}` | Durum sorgula |
| `GET /api/v1/tests/{id}/cases` | Üretilen testleri listele |
| `POST /api/v1/tests/{id}/run-all` | Tümünü çalıştır + rapor + email |
| `POST /api/v1/scheduler/{id}/trigger-now` | Self-healing tetikle |
| `GET /api/v1/llm/calls` | LLM çağrı geçmişi |
| `GET /api/v1/llm/summary` | LLM özet istatistikleri |

---

## 🤖 LLM Modeli

Varsayılan: **`gemma4:31b-cloud`** — Google Gemma 4, 256K context, bulut tabanlı.

```bash
# Model çek
ollama pull gemma4:31b-cloud

# Farklı model kullanmak için .env dosyasında:
OLLAMA_MODEL=gemma4:31b-cloud
```

### OpenAI'ya geçiş
```
LLM_PROVIDER=openai
OPENAI_API_KEY=<your-key>
```

---

## 🔄 Self-Healing Akışı

```
Test FAILED
    ↓
FailureAnalysisService (LLM analiz)
    ↓
_Fixed_vN case üretilir
    ↓
Başarısız case "superseded" olarak işaretlenir
    ↓
Max 3 deneme (max-heal-attempts: 3)
```

---

## 📊 Test Akışı

```
POST /generate
    ↓
8-Agent AI Pipeline
(ProductManager → Developer → Analyst → TestAutomation → ...)
    ↓
Generator (Karate / Selenium / Appium)
    ↓
TestRunnerService (parallel)
    ↓
ReportOrchestrator
  ├── Allure Report → localhost:8888
  └── Email → MailHog localhost:8025
```

---

## 📁 Proje Yapısı

```
ai-test-generator/
├── src/main/java/com/testgen/
│   ├── agent/          8-agent AI pipeline
│   ├── controller/     REST API
│   ├── llm/            OllamaLlmService + LlmReportStore
│   ├── generator/      Karate / Selenium / Appium üreticiler
│   ├── runner/         TestRunnerService (parallel)
│   ├── scheduler/      DailySchedulerService + FailureAnalysisService
│   ├── report/         AllureReportService + ReportOrchestrator
│   └── notification/   EmailNotificationService
├── docker-compose.yml
├── Dockerfile
├── setup.sh
├── demo-full-flow.sh
└── .env.example
```

---

## 💻 IntelliJ ile Yerel Çalıştırma (H2, Docker'sız)

1. Projeyi aç → `pom.xml` otomatik algılanır
2. Run Configuration: `AiTestGeneratorApplication` → `-Dspring.profiles.active=local`
3. H2 in-memory DB, seed verisi otomatik yüklenir
4. Ollama kurulu ise LLM entegrasyonu aktif olur
