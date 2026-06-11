# ============================================================================
#  Setzt einen annotated Milestone-Tag fuer einen fertigen Feature-Cluster.
#  Ersetzt das alte archive-branch.ps1 (historie/-Archiv abgeschafft 2026-06-11).
#
#  Aufruf:
#    pwsh scripts/tag-milestone.ps1 -Slug <slug> -Description "Kurzbeschreibung" [-Commit <commit-ish>]
#    z.B.: pwsh scripts/tag-milestone.ps1 -Slug statistik-dashboard -Description "Statistik-Dashboard + Quality-Endpoint"
#
#  - Tag-Name: milestone/<slug>
#  - Zeigt per Default auf origin/main HEAD, oder auf das optional angegebene
#    -Commit (SHA / Branch / Tag) — z.B. der main-Merge-Commit des letzten PR.
#  - Annotated Tag (mit Nachricht), wird nach origin gepusht.
#
#  Wer macht das: nach Merge des letzten PR im Cluster (i.d.R. Djavid).
#  Nicht jeder PR wird getaggt — nur abgeschlossene groessere Cluster.
# ============================================================================
param(
  [Parameter(Mandatory=$true)][string]$Slug,
  [Parameter(Mandatory=$true)][string]$Description,
  [string]$Commit = ""
)
$ErrorActionPreference = "Stop"

$Tag = "milestone/$Slug"

git fetch origin --tags --quiet

git rev-parse -q --verify "refs/tags/$Tag" 2>$null | Out-Null
if ($LASTEXITCODE -eq 0) {
  Write-Error "Tag '$Tag' existiert bereits."
  exit 1
}

if ([string]::IsNullOrEmpty($Commit)) { $Commit = "origin/main" }

$Target = (git rev-parse --verify "$Commit^{commit}").Trim()
Write-Host "==> Tagge '$Tag' auf $Target ($Commit)"

git tag -a $Tag $Target -m $Description
git push origin $Tag

Write-Host "OK: '$Tag' gesetzt + gepusht."
Write-Host "    Sichtbar in GitHub unter Tags/Releases."
Write-Host "    Anzeigen: git show $Tag"
