#!/usr/bin/env bash
# Usage: fetch-central.sh <site-dir> [<release-version>]
#
# Adds the driver jars and the Javadoc from Maven Central to a built site.
# CONTRIBUTING.md, "Deploying the website", says what it fetches and how it
# checks each file. The environment variables CENTRAL_URL, RELEASE_KEYS,
# RELEASE_ATTEMPTS and RETRY_DELAY replace the defaults below.
set -euo pipefail

if (($# < 1 || $# > 2)); then
  echo "usage: $0 <site-dir> [<release-version>]" >&2
  exit 2
fi
site=$1
release=${2:-}
default_central=https://repo1.maven.org/maven2/org/postgresql/postgresql
central=${CENTRAL_URL:-$default_central}
keys=${RELEASE_KEYS:-$(dirname "$0")/release-keys.asc}
attempts=${RELEASE_ATTEMPTS:-30}
delay=${RETRY_DELAY:-60}
# A version from 42.0.0 on, with an optional jre6 or jre7 suffix, and each
# number without a leading zero.
major='(4[2-9]|[5-9][0-9]|[1-9][0-9]{2,})'
number='(0|[1-9][0-9]*)'
pattern="^$major\\.$number\\.$number(\\.jre[67])?\$"

# The jars of these versions are signed by subkey 105CB91CAC2AEE0E of
# 1A60 90D3 223D CA95 BFD2 5C0E 38F4 7D3E 410C 47B1, whose key flags allow
# encryption only, so GnuPG does not check signatures made with it. They
# get the SHA-1 check, and their .asc must be on Central.
unchecked=(42.2.14 42.2.14.jre6 42.2.14.jre7 42.2.15 42.2.15.jre6
  42.2.15.jre7 42.2.16 42.2.16.jre6 42.2.16.jre7)

if [[ -n $release && ! $release =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "release version $release is not three numbers separated by dots" >&2
  exit 1
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
# gpgv reads a binary keyring, and gpg writes into GNUPGHOME even to
# dearmor, so both stay in the temporary directory.
export GNUPGHOME=$tmp/gnupg
mkdir -m 700 "$GNUPGHOME"
gpg --batch --quiet --dearmor --output "$tmp/keys.gpg" "$keys"

curl -fsS --retry 3 -o "$tmp/maven-metadata.xml" \
  "$central/maven-metadata.xml"
python3 - "$tmp/maven-metadata.xml" > "$tmp/all-versions.txt" <<'EOF'
import sys
import xml.etree.ElementTree as ET

for version in ET.parse(sys.argv[1]).getroot().iter("version"):
    print((version.text or "").strip())
EOF

list=$(dirname "$site")/central-versions.txt
if ! grep -E "$pattern" "$tmp/all-versions.txt" > "$list"; then
  echo "maven-metadata.xml lists no version from 42.0.0 on" >&2
  exit 1
fi
cp "$list" "$tmp/versions.txt"
if [[ -n $release ]] && ! grep -qxF "$release" "$list"; then
  echo "$release" >> "$tmp/versions.txt"
fi

# download <url>...: downloads each URL into $tmp, named after the last
# part of the URL, and prints the name of the first one that is missing.
download() {
  local url
  for url in "$@"; do
    if ! curl -fsS --retry 3 -o "$tmp/${url##*/}" "$url" 2> /dev/null; then
      echo "${url##*/}"
      return 1
    fi
  done
}

# fetch <version> <file> <destination>: downloads <file> of <version> with
# its .sha1 and .asc, checks both, and moves <file> to <destination>. The
# files of the release version are retried, because they can appear on
# Central minutes after the release.
fetch() {
  local version=$1 file=$2 dest=$3 retries=0 try=0 missing
  local url=$central/$version/$file
  if [[ $version == "$release" ]]; then
    retries=$attempts
  fi
  until missing=$(download "$url" "$url.sha1" "$url.asc"); do
    if ((try >= retries)); then
      echo "$missing is not on Maven Central at ${url%/*}/" >&2
      return 1
    fi
    try=$((try + 1))
    echo "$missing is not on Maven Central yet; retry $try of $retries" >&2
    sleep "$delay"
  done
  local computed published
  computed=$(sha1sum "$tmp/$file" | cut -c1-40)
  published=$(head -c 40 "$tmp/$file.sha1")
  if [[ $computed != "$published" ]]; then
    echo "$file: SHA-1 $computed does not match Maven Central's" \
      "$published" >&2
    return 1
  fi
  local skip
  for skip in "${unchecked[@]}"; do
    if [[ $file == "postgresql-$skip.jar" ]]; then
      echo "$file: signature not checked (signed with an encryption-only" \
        "subkey)"
      unsigned=$((unsigned + 1))
      mv "$tmp/$file" "$dest"
      return 0
    fi
  done
  if ! gpgv --keyring "$tmp/keys.gpg" "$tmp/$file.asc" "$tmp/$file" \
    > "$tmp/gpgv.log" 2>&1; then
    sed 's/^/  /' "$tmp/gpgv.log" >&2
    echo "$file: signature does not verify against the release keys" >&2
    return 1
  fi
  mv "$tmp/$file" "$dest"
}

mkdir -p "$site/download"
count=0
unsigned=0
while read -r version; do
  fetch "$version" "postgresql-$version.jar" \
    "$site/download/postgresql-$version.jar"
  count=$((count + 1))
done < "$tmp/versions.txt"

javadoc=$(grep -v jre "$tmp/versions.txt" | sort -V | tail -1)
fetch "$javadoc" "postgresql-$javadoc-javadoc.jar" "$tmp/javadoc.jar"
# zipfile writes every entry as a regular file, even one that unzip would
# restore as a symbolic link, and drops ".." and leading "/" from names.
python3 - "$tmp/javadoc.jar" "$site/documentation/publicapi" <<'EOF'
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as jar:
    names = [n for n in jar.namelist() if not n.startswith("META-INF/")]
    jar.extractall(sys.argv[2], names)
EOF

echo "Fetched $count jars, $((count - unsigned)) of them with a checked" \
  "signature, and the Javadoc of $javadoc into $site"
if [[ -n ${GITHUB_OUTPUT:-} ]]; then
  echo "javadoc-version=$javadoc" >> "$GITHUB_OUTPUT"
fi
