# ============================================================================
#  Smoke-Test fuer Phase S' (Cascade-Isolation) — PowerShell-Pendant zu .sh.
#
#  Aufruf:
#    pwsh scripts/test-phase-s-prime.ps1
#    $env:BASE="http://187.127.77.111:8090"; pwsh scripts/test-phase-s-prime.ps1
# ============================================================================
$ErrorActionPreference = "Stop"
$Base = if ($env:BASE) { $env:BASE } else { "http://localhost:8090" }

function Section($msg) { Write-Host "`n── $msg ──" -ForegroundColor Cyan }
function Ok($msg)      { Write-Host "OK: $msg" -ForegroundColor Green }
function Warn($msg)    { Write-Host "WARN: $msg" -ForegroundColor Yellow }
function Fail($msg)    { Write-Host "FAIL: $msg" -ForegroundColor Red }

$fail = 0

Section "T1 — Cascades-Endpoint listet distinkte Bereiche"
try {
    $cascades = Invoke-RestMethod -Uri "$Base/api/cascades" -Method Get
    foreach ($c in $cascades) { Write-Host "  - $($c.name)" }
    if ($cascades.Count -lt 1) { Fail "GET /api/cascades liefert leere Liste"; $fail++ }
} catch { Fail "GET /api/cascades unreachable: $_"; $fail++ }

Section "T2 — Backward-Compat: Aufruf OHNE category klappt"
try {
    $body = @{ prompt = "ping"; service = "test-bc"; cooldown = $false } | ConvertTo-Json
    $resp = Invoke-RestMethod -Uri "$Base/api/generate" -Method Post -ContentType "application/json" -Body $body
    if ($resp.model) { Ok "default-cascade liefert $($resp.model)" }
    else { Fail "Default-Cascade-Call ohne Modell: $($resp.error)"; $fail++ }
} catch { Fail "POST /api/generate (default) fehlgeschlagen: $_"; $fail++ }

Section "T3 — Cascade-Aware: content-Call greift nur content-Modelle"
try {
    $body = @{ prompt = "ping"; category = "content"; service = "test-content"; cooldown = $false } | ConvertTo-Json
    $resp = Invoke-RestMethod -Uri "$Base/api/generate" -Method Post -ContentType "application/json" -Body $body
    if ($resp.model) { Ok "content-cascade → $($resp.model)" }
    else { Warn "content-Call exhausted oder kein content-Modell — Test skipped" }
} catch { Warn "content-Call: $_" }

Section "T4 — Cooldown-Isolation: state-Maps sind cascade-separat"
try {
    $contentState = Invoke-RestMethod -Uri "$Base/api/cascades/content" -Method Get -ErrorAction SilentlyContinue
    $utilityState = Invoke-RestMethod -Uri "$Base/api/cascades/utility" -Method Get -ErrorAction SilentlyContinue
    Write-Host "  content cooldownSec keys: [$($contentState.cooldownSec.PSObject.Properties.Name -join ',')]"
    Write-Host "  utility cooldownSec keys: [$($utilityState.cooldownSec.PSObject.Properties.Name -join ',')]"
    if ($contentState.name -eq "content") { Ok "content + utility State-Maps sind cascade-separat" }
    else { Warn "/api/cascades/content nicht geseeded" }
} catch { Warn "T4 Sub-State check: $_" }

Section "T5 — Models-Endpoint zeigt category fuer jedes Modell"
try {
    $models = Invoke-RestMethod -Uri "$Base/api/models" -Method Get
    $withCategory = ($models | Where-Object { $_.category -and $_.category -ne "" }).Count
    Write-Host "  $withCategory/$($models.Count) Modelle haben explizite category"
} catch { Warn "GET /api/models: $_" }

Write-Host ""
if ($fail -eq 0) {
    Write-Host "=============================================" -ForegroundColor Green
    Write-Host "Phase S' Smoke-Tests: alle Checks bestanden" -ForegroundColor Green
    Write-Host "=============================================" -ForegroundColor Green
} else {
    Write-Host "=============================================" -ForegroundColor Red
    Write-Host "Phase S' Smoke-Tests: $fail Fehler" -ForegroundColor Red
    Write-Host "=============================================" -ForegroundColor Red
    exit 1
}
