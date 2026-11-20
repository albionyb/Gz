#!/usr/bin/env bash
# Downloads several public ad/tracker domain lists, merges them into app assets.
# Optional: any list that fails is skipped; if all fail, the bundled blocklist_base.txt is still used.
cd "$(dirname "$0")/.."
mkdir -p app/src/main/assets
OUT=app/src/main/assets/blocklist_extra.txt
TMP=$(mktemp -d)

get() { # name url
  if curl -fsSL --max-time 90 -o "$TMP/$1" "$2"; then
    echo "$1: ok ($(wc -l < "$TMP/$1") lines)"
  else
    echo "$1: failed, skipped"; rm -f "$TMP/$1"
  fi
}

# 1. Peter Lowe's ad servers (plain domains)
get lowe "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=nohtml&showintro=0&mimetype=plaintext"
# 2. StevenBlack unified hosts ("0.0.0.0 domain")
get steven "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
# 3. AdGuard DNS filter ("||domain^")
get adguard "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"
# Optional, bigger and more aggressive (may break some sites). Uncomment to use:
# get hagezi "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/domains/pro.txt"

{
  [ -f "$TMP/lowe" ]    && cat "$TMP/lowe"
  [ -f "$TMP/steven" ]  && awk '$1=="0.0.0.0"{print $2}' "$TMP/steven"
  [ -f "$TMP/adguard" ] && sed -nE 's/^\|\|([A-Za-z0-9._-]+)\^$/\1/p' "$TMP/adguard"
  [ -f "$TMP/hagezi" ]  && grep -v '^#' "$TMP/hagezi"
} | tr -d '\r' | tr 'A-Z' 'a-z' \
  | grep -E '^[a-z0-9._-]+\.[a-z0-9-]+$' | grep -vE '^[0-9.]+$' \
  | sort -u > "$OUT"

n=$(wc -l < "$OUT")
if [ "$n" -lt 100 ]; then
  echo "Blocklist too small ($n); using bundled list only"
  rm -f "$OUT"
else
  echo "Blocklist merged: $n domains"
fi
rm -rf "$TMP"
exit 0
