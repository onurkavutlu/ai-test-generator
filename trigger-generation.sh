#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════════════════════
#  AI Test Generator — uçtan uca üretim tetikleyici
#
#  NE YAPAR
#    1. Ollama'yı ve modeli yoklar          → yoksa NEDENİYLE birlikte durur
#    2. Uygulamayı ve HEDEF API SPEC'İNİ yoklar → erişilemiyorsa açıkça durur
#    3. Üretimi tetikler (Swagger URL ile)
#    4. Bitene kadar bekler                  → GENERATING/GENERATED/FAILED izler
#    5. Üretilen case'leri, DOĞRULAMA durumlarını ve ajan analizlerini yazar
#    6. İstenirse testleri koşar
#
#  NEDEN BETİK: Üretim asenkron ve dakikalarca sürebilir. Tek bir curl "202 kabul
#  edildi" der ve biter; gerçekten ne üretildiğini görmek için durum yoklamak,
#  sonra da doğrulama sonuçlarına bakmak gerekir. Betik bu üç adımı birleştirir.
#
#  KULLANIM
#    ./trigger-generation.sh                                   # varsayılan: FakeRESTApi
#    ./trigger-generation.sh --swagger <url> --framework KARATE
#    ./trigger-generation.sh --story "Kullanıcı sipariş verebilmeli"
#    ./trigger-generation.sh --run                             # üretim sonrası testleri koş
# ══════════════════════════════════════════════════════════════════════════════
set -uo pipefail

APP="${APP_URL:-http://localhost:8080}"
OLLAMA="${OLLAMA_BASE_URL:-http://localhost:11434}"
# Varsayılan hedef: FakeRESTApi — gerçek OpenAPI 3 spec'i var, kimlik doğrulama
# istemez, tam CRUD sunar. --swagger ile istediğin API'ye çevirebilirsin.
SWAGGER="${SWAGGER_URL:-https://fakerestapi.azurewebsites.net/swagger/v1/swagger.json}"
FRAMEWORK="KARATE"
TEST_TYPE="BACKEND_API"
STORY="Kullanıcı kitap kayıtlarını listeleyebilmeli, ekleyebilmeli ve güncelleyebilmeli"
MAX_CASES=3
RUN_TESTS=0
TIMEOUT_MIN=20

while [[ $# -gt 0 ]]; do
  case "$1" in
    --swagger)   SWAGGER="$2"; shift 2 ;;
    --framework) FRAMEWORK="$2"; shift 2 ;;
    --type)      TEST_TYPE="$2"; shift 2 ;;
    --story)     STORY="$2"; shift 2 ;;
    --max)       MAX_CASES="$2"; shift 2 ;;
    --timeout)   TIMEOUT_MIN="$2"; shift 2 ;;
    --run)       RUN_TESTS=1; shift ;;
    -h|--help)   sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "Bilinmeyen argüman: $1"; exit 2 ;;
  esac
done

bold(){ printf '\033[1m%s\033[0m\n' "$*"; }
ok(){   printf '  \033[32m✓\033[0m %s\n' "$*"; }
bad(){  printf '  \033[31m✗\033[0m %s\n' "$*"; }
inf(){  printf '  · %s\n' "$*"; }

# jq olmayabilir; python3 her macOS'ta var
jqf(){ python3 -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }

# ── 1) Ollama ────────────────────────────────────────────────────────────────
bold "1/6  Ollama"
if ! curl -sf --max-time 5 "$OLLAMA/api/tags" -o /tmp/_tags.json 2>/dev/null; then
  bad "Ollama yanıt vermiyor: $OLLAMA"
  inf "Başlat:  ollama serve"
  inf "Farklı adresteyse:  OLLAMA_BASE_URL=http://host:11434 $0"
  exit 1
fi
MODELS=$(jqf "', '.join(m['name'] for m in d.get('models',[]))" < /tmp/_tags.json)
ok "Ollama ayakta — modeller: ${MODELS:-<yok>}"

WANT=$(curl -sf --max-time 5 "$APP/actuator/configprops" 2>/dev/null | grep -o '"model":"[^"]*"' | head -1 | cut -d'"' -f4)
WANT="${WANT:-llama3.1}"
if ! echo "$MODELS" | grep -q "${WANT%%:*}"; then
  bad "Uygulamanın beklediği model yüklü değil: $WANT"
  inf "İndir:  ollama pull $WANT"
  exit 1
fi
ok "Model hazır: $WANT"

# ── 2) Uygulama ──────────────────────────────────────────────────────────────
bold "2/6  Uygulama"
if ! curl -sf --max-time 5 "$APP/api/v1/tests/health" >/dev/null 2>&1; then
  bad "Uygulama yanıt vermiyor: $APP"
  inf "Başlat:  ./mvnw spring-boot:run -Dspring-boot.run.profiles=local"
  exit 1
fi
ok "Uygulama ayakta: $APP"

# Hedef API spec'i GERÇEKTEN erişilebilir mi? Erişilemiyorsa uygulama içeride
# opak bir hatayla düşer; burada durup nedeni açıkça söylemek daha dürüst.
SPEC_CODE=$(curl -s -o /tmp/_spec.json -w '%{http_code}' --max-time 15 "$SWAGGER" 2>/dev/null)
if [[ "$SPEC_CODE" != "200" ]]; then
  bad "Hedef API spec'ine erişilemedi: $SWAGGER (HTTP ${SPEC_CODE:-000})"
  inf "Ağ kapalıysa ya da API düştüyse üretim başlamaz — uydurma veriyle devam edilmez."
  inf "Başka bir API dene:  $0 --swagger <openapi-url>"
  exit 1
fi
if ! python3 -c "import json;d=json.load(open('/tmp/_spec.json'));assert d.get('openapi') or d.get('swagger')" 2>/dev/null; then
  bad "Adres yanıt verdi ama geçerli bir OpenAPI/Swagger dokümanı değil: $SWAGGER"
  head -c 200 /tmp/_spec.json; echo
  exit 1
fi
SPEC_INFO=$(python3 -c "
import json;d=json.load(open('/tmp/_spec.json'))
i=d.get('info',{});print('%s %s — %d endpoint' % (i.get('title','?'),i.get('version',''),len(d.get('paths',{}))))
" 2>/dev/null)
ok "Hedef API spec'i erişilebilir: ${SPEC_INFO:-$SWAGGER}"

# ── 3) Tetikle ───────────────────────────────────────────────────────────────
bold "3/6  Üretim tetikleniyor"
inf "framework=$FRAMEWORK  type=$TEST_TYPE  maxCases=$MAX_CASES"
inf "swagger=$SWAGGER"

REQ=$(python3 - "$SWAGGER" "$FRAMEWORK" "$TEST_TYPE" "$STORY" "$MAX_CASES" <<'PY'
import json,sys
sw,fw,tt,story,mx = sys.argv[1:6]
print(json.dumps({"swaggerUrl":sw,"framework":fw,"testType":tt,
                  "userStory":story,"maxCases":int(mx)}))
PY
)

RESP=$(curl -s -w '\n%{http_code}' -X POST "$APP/api/v1/tests/generate" \
        -H 'Content-Type: application/json' -d "$REQ")
CODE=$(tail -1 <<<"$RESP"); BODY=$(sed '$d' <<<"$RESP")

if [[ "$CODE" != "200" && "$CODE" != "202" ]]; then
  bad "Tetikleme reddedildi (HTTP $CODE)"
  echo "$BODY" | head -5
  exit 1
fi
RID=$(echo "$BODY" | jqf "d.get('id') or d.get('requestId')")
[[ -z "${RID:-}" ]] && { bad "requestId alınamadı"; echo "$BODY" | head -5; exit 1; }
ok "Kabul edildi — requestId: $RID"

# ── 4) Bekle ─────────────────────────────────────────────────────────────────
bold "4/6  Üretim bekleniyor (en fazla ${TIMEOUT_MIN} dk)"
DEADLINE=$(( $(date +%s) + TIMEOUT_MIN*60 )); LAST=""
while :; do
  ST=$(curl -sf --max-time 10 "$APP/api/v1/tests/$RID" | jqf "d.get('status','?')")
  [[ "$ST" != "$LAST" ]] && { inf "durum: $ST"; LAST="$ST"; }
  case "$ST" in
    GENERATED) ok "Üretim tamamlandı"; break ;;
    FAILED)
      bad "Üretim BAŞARISIZ"
      inf "Son LLM çağrıları:"
      curl -sf "$APP/api/v1/llm/calls" | python3 -c "
import sys,json
for c in json.load(sys.stdin)[-3:]:
    print('    %s %s success=%s %s' % (c.get('callType'),c.get('model'),
          c.get('success'), (c.get('errorMessage') or '')[:120]))
" 2>/dev/null
      inf "Uygulama logu:  tail -50 logs/application.log"
      exit 1 ;;
  esac
  [[ $(date +%s) -gt $DEADLINE ]] && { bad "Zaman aşımı (${TIMEOUT_MIN} dk)"; exit 1; }
  sleep 10
done

# ── 5) Sonuçlar ──────────────────────────────────────────────────────────────
bold "5/6  Üretilen test case'leri"
curl -sf "$APP/api/v1/tests/$RID/cases" | python3 -c "
import sys,json
cs=json.load(sys.stdin)
if not cs: print('  (hiç case üretilmedi)'); raise SystemExit
print('  %-34s %-9s %-10s %s' % ('CASE','FRAMEWORK','DOĞRULAMA','KATEGORİ'))
for c in cs:
    print('  %-34s %-9s %-10s %s' % (
        (c.get('testName') or '')[:34],
        c.get('framework') or '-',
        c.get('validationStatus') or '-',
        c.get('testCategory') or '-'))
bad=[c for c in cs if c.get('validationStatus')=='INVALID']
print()
print('  toplam: %d   geçerli: %d   geçersiz: %d'
      % (len(cs), sum(1 for c in cs if c.get('validationStatus')=='VALID'), len(bad)))
for c in bad:
    print('  ! %s → %s' % (c.get('testName'), (c.get('validationError') or '')[:150]))
"

bold "6/6  LLM ve ajan özeti"
curl -sf "$APP/api/v1/llm/summary" | python3 -c "
import sys,json;d=json.load(sys.stdin)
print('  çağrı: %s (başarılı %s / başarısız %s)  ort. süre: %s ms  prompt token: ~%s'
      % (d['totalCalls'],d['successCalls'],d['failedCalls'],d['avgDurationMs'],d['totalPromptTokens']))
" 2>/dev/null
inf "Ajan analizleri:  $APP/tests/$RID/llm-report"
inf "Cucumber raporu:  $APP/reports/cucumber/$RID"

# ── opsiyonel: koş ───────────────────────────────────────────────────────────
if [[ $RUN_TESTS -eq 1 ]]; then
  bold "Testler koşuluyor"
  curl -s -X POST "$APP/api/v1/tests/$RID/run-all" >/dev/null
  inf "Koşum başlatıldı (asenkron). İzle:  $APP/api/v1/tests/$RID/cases"
  sleep 20
  curl -sf "$APP/api/v1/tests/$RID/cases" | python3 -c "
import sys,json
for c in json.load(sys.stdin):
    print('  %-34s %s (%s/%s senaryo)' % ((c.get('testName') or '')[:34],
          c.get('runStatus') or 'NOT_RUN', c.get('passedScenarios'), c.get('totalScenarios')))
"
fi

echo
bold "Bitti — requestId: $RID"
