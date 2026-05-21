# ============================================================================
#  Archiviert einen gemergeten Feature-Branch unter historie/<name>.
#  Branch wird NICHT geloescht - nur in den historie-Namespace verschoben.
#
#  Aufruf:
#    .\scripts\archive-branch.ps1 feat/<branch-name>
#
#  Wer macht das: nur Djavid nach Merge des PR. Anar nicht.
# ============================================================================
$ErrorActionPreference = "Stop"

if ($args.Count -lt 1) {
    Write-Host "usage: .\scripts\archive-branch.ps1 <branch-name>"
    Write-Host "  z.B.: .\scripts\archive-branch.ps1 feat/dashboard-redesign"
    exit 1
}

$Branch  = $args[0]
$Archive = "historie/$Branch"

& git fetch origin --prune --quiet

$exists = & git show-ref --verify --quiet "refs/remotes/origin/$Branch"; $exists = ($LASTEXITCODE -eq 0)
if (-not $exists) {
    Write-Host "FEHLER: Branch '$Branch' existiert nicht auf origin." -ForegroundColor Red
    exit 1
}

# Sicherheits-Check (siehe Hinweise in archive-branch.sh: bei Squash/Rebase-Merge
# ist `git log main..branch` nicht leer obwohl Inhalt in main ist - ARCHIVE_FORCE setzen).
$unmergedRaw = & git log "origin/main..origin/$Branch" --oneline 2>$null
$unmerged    = if ($unmergedRaw) { ($unmergedRaw | Measure-Object -Line).Lines } else { 0 }

if ($unmerged -ne 0 -and $env:ARCHIVE_FORCE -ne "1") {
    Write-Host "WARNUNG: '$Branch' hat $unmerged Commits, die NICHT direkt in main als Ancestors sind." -ForegroundColor Yellow
    Write-Host "  - Bei Squash/Rebase-Merge ist das normal (Inhalt als anderer SHA in main)."
    Write-Host "  - Bei Merge-Commit heisst das: Branch ist NICHT gemerged."
    Write-Host "Wenn der PR auf GitHub als 'merged' markiert ist:"
    Write-Host "  `$env:ARCHIVE_FORCE='1'; .\scripts\archive-branch.ps1 $Branch"
    exit 1
}

Write-Host "==> Archiviere '$Branch' als '$Archive'" -ForegroundColor Cyan

# 1) Remote: neuer ref-Name unter historie/, dann alten ref entfernen
& git push origin "refs/remotes/origin/${Branch}:refs/heads/$Archive"
& git push origin --delete $Branch

# 2) Lokal: rename falls vorhanden
$localExists = & git show-ref --verify --quiet "refs/heads/$Branch"; $localExists = ($LASTEXITCODE -eq 0)
if ($localExists) {
    & git branch -m $Branch $Archive
}

Write-Host "OK: '$Branch' archiviert als '$Archive'" -ForegroundColor Green
Write-Host "    Inhalt + Commits bleiben unter neuem Namen erreichbar:"
Write-Host "      git log $Archive"
