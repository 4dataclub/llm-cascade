#!/bin/bash
# ============================================================================
#  Archiviert einen gemergeten Feature-Branch unter historie/<name>.
#  Branch wird NICHT gelöscht — nur in den historie-Namespace verschoben.
#  Inhalt + Commits bleiben unter neuem Namen erhalten.
#
#  Aufruf:
#    bash scripts/archive-branch.sh feat/<branch-name>
#
#  Voraussetzungen:
#    - Branch ist auf origin (oder lokal vorhanden)
#    - Branch ist bereits in main gemerged (sicherheitscheck)
#
#  Wer macht das: nur Djavid nach Merge des PR. Anar nicht.
# ============================================================================
set -e

BRANCH="${1:-}"
if [ -z "$BRANCH" ]; then
  echo "usage: $0 <branch-name>"
  echo "  z.B.: $0 feat/dashboard-redesign"
  exit 1
fi

ARCHIVE="historie/$BRANCH"

git fetch origin --prune --quiet

if ! git show-ref --verify --quiet "refs/remotes/origin/$BRANCH"; then
  echo "FEHLER: Branch '$BRANCH' existiert nicht auf origin."
  exit 1
fi

# Sicherheits-Check: ist der Branch tatsächlich in main gemerged?
# HINWEIS: dieser Check ist eine Heuristik via `git log main..branch`.
#   - Bei Merge-Commit: Branch-HEAD ist Ancestor von main → Check sauber, leer = OK.
#   - Bei Squash-Merge / Rebase-Merge: Branch-Commits existieren als andere SHAs in main →
#     Check zeigt false-positive ("nicht gemerged"), obwohl Inhalt drin ist.
#   In dem Fall: ARCHIVE_FORCE=1 setzen, nachdem du auf GitHub bestätigt hast
#   dass der PR merged ist.
unmerged=$(git log "origin/main..origin/$BRANCH" --oneline 2>/dev/null | wc -l | tr -d ' ')
if [ "$unmerged" != "0" ]; then
  echo "WARNUNG: '$BRANCH' hat $unmerged Commits, die NICHT direkt in main als Ancestors sind."
  echo "  - Bei Squash- oder Rebase-Merge ist das normal (Inhalt ist als anderer SHA in main)."
  echo "  - Bei Merge-Commit (kein Squash) heisst das: Branch ist NICHT gemerged."
  echo "Wenn der PR auf GitHub als 'merged' markiert ist:"
  echo "  ARCHIVE_FORCE=1 $0 $BRANCH"
  if [ "$ARCHIVE_FORCE" != "1" ]; then
    exit 1
  fi
fi

echo "==> Archiviere '$BRANCH' als '$ARCHIVE'"

# 1) Auf remote: neuer ref-Name unter historie/, dann alten ref entfernen
git push origin "refs/remotes/origin/$BRANCH:refs/heads/$ARCHIVE"
git push origin --delete "$BRANCH"

# 2) Lokal: rename falls vorhanden
if git show-ref --verify --quiet "refs/heads/$BRANCH"; then
  git branch -m "$BRANCH" "$ARCHIVE"
fi

echo "OK: '$BRANCH' archiviert als '$ARCHIVE'"
echo "    Inhalt + Commits bleiben unter neuem Namen erreichbar:"
echo "      git log $ARCHIVE"
