#!/bin/bash
# ============================================================================
#  Setzt einen annotated Milestone-Tag fuer einen fertigen Feature-Cluster.
#  Ersetzt das alte archive-branch.sh (historie/-Archiv abgeschafft 2026-06-11).
#
#  Aufruf:
#    bash scripts/tag-milestone.sh <slug> "Kurzbeschreibung" [commit-ish]
#    z.B.: bash scripts/tag-milestone.sh statistik-dashboard "Statistik-Dashboard + Quality-Endpoint"
#
#  - Tag-Name: milestone/<slug>
#  - Zeigt per Default auf origin/main HEAD, oder auf das optional angegebene
#    commit-ish (SHA / Branch / Tag) — z.B. der main-Merge-Commit des letzten
#    PR im Cluster.
#  - Annotated Tag (mit Nachricht), wird nach origin gepusht.
#
#  Wer macht das: nach Merge des letzten PR im Cluster (i.d.R. Djavid).
#  Nicht jeder PR wird getaggt — nur abgeschlossene groessere Cluster.
# ============================================================================
set -e

SLUG="${1:-}"
DESC="${2:-}"
COMMIT="${3:-}"

if [ -z "$SLUG" ] || [ -z "$DESC" ]; then
  echo "usage: $0 <slug> \"Kurzbeschreibung\" [commit-ish]"
  echo "  z.B.: $0 statistik-dashboard \"Statistik-Dashboard + Quality-Endpoint\""
  exit 1
fi

TAG="milestone/$SLUG"

git fetch origin --tags --quiet

if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  echo "FEHLER: Tag '$TAG' existiert bereits."
  exit 1
fi

if [ -z "$COMMIT" ]; then
  COMMIT="origin/main"
fi

TARGET=$(git rev-parse --verify "$COMMIT^{commit}")
echo "==> Tagge '$TAG' auf $TARGET ($COMMIT)"

git tag -a "$TAG" "$TARGET" -m "$DESC"
git push origin "$TAG"

echo "OK: '$TAG' gesetzt + gepusht."
echo "    Sichtbar in GitHub unter Tags/Releases."
echo "    Anzeigen: git show $TAG"
