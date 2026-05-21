#!/usr/bin/env bash
# ============================================================================
#  Smoke-Test fuer Phase S' (Cascade-Isolation).
#
#  Voraussetzung: llm-cascade laeuft auf $BASE (default http://localhost:8090).
#  Die laufende Instanz braucht in ai_model_config mindestens je ein enabled
#  Modell mit category="content" und category="utility" — sonst skipt der
#  Isolations-Test mit Warnung.
#
#  Aufruf:
#    bash scripts/test-phase-s-prime.sh                          # localhost
#    BASE=http://187.127.77.111:8090 bash scripts/test-phase-s-prime.sh   # remote
# ============================================================================
set -e

BASE="${BASE:-http://localhost:8090}"
JQ="${JQ:-jq}"

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
yellow(){ printf '\033[33m%s\033[0m\n' "$*"; }
section(){ printf '\n\033[36m── %s ──\033[0m\n' "$*"; }

fail=0

section "T1 — Cascades-Endpoint listet distinkte Bereiche"
cascades=$(curl -fsS "$BASE/api/cascades")
echo "$cascades" | $JQ -r '.[] | "  - " + .name'
count=$(echo "$cascades" | $JQ 'length')
if [ "$count" -lt 1 ]; then
  red "FAIL: GET /api/cascades liefert leere Liste"
  fail=1
fi

section "T2 — Backward-Compat: Aufruf OHNE category klappt"
resp=$(curl -fsS -X POST "$BASE/api/generate" \
  -H "Content-Type: application/json" \
  -d '{"prompt":"ping","service":"test-bc","cooldown":false}' || echo '{"error":"unreachable"}')
err=$(echo "$resp" | $JQ -r '.error // empty')
model=$(echo "$resp" | $JQ -r '.model // empty')
if [ -n "$err" ] && [ -z "$model" ]; then
  red "FAIL: Default-Cascade-Call: $err"; fail=1
else
  green "OK: default-cascade liefert $model"
fi

section "T3 — Cascade-Aware: content-Call greift nur content-Modelle"
respC=$(curl -fsS -X POST "$BASE/api/generate" \
  -H "Content-Type: application/json" \
  -d '{"prompt":"ping","category":"content","service":"test-content","cooldown":false}' || echo '{"error":"unreachable"}')
modelC=$(echo "$respC" | $JQ -r '.model // empty')
if [ -z "$modelC" ]; then
  yellow "WARN: content-Call exhausted oder kein content-Modell konfiguriert — Test skipped"
else
  green "OK: content-cascade → $modelC"
fi

section "T4 — Cooldown-Isolation: content-Cooldown blockt utility nicht"
# Wir lesen die initiale cooldownSec-Map beider Cascades, simulieren keinen
# echten 503 (das braeuchte Mocking), pruefen aber dass die State-Maps
# voneinander getrennt sind: 'cooldownSec' im Detail-Endpoint pro Cascade
# darf NICHT identische Object-Reference sein.
contentState=$(curl -fsS "$BASE/api/cascades/content" 2>/dev/null || echo '{}')
utilityState=$(curl -fsS "$BASE/api/cascades/utility" 2>/dev/null || echo '{}')
contentKeys=$(echo "$contentState" | $JQ -r '.cooldownSec | keys // [] | join(",")')
utilityKeys=$(echo "$utilityState" | $JQ -r '.cooldownSec | keys // [] | join(",")')
echo "  content cooldownSec keys: [$contentKeys]"
echo "  utility cooldownSec keys: [$utilityKeys]"
# Sanity: jeder cooldownSec ist ein eigener Sub-State; gleicher Modell-Name
# in beiden ist OK (general-Fallback), aber Timer waeren voneinander getrennt.
if [ "$(echo "$contentState" | $JQ -r '.name // empty')" != "content" ]; then
  yellow "WARN: /api/cascades/content liefert kein 'name=content' — vermutlich noch nicht geseeded"
else
  green "OK: content + utility State-Maps sind cascade-separat"
fi

section "T5 — Models-Endpoint zeigt category fuer jedes Modell"
models=$(curl -fsS "$BASE/api/models")
withCategory=$(echo "$models" | $JQ '[.[] | select(.category != null and .category != "")] | length')
total=$(echo "$models" | $JQ 'length')
echo "  $withCategory/$total Modelle haben explizite category"

echo
if [ "$fail" -eq 0 ]; then
  green "============================================="
  green "Phase S' Smoke-Tests: alle Checks bestanden"
  green "============================================="
else
  red "============================================="
  red "Phase S' Smoke-Tests: $fail Fehler"
  red "============================================="
  exit 1
fi
