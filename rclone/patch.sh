#!/usr/bin/env bash
# Prépare third_party/rclone : copie du module rclone officiel (version de go.mod, tirée du cache Go
# sans le modifier) + correctifs de patches/*.patch. go.mod pointe dessus (directive replace).
# Sans effet si la copie est déjà à jour (version et correctifs inchangés).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
cd "$here"
version="$(awk '$1 == "github.com/rclone/rclone" && $2 ~ /^v/ { print $2; exit }' go.mod)"
[ -n "$version" ] || { echo "version de rclone introuvable dans go.mod" >&2; exit 1; }
dest="$here/third_party/rclone"
stamp="$version $(cat patches/*.patch | sha256sum | cut -c1-16)"
if [ -f "$dest/.xfiles-patch" ] && [ "$(cat "$dest/.xfiles-patch")" = "$stamp" ]; then
  exit 0
fi
# Téléchargement hors du module (la directive replace ne s'applique pas ici) ; vérifié par go.sum public.
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
src="$(cd "$tmp" && GOFLAGS=-mod=mod go mod download -json "github.com/rclone/rclone@$version" | sed -n 's/^\s*"Dir": "\(.*\)",$/\1/p')"
[ -d "$src" ] || { echo "module rclone $version introuvable" >&2; exit 1; }
rm -rf "$dest.new"
mkdir -p "$(dirname "$dest")"
cp -R "$src" "$dest.new"
chmod -R u+w "$dest.new"
for p in patches/*.patch; do
  patch -s -p1 -d "$dest.new" < "$p"
done
echo "$stamp" > "$dest.new/.xfiles-patch"
rm -rf "$dest"
mv "$dest.new" "$dest"
echo "rclone $version préparé avec $(ls patches/*.patch | wc -l) correctif(s)."
