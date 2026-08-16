#!/usr/bin/env bash

set -u

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ $# -lt 2 ]]; then
  echo "Kullanım: $0 <rapor-etiketi> <maven-argümanları...>" >&2
  exit 2
fi

LABEL="$1"
shift

if command -v /usr/libexec/java_home >/dev/null 2>&1; then
  JAVA_17_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  if [[ -n "$JAVA_17_HOME" ]]; then
    export JAVA_HOME="$JAVA_17_HOME"
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
fi

JAVA_VERSION="$(${JAVA_HOME:+"$JAVA_HOME/bin/"}java -version 2>&1 | head -n 1)"
if [[ "$JAVA_VERSION" != *'"17.'* ]]; then
  echo "Test koşumu reddedildi: Java 17 gerekli, bulunan: $JAVA_VERSION" >&2
  exit 2
fi

TIMESTAMP="$(date '+%Y%m%d-%H%M%S')"
SLUG="$(printf '%s' "$LABEL" | tr '[:upper:]' '[:lower:]' | tr -cs '[:alnum:]' '-' | sed 's/^-//;s/-$//')"
REPORT_DIR="$PROJECT_ROOT/docs/test-reports"
# `clean` hedefi target/ altını Maven çalışırken siler. Koşum günlüğü rapor
# üretiminden önce kaybolmasın diye kalıcı ama .gitignore'daki logs/ altında tut.
LOG_DIR="$REPORT_DIR/logs"
REPORT_FILE="$REPORT_DIR/$TIMESTAMP-$SLUG.md"
LOG_FILE="$LOG_DIR/$TIMESTAMP-$SLUG.log"

mkdir -p "$REPORT_DIR" "$LOG_DIR"

# JaCoCo agent varsayılan olarak mevcut exec verisine ekleme yapabildiği için her
# koşum öncesi eski kapsam kanıtını arşivle. Böylece rapor yalnız bu komutun
# çalıştırdığı testleri ölçer; önceki koşum yeni kapsam gibi gösterilmez.
for COVERAGE_ARTIFACT in \
  "$PROJECT_ROOT/target/jacoco.exec" \
  "$PROJECT_ROOT/target/jacoco-external.exec" \
  "$PROJECT_ROOT/target/site/jacoco/jacoco.csv"; do
  if [[ -f "$COVERAGE_ARTIFACT" ]]; then
    ARTIFACT_NAME="$(basename "$COVERAGE_ARTIFACT")"
    mv "$COVERAGE_ARTIFACT" "$LOG_DIR/$TIMESTAMP-$SLUG-preexisting-$ARTIFACT_NAME"
  fi
done

START_NS="$(python3 -c 'import time; print(time.time_ns())')"
START_ISO="$(date '+%Y-%m-%dT%H:%M:%S%z')"
COMMAND="$(printf '%q ' "$PROJECT_ROOT/mvnw" "$@")"

set +e
(cd "$PROJECT_ROOT" && "$PROJECT_ROOT/mvnw" "$@") 2>&1 | tee "$LOG_FILE"
MAVEN_EXIT=${PIPESTATUS[0]}
set -e

END_ISO="$(date '+%Y-%m-%dT%H:%M:%S%z')"
END_NS="$(python3 -c 'import time; print(time.time_ns())')"

python3 "$PROJECT_ROOT/scripts/render-test-report.py" \
  --project-root "$PROJECT_ROOT" \
  --label "$LABEL" \
  --command "$COMMAND" \
  --java-version "$JAVA_VERSION" \
  --start-iso "$START_ISO" \
  --end-iso "$END_ISO" \
  --start-ns "$START_NS" \
  --end-ns "$END_NS" \
  --exit-code "$MAVEN_EXIT" \
  --log-file "$LOG_FILE" \
  --report-file "$REPORT_FILE"

echo "TEST_REPORT=$REPORT_FILE"
exit "$MAVEN_EXIT"
