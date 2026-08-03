#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════
#  AI Test Generator — Tam Demo Akışı
#
#  Yapılanlar:
#    1. Karate (API) test üret + koştur
#    2. Selenium (Web) test üret + koştur
#    3. Hatalı test case ekle
#    4. Scheduler'ı tetikle → AI hatalı case'i analiz edip iyileştirir
#    5. Allure & MailHog linklerini göster
#
#  Kullanım: chmod +x demo-full-flow.sh && ./demo-full-flow.sh
# ═══════════════════════════════════════════════════════════════
set -euo pipefail

BASE_URL="http://localhost:8080"
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

ok()      { echo -e "${GREEN}✓${NC} $*"; }
warn()    { echo -e "${YELLOW}⚠${NC}  $*"; }
err()     { echo -e "${RED}✗${NC} $*"; exit 1; }
info()    { echo -e "${BLUE}→${NC} $*"; }
section() { echo ""; echo -e "${BOLD}${CYAN}══ $* ══${NC}"; echo ""; }
pause()   { echo ""; read -rp "  ▶ Devam etmek için Enter'a bas..." _; echo ""; }

# ── Yardımcı fonksiyonlar ──────────────────────────────────────

# Uygulama sağlık kontrolü
check_app() {
  curl -sf "$BASE_URL/api/v1/tests/health" &>/dev/null \
    || err "Uygulama ayakta değil. Önce ./setup.sh çalıştır."
}

# POST isteği gönder, requestId döndür
generate_tests() {
  local description="$1"
  local payload="$2"
  info "Test üretimi başlatılıyor: $description"
  local response
  response=$(curl -sf -X POST "$BASE_URL/api/v1/tests/generate?autoRun=false" \
    -H 'Content-Type: application/json' \
    -d "$payload")
  local req_id
  req_id=$(echo "$response" | python3 -c "import sys,json; print(json.load(sys.stdin)['requestId'])")
  echo "$req_id"
}

# Üretim tamamlanana kadar bekle (GENERATED veya FAILED)
wait_generated() {
  local req_id="$1"
  local max=120
  local elapsed=0
  printf "  Üretim bekleniyor"
  while [ $elapsed -lt $max ]; do
    local status
    status=$(curl -sf "$BASE_URL/api/v1/tests/$req_id" \
      | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])" 2>/dev/null || echo "PENDING")
    if [ "$status" = "GENERATED" ] || [ "$status" = "FAILED" ]; then
      echo " $status"
      echo "$status"
      return
    fi
    printf "."
    sleep 4
    elapsed=$((elapsed + 4))
  done
  echo " TIMEOUT"
  echo "TIMEOUT"
}

# Test case'leri listele
list_cases() {
  local req_id="$1"
  curl -sf "$BASE_URL/api/v1/tests/$req_id/cases" \
    | python3 -c "
import sys, json
cases = json.load(sys.stdin)
for c in cases:
    status_icon = '✓' if c['runStatus'] == 'PASSED' else ('✗' if c['runStatus'] == 'FAILED' else '○')
    print(f\"  {status_icon} [{c['framework']}] {c['testName']} — {c['runStatus']}\")
print(f'  Toplam: {len(cases)} case')
"
}

# Tüm testleri koştur ve sonucu bekle
run_and_report() {
  local req_id="$1"
  local recipients="${2:-demo@testgen.local}"
  info "Testler koşturuluyor + Allure raporu + email gönderiliyor..."
  curl -sf -X POST "$BASE_URL/api/v1/tests/$req_id/run-all?recipients=$recipients" \
    -H 'Content-Type: application/json' &>/dev/null
  sleep 3
  ok "Koşum başlatıldı — Allure: http://localhost:8888 | Email: http://localhost:8025"
}

# ══════════════════════════════════════════════════════════════
#  BAŞLANGIÇ
# ══════════════════════════════════════════════════════════════

clear
echo ""
echo "╔══════════════════════════════════════════════════════════╗"
echo "║         AI Test Generator — Full Demo Akışı            ║"
echo "║      Karate · Selenium · Hata Düzeltme · Rapor         ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""

check_app
ok "Uygulama hazır: $BASE_URL"

# ══════════════════════════════════════════════════════════════
section "ADIM 1 — Karate DSL (API Testi) Üretimi"
# ══════════════════════════════════════════════════════════════

KARATE_PAYLOAD='{
  "testType": "BACKEND_API",
  "framework": "KARATE",
  "swaggerUrl": "https://petstore3.swagger.io/api/v3/openapi.json",
  "additionalContext": "PetStore API için CRUD ve hata senaryoları: pet oluşturma, listeleme, güncelleme, silme ve 404 kontrolü"
}'

KARATE_ID=$(generate_tests "Karate / PetStore API" "$KARATE_PAYLOAD")
ok "RequestId: $KARATE_ID"

KARATE_STATUS=$(wait_generated "$KARATE_ID" | tail -1)
if [ "$KARATE_STATUS" = "GENERATED" ]; then
  ok "Karate testleri üretildi:"
  list_cases "$KARATE_ID"
else
  warn "Üretim tamamlanamadı ($KARATE_STATUS) — Ollama çalışıyor mu kontrol et"
fi

pause

# ══════════════════════════════════════════════════════════════
section "ADIM 2 — Selenium (Web UI Testi) Üretimi"
# ══════════════════════════════════════════════════════════════

SELENIUM_PAYLOAD='{
  "testType": "FRONTEND_WEB",
  "framework": "SELENIUM",
  "applicationUrl": "https://www.saucedemo.com",
  "userStory": "Kullanıcı doğru kullanıcı adı ve şifre ile login olabilmeli, yanlış şifrede hata mesajı görmeli, login sonrası ürün listesini görebilmeli"
}'

SELENIUM_ID=$(generate_tests "Selenium / SauceDemo Login" "$SELENIUM_PAYLOAD")
ok "RequestId: $SELENIUM_ID"

SELENIUM_STATUS=$(wait_generated "$SELENIUM_ID" | tail -1)
if [ "$SELENIUM_STATUS" = "GENERATED" ]; then
  ok "Selenium testleri üretildi:"
  list_cases "$SELENIUM_ID"
else
  warn "Üretim tamamlanamadı ($SELENIUM_STATUS)"
fi

pause

# ══════════════════════════════════════════════════════════════
section "ADIM 3 — Hatalı Test Case Ekleme"
# ══════════════════════════════════════════════════════════════

info "Kasıtlı olarak hatalı (başarısız olacak) bir Karate test case'i Karate isteğine ekleniyor..."

BROKEN_TEST='Feature: Broken Pet Test — Bu test kasıtlı olarak başarısız üretilmiştir

  Background:
    * url '\''https://petstore3.swagger.io/api/v3'\''

  Scenario: Get pet with broken assertion — will FAIL
    Given path '\''/pet/999999'\''
    When method GET
    Then status 200
    # BUG: 999999 id mevcut değil, 404 döner ama 200 bekliyoruz
    And match response.id == 999999
    And match response.name == '\''definitely-exists'\'''

BROKEN_PAYLOAD=$(python3 -c "
import json
content = open('/dev/stdin').read()
payload = {
  'testName': 'BrokenPetLookupTest',
  'fileName': 'BrokenPetLookupTest.feature',
  'testContent': content,
  'testSummary': 'BUG: 404 dönen endpoint için 200 bekleniyor — kasıtlı hatalı test',
  'framework': 'KARATE',
  'runStatus': 'FAILED'
}
print(json.dumps(payload))
" <<< "$BROKEN_TEST")

BROKEN_CASE_ID=$(curl -sf -X POST "$BASE_URL/api/v1/tests/$KARATE_ID/cases" \
  -H 'Content-Type: application/json' \
  -d "$BROKEN_PAYLOAD" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")

ok "Hatalı case eklendi — ID: $BROKEN_CASE_ID"

echo ""
info "Şu anki durum (hatalı case dahil):"
list_cases "$KARATE_ID"

pause

# ══════════════════════════════════════════════════════════════
section "ADIM 5 — Scheduler ile AI Self-Healing (Otomatik İyileştirme)"
# ══════════════════════════════════════════════════════════════

info "Karate isteği günlük schedule'a ekleniyor (autoGenerate=true)..."
curl -sf -X POST "$BASE_URL/api/v1/scheduler/$KARATE_ID/enable?autoGenerate=true" \
  -H 'Content-Type: application/json' &>/dev/null
ok "Schedule aktif edildi"

echo ""
info "Scheduler hemen tetikleniyor → AI başarısız case'leri analiz edip yeni test üretecek..."
HEAL_RESPONSE=$(curl -sf -X POST "$BASE_URL/api/v1/scheduler/$KARATE_ID/trigger-now" \
  -H 'Content-Type: application/json' || echo '{}')

echo ""
echo "$HEAL_RESPONSE" | python3 -c "
import sys, json
try:
    r = json.load(sys.stdin)
    total    = r.get('totalCases', '?')
    passed   = r.get('passedCases', '?')
    failed   = r.get('failedCases', '?')
    improved = r.get('newCasesGenerated', '?')
    print(f'  Toplam case : {total}')
    print(f'  Geçti       : {passed}')
    print(f'  Başarısız   : {failed}')
    print(f'  AI iyileştirdi (yeni case) : {improved}')
except:
    print('  (Sonuç ayrıntısı mevcut değil — Allure raporuna bak)')
" 2>/dev/null || true

echo ""
info "Güncel case listesi (iyileştirmeden sonra):"
list_cases "$KARATE_ID"

pause

# ══════════════════════════════════════════════════════════════
section "ADIM 6 — Tüm Framework'leri Koştur + Rapor + Email"
# ══════════════════════════════════════════════════════════════

info "Karate testleri koşturuluyor..."
run_and_report "$KARATE_ID" "qa@testgen.local"

info "Selenium testleri koşturuluyor..."
run_and_report "$SELENIUM_ID" "qa@testgen.local"



# ══════════════════════════════════════════════════════════════
section "ADIM 7 — LLM Üretim Raporu"
# ══════════════════════════════════════════════════════════════

info "LLM çağrı istatistikleri alınıyor..."
sleep 2
LLM_SUMMARY=$(curl -sf "$BASE_URL/api/v1/llm/summary" 2>/dev/null || echo '{}')

echo ""
echo "$LLM_SUMMARY" | python3 -c "
import sys, json
try:
    r = json.load(sys.stdin)
    print(f\"  Toplam LLM çağrısı      : {r.get('totalCalls','?')}\")
    print(f\"  Başarılı                 : {r.get('successCalls','?')}\")
    print(f\"  Başarısız                : {r.get('failedCalls','?')}\")
    print(f\"  Ortalama süre            : {r.get('avgDurationMs','?')} ms\")
    print(f\"  Toplam prompt token (~)  : {r.get('totalPromptTokens','?')}\")
    print(f\"  Toplam yanıt token (~)   : {r.get('totalResponseTokens','?')}\")
except Exception as e:
    print(f'  (LLM raporu alınamadı: {e})')
" 2>/dev/null || true

echo ""
info "Çağrı tipine göre dağılım:"
curl -sf "$BASE_URL/api/v1/llm/calls" 2>/dev/null | python3 -c "
import sys, json
try:
    calls = json.load(sys.stdin)
    by_type = {}
    for c in calls:
        t = c.get('callType','?')
        by_type[t] = by_type.get(t, 0) + 1
    for t, cnt in sorted(by_type.items()):
        icon = '✓' if t not in ('FAILURE_ANALYSIS',) else '🔧'
        print(f'  {icon} {t:25s}: {cnt} çağrı')
except Exception as e:
    print(f'  (Detay alınamadı: {e})')
" 2>/dev/null || true

echo ""
info "Son 3 LLM çağrısının prompt özeti:"
curl -sf "$BASE_URL/api/v1/llm/calls" 2>/dev/null | python3 -c "
import sys, json
try:
    calls = json.load(sys.stdin)
    for c in calls[-3:]:
        ok = '✓' if c.get('success') else '✗'
        print(f\"  {ok} [{c.get('callType','?')}] {c.get('durationMs','?')}ms — {c.get('promptSummary','')[:80]}...\")
except:
    pass
" 2>/dev/null || true

# ══════════════════════════════════════════════════════════════
section "Demo Tamamlandı"
# ══════════════════════════════════════════════════════════════

echo "╔══════════════════════════════════════════════════════════╗"
echo "║                   Sonuç Özeti  🎉                       ║"
echo "╠══════════════════════════════════════════════════════════╣"
printf "║  %-52s ║\n" "Karate  requestId : $KARATE_ID"
printf "║  %-52s ║\n" "Selenium requestId: $SELENIUM_ID"

echo "╠══════════════════════════════════════════════════════════╣"
echo "║  Allure Raporu  → http://localhost:8888                 ║"
echo "║  Email Inbox    → http://localhost:8025                 ║"
echo "║  Swagger UI     → http://localhost:8080/swagger-ui.html ║"
echo "║  LLM Çağrıları  → http://localhost:8080/api/v1/llm/calls║"
echo "║  LLM Özet       → http://localhost:8080/api/v1/llm/summary║"
echo "╠══════════════════════════════════════════════════════════╣"
echo "║  Case detayları:                                         ║"
printf "║    curl %s/api/v1/tests/%s/cases\n" "$BASE_URL" "$KARATE_ID"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""
