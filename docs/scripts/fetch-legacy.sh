#!/usr/bin/env bash
# Usage: fetch-legacy.sh <site-dir>
#
# Adds the old jars that legacy-downloads.tsv lists to a built site, after
# fetch-central.sh. CONTRIBUTING.md, "Deploying the website", says where
# they come from. The environment variables LEGACY_URL and LEGACY_MANIFEST
# replace the defaults below.
set -euo pipefail

if (($# != 1)); then
  echo "usage: $0 <site-dir>" >&2
  exit 2
fi
site=$1
default_url=https://github.com/pgjdbc/pgjdbc/releases/download/legacy-downloads
url=${LEGACY_URL:-$default_url}
manifest=${LEGACY_MANIFEST:-$(dirname "$0")/legacy-downloads.tsv}

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# Checks the whole manifest before any download, and writes the rows as
# "<file> <bytes> <sha256>" lines. A name may hold only letters, digits, "."
# and "-", so it cannot leave download/.
python3 - "$manifest" "$site/download" > "$tmp/rows.txt" <<'EOF'
import csv
import os
import re
import sys

manifest, download = sys.argv[1:]
name_re = re.compile(r"[A-Za-z0-9][A-Za-z0-9.-]*\.jar")
sha_re = re.compile(r"[0-9a-f]{64}")
seen = set()


def fail(line, reason):
    sys.exit(f"{manifest} line {line}: {reason}")


with open(manifest, newline="", encoding="utf-8") as f:
    rows = csv.reader(f, delimiter="\t", quoting=csv.QUOTE_NONE)
    for line, row in enumerate(rows, 1):
        if line == 1:
            if row != ["file", "bytes", "sha256", "source"]:
                fail(line, "the header is not file, bytes, sha256, source")
            continue
        if len(row) != 4:
            fail(line, f"{len(row)} fields, not 4")
        name, size, sha, source = row
        if not name_re.fullmatch(name) or ".." in name:
            fail(line, f"{name} is not a file name this site serves under"
                       " /download/")
        if name in seen:
            fail(line, f"{name} is listed twice")
        if not size.isascii() or not size.isdigit():
            fail(line, f"bytes {size} is not a number")
        if not sha_re.fullmatch(sha):
            fail(line, f"sha256 {sha} is not 64 lowercase hexadecimal digits")
        if source != "legacy" and not re.fullmatch(r"central:\S+", source):
            fail(line, f"source {source} is neither legacy nor central:<path>")
        if os.path.lexists(os.path.join(download, name)):
            sys.exit(f"{name} is in the manifest, but the Central fetch"
                     " already wrote it")
        seen.add(name)
        print(name, size, sha)
EOF

mkdir -p "$site/download"
count=0
while read -r file bytes sha; do
  if ! curl -fsSL --retry 3 -o "$tmp/$file" "$url/$file" 2> /dev/null; then
    echo "$file is not in the release legacy-downloads at $url/" >&2
    exit 1
  fi
  size=$(wc -c < "$tmp/$file" | tr -d ' ')
  if [[ $size != "$bytes" ]]; then
    echo "$file: $size bytes, but the manifest pins $bytes" >&2
    exit 1
  fi
  computed=$(sha256sum "$tmp/$file" | cut -c1-64)
  if [[ $computed != "$sha" ]]; then
    echo "$file: SHA-256 $computed does not match the manifest's $sha" >&2
    exit 1
  fi
  mv "$tmp/$file" "$site/download/$file"
  count=$((count + 1))
done < "$tmp/rows.txt"

echo "Fetched $count old jars from the release legacy-downloads into" \
  "$site/download"
