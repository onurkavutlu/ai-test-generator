#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════════════════════
#  AI Test Generator — tek komutluk kurulum ve başlatma
#
#  NE YAPAR
#    Gereken her şeyi SIRAYLA yoklar, eksik olanı kurar, sonra sistemi başlatır.
#    Her adımda ne bulduğunu ve ne yaptığını yazar; hiçbir şeyi sessizce atlamaz.
#
#  KAPSAM
#    Java 17+ · Maven wrapper · Ollama + model · uygulama · (opsiyonel) graphify
#
#  KULLANIM
#    ./kurulum.sh              # yokla, kur, başlat
#    ./kurulum.sh --check      # yalnızca yokla, hiçbir şey kurma/başlatma
#    ./kurulum.sh --no-start   # kur ama uygulamayı başlatma
#    ./kurulum.sh --with-graphify
# ══════════════════════════════════════════════════════════════════════════════
set -uo pipefail
cd "$(dirname "$0")"

CHECK_ONLY=0; NO_START=0; WITH_GRAPHIFY=0
MODEL="${OLLAMA_MODEL:-llama3.1}"
OLLAMA="${OLLAMA_BASE_URL:-http://localhost:11434}"
APP="${APP_URL:-http://localhost:8080}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --check) CHECK_ONLY=1; shift ;;
    --no-start) NO_START=1; shift ;;
    --with-graphify) WITH_GRAPHIFY=1; shift ;;
    --model) MODEL="$2"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "Bilinmeyen argüman: $1"; exit 2 ;;
  esac
done

bold(){ printf '\n\033[1m%s\033[0m\n' "$*"; }
ok(){   printf '  \033[32m✓\033[0m %s\n' "$*"; }
bad(){  printf '  \033[31m✗\033[0m %s\n' "$*"; }
warn(){ printf '  \033[33m!\033[0m %s\n' "$*"; }
inf(){  printf '  · %s\n' "$*"; }

MISSING=0
need(){ MISSING=$((MISSING+1)); }

# ── 1) Java ──────────────────────────────────────────────────────────────────
bold "1/6  Java 17+"
if command -v java >/dev/null 2>&1; then
  # head -1 KULLANILMAZ: bazı ortamlarda ilk satır "Picked up JAVA_TOOL_OPTIONS…"
  # olur ve sürüm boş çıkar (bu betiği koştururken bizzat yaşandı). Sürüm satırı
  # nerede olursa olsun aranır.
  JV=$(java -version 2>&1 | grep -oE '(openjdk|java) version "[0-9]+' \
       | grep -oE '[0-9]+$' | head -1)
  if [[ -n "${JV:-}" && "$JV" -ge 17 ]]; then
    ok "Java $JV bulundu"
  else
    bad "Java $JV çok eski (17+ gerekli)"; need
    inf "Kur:  brew install openjdk@21  &&  sudo ln -sfn \$(brew --prefix)/opt/openjdk@21/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-21.jdk"
  fi
else
  bad "Java yok"; need
  inf "Kur:  brew install openjdk@21"
fi

# ── 2) Maven wrapper ─────────────────────────────────────────────────────────
bold "2/6  Maven"
if [[ -x ./mvnw ]]; then
  ok "Proje wrapper'ı hazır (./mvnw) — ayrıca Maven kurmaya gerek yok"
elif [[ -f ./mvnw ]]; then
  chmod +x ./mvnw && ok "./mvnw çalıştırılabilir yapıldı"
else
  bad "./mvnw bulunamadı — proje kökünde misin?"; need
fi

# ── 3) Ollama ────────────────────────────────────────────────────────────────
bold "3/6  Ollama"
if ! command -v ollama >/dev/null 2>&1; then
  bad "Ollama kurulu değil"
  if [[ $CHECK_ONLY -eq 1 ]]; then need; else
    inf "Kuruluyor: brew install ollama"
    if command -v brew >/dev/null 2>&1 && brew install ollama; then
      ok "Ollama kuruldu"
    else
      bad "Kurulamadı — elle: https://ollama.com/download"; need
    fi
  fi
else
  ok "Ollama kurulu: $(ollama --version 2>/dev/null | head -1)"
fi

if command -v ollama >/dev/null 2>&1; then
  if curl -sf --max-time 5 "$OLLAMA/api/tags" -o /tmp/_tags.json 2>/dev/null; then
    ok "Ollama servisi ayakta: $OLLAMA"
  else
    warn "Ollama servisi kapalı — başlatılıyor (arka planda)"
    if [[ $CHECK_ONLY -eq 0 ]]; then
      nohup ollama serve >/tmp/ollama.log 2>&1 &
      for i in $(seq 1 15); do
        sleep 2
        curl -sf --max-time 3 "$OLLAMA/api/tags" -o /tmp/_tags.json 2>/dev/null && break
      done
      curl -sf --max-time 3 "$OLLAMA/api/tags" >/dev/null 2>&1 \
        && ok "Ollama başlatıldı" || { bad "Ollama başlatılamadı (bkz. /tmp/ollama.log)"; need; }
    else need; fi
  fi

  if [[ -s /tmp/_tags.json ]]; then
    if python3 -c "
import json,sys
d=json.load(open('/tmp/_tags.json'))
sys.exit(0 if any('${MODEL%%:*}' in m['name'] for m in d.get('models',[])) else 1)
" 2>/dev/null; then
      ok "Model hazır: $MODEL"
    else
      warn "Model yüklü değil: $MODEL"
      if [[ $CHECK_ONLY -eq 0 ]]; then
        inf "İndiriliyor (birkaç GB, sabır)…"
        ollama pull "$MODEL" && ok "Model indirildi" || { bad "Model indirilemedi"; need; }
      else need; fi
    fi
  fi
fi

# ── 4) Bağımlılıklar + derleme ───────────────────────────────────────────────
bold "4/6  Bağımlılıklar ve derleme"
if [[ $CHECK_ONLY -eq 1 ]]; then
  inf "atlandı (--check)"
else
  if ./mvnw -B -q -DskipTests package 2>&1 | tail -5; then
    ok "Derlendi"
  else
    bad "Derleme başarısız"; need
  fi
fi

# ── 5) graphify (opsiyonel) ──────────────────────────────────────────────────
bold "5/6  Graphify (opsiyonel bilgi grafiği)"
if [[ $WITH_GRAPHIFY -eq 0 ]]; then
  inf "atlandı (--with-graphify ile açılır)"
elif command -v graphify >/dev/null 2>&1; then
  ok "graphify kurulu: $(graphify --version 2>/dev/null)"
else
  if command -v uv >/dev/null 2>&1 || brew install uv; then
    uv tool install graphifyy && ok "graphify kuruldu" || warn "graphify kurulamadı (kritik değil)"
  else
    warn "uv kurulamadı — graphify atlandı"
  fi
fi

# ── 6) Başlat ────────────────────────────────────────────────────────────────
bold "6/6  Uygulama"
if [[ $MISSING -gt 0 ]]; then
  bad "$MISSING eksik var — yukarıdaki adımları tamamlayıp tekrar çalıştır."
  exit 1
fi
if [[ $CHECK_ONLY -eq 1 ]]; then
  ok "Tüm gereksinimler hazır (--check modunda başlatılmadı)"
  exit 0
fi
if [[ $NO_START -eq 1 ]]; then
  ok "Kurulum tamam (--no-start)"
  inf "Başlatmak için:  ./mvnw spring-boot:run -Dspring-boot.run.profiles=local"
  exit 0
fi

if curl -sf --max-time 3 "$APP/api/v1/tests/health" >/dev/null 2>&1; then
  ok "Uygulama zaten ayakta: $APP"
else
  inf "Başlatılıyor (arka planda, log: logs/app-start.log)…"
  mkdir -p logs
  nohup ./mvnw spring-boot:run -Dspring-boot.run.profiles=local > logs/app-start.log 2>&1 &
  for i in $(seq 1 40); do
    sleep 3
    curl -sf --max-time 3 "$APP/api/v1/tests/health" >/dev/null 2>&1 && break
  done
  if curl -sf --max-time 3 "$APP/api/v1/tests/health" >/dev/null 2>&1; then
    ok "Uygulama ayağa kalktı"
  else
    bad "Uygulama başlatılamadı — bkz. logs/app-start.log"
    exit 1
  fi
fi

bold "Hazır"
inf "Dashboard        : $APP"
inf "Swagger UI       : $APP/swagger-ui/index.html"
inf "Endpoint Comparer: $APP/comparer"
inf "Üretimi tetikle  : ./trigger-generation.sh --run"
command -v open >/dev/null 2>&1 && open "$APP" 2>/dev/null || true
