#!/usr/bin/env bash
# Supported local runtime bootstrap. This script verifies prerequisites, starts
# the declared Compose stack, and waits for health; it never installs software,
# pulls an LLM model, or destroys running resources.
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
info() { printf '%s\n' "$*"; }

command -v docker >/dev/null 2>&1 || fail "Docker Desktop/Engine is required."
docker info >/dev/null 2>&1 || fail "Docker is installed but not running."
docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is required."

if [[ ! -f .env ]]; then
  cp .env.example .env
  info "Created .env from .env.example. Configure secrets there or in your secret manager."
fi

info "Starting the declared local stack..."
docker compose up -d --build

deadline=$((SECONDS + 180))
until curl --fail --silent --show-error http://localhost:8080/api/v1/tests/health >/dev/null; do
  (( SECONDS < deadline )) || fail "Application did not become healthy within 180 seconds. Run: docker compose logs app"
  sleep 3
done

info "Ready: http://localhost:8080"
