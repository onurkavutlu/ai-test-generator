#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════
#  AI Test Generator — Tam Kurulum Scripti
#  Kullanım: chmod +x setup.sh && ./setup.sh
# ═══════════════════════════════════════════════════════════════
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
ok()   { echo -e "${GREEN}✓${NC} $*"; }
warn() { echo -e "${YELLOW}⚠${NC}  $*"; }
err()  { echo -e "${RED}✗${NC} $*"; exit 1; }
info() { echo -e "${BLUE}→${NC} $*"; }

echo ""
echo "╔══════════════════════════════════════════════════════╗"
echo "║        AI Test Generator — Setup & Launch           ║"
echo "╚══════════════════════════════════════════════════════╝"
echo ""

# ── 1. Docker kontrolü ─────────────────────────────────────────
info "Docker kontrol ediliyor..."
command -v docker &>/dev/null || err "Docker bulunamadı. https://www.docker.com/products/docker-desktop adresinden kur."
docker info &>/dev/null       || err "Docker çalışmıyor. Docker Desktop'ı başlat."
ok "Docker hazır"

# ── 2. Ollama kontrolü ─────────────────────────────────────────
info "Ollama kontrol ediliyor..."
OLLAMA_RUNNING=false
if command -v ollama &>/dev/null; then
  if curl -s http://localhost:11434/api/tags &>/dev/null; then
    OLLAMA_RUNNING=true
    ok "Ollama çalışıyor"
  else
    warn "Ollama kurulu ama çalışmıyor. Arka planda başlatılıyor..."
    ollama serve &>/dev/null &
    sleep 3
    if curl -s http://localhost:11434/api/tags &>/dev/null; then
      OLLAMA_RUNNING=true
      ok "Ollama başlatıldı"
    else
      warn "Ollama başlatılamadı — AI üretimi çalışmayacak, seed data üzerinden devam edilecek"
    fi
  fi
else
  warn "Ollama kurulu değil. AI test üretimi için: https://ollama.ai"
  warn "Devam ediliyor — seed data ile çalışabilirsin"
fi

# ── 3. Ollama modeli çek ───────────────────────────────────────
MODEL="qwen2.5-coder:1.5b"
if [ "$OLLAMA_RUNNING" = true ]; then
  info "Model kontrol ediliyor: $MODEL"
  if ollama list 2>/dev/null | grep -q "$MODEL"; then
    ok "Model zaten mevcut: $MODEL"
  else
    warn "Model bulunamadı, indiriliyor (~900MB)..."
    ollama pull "$MODEL"
    ok "Model indirildi: $MODEL"
  fi
fi

# ── 4. .env dosyası ────────────────────────────────────────────
if [ ! -f .env ]; then
  info ".env dosyası oluşturuluyor..."
  cp .env.example .env
  ok ".env oluşturuldu (.env.example'dan kopyalandı)"
else
  ok ".env zaten mevcut"
fi

# ── 5. Docker Compose başlat ───────────────────────────────────
info "Servisler başlatılıyor (postgres, mailhog, allure-server, selenium-hub, app)..."
docker compose down --remove-orphans 2>/dev/null || true
docker compose up -d --build

echo ""
info "Uygulama ayağa kalkıyor, health bekleniyor (max 3 dakika)..."

# ── 6. Health check ────────────────────────────────────────────
MAX_WAIT=180
ELAPSED=0
while [ $ELAPSED -lt $MAX_WAIT ]; do
  STATUS=$(curl -s http://localhost:8080/api/v1/tests/health 2>/dev/null | grep -c '"UP"' || true)
  if [ "$STATUS" -gt 0 ]; then
    echo ""
    ok "Uygulama hazır!"
    break
  fi
  printf "."
  sleep 5
  ELAPSED=$((ELAPSED + 5))
done

if [ $ELAPSED -ge $MAX_WAIT ]; then
  echo ""
  err "Uygulama 3 dakika içinde ayağa kalkmadı. Loglar: docker compose logs app"
fi

# ── 7. Özet ────────────────────────────────────────────────────
echo ""
echo "╔══════════════════════════════════════════════════════╗"
echo "║               Servisler Hazır  🚀                   ║"
echo "╠══════════════════════════════════════════════════════╣"
echo "║  API          → http://localhost:8080               ║"
echo "║  Swagger UI   → http://localhost:8080/swagger-ui.html║"
echo "║  Allure       → http://localhost:8888               ║"
echo "║  MailHog      → http://localhost:8025               ║"
echo "║  Selenium     → http://localhost:4444               ║"
echo "╠══════════════════════════════════════════════════════╣"
echo "║  Demo çalıştır: ./demo-full-flow.sh                 ║"
echo "╚══════════════════════════════════════════════════════╝"
echo ""
