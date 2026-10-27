#!/usr/bin/env bash
# Usage: test-site-scripts.sh
#
# Tests fetch-central.sh against a Maven Central mirror in a temporary
# directory, signed with a key made for the test, and check-site.sh
# against fixture sites. Every case prints PASS; the first case that fails
# stops the script with a non-zero exit. No case needs the network.
set -euo pipefail

scripts=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
export GNUPGHOME=$work/gnupg
cleanup() {
  gpgconf --kill gpg-agent 2> /dev/null || true
  rm -rf "$work"
}
trap cleanup EXIT
export RETRY_DELAY=0
# ls and sort -V order the file names the same way on every machine.
export LC_ALL=C

fail() {
  echo "FAIL: $1" >&2
  if [[ -f $work/log ]]; then
    sed 's/^/  log: /' "$work/log" >&2
  fi
  exit 1
}

# The mirror is signed with a key made here. fetch-central.sh reads its
# public key through RELEASE_KEYS, in place of the release keys.
mkdir -m 700 "$GNUPGHOME"
# Without scdaemon, gpg signs with the key made here and never looks for
# a smartcard, which fails the signature on a machine with a card reader.
echo disable-scdaemon > "$GNUPGHOME/gpg-agent.conf"
gpg --batch --quiet --passphrase '' --quick-gen-key \
  'pgjdbc test <test@invalid>' ed25519 sign never
gpg --batch --quiet --armor --export > "$work/keys.asc"
export RELEASE_KEYS=$work/keys.asc

# sign <file>: writes <file>.sha1 and <file>.asc, as Central has them.
sign() {
  sha1sum "$1" | cut -c1-40 > "$1.sha1"
  gpg --batch --quiet --yes --armor --detach-sign --output "$1.asc" "$1"
}

# mirror <version>...: writes a mirror whose maven-metadata.xml lists the
# versions, and sets CENTRAL_URL to it.
mirror() {
  rm -rf "$work/central"
  mkdir -p "$work/central"
  {
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    echo '<metadata><versioning><versions>'
    for version in "$@"; do
      echo "<version>$version</version>"
    done
    echo '</versions></versioning></metadata>'
  } > "$work/central/maven-metadata.xml"
  export CENTRAL_URL=file://$work/central
}

# add_jar <version>: adds postgresql-<version>.jar.
add_jar() {
  mkdir -p "$work/central/$1"
  echo "jar $1" > "$work/central/$1/postgresql-$1.jar"
  sign "$work/central/$1/postgresql-$1.jar"
}

# add_javadoc <version>: adds a javadoc jar holding index.html, and two
# entries that must not leave the Javadoc directory: a symbolic link to
# /etc/hosts and a file named ../escape.html.
add_javadoc() {
  local jar=$work/central/$1/postgresql-$1-javadoc.jar
  mkdir -p "$work/central/$1"
  python3 - "$jar" "$1" <<'EOF'
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1], "w") as jar:
    jar.writestr("index.html", f"Javadoc {sys.argv[2]}\n")
    jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
    link = zipfile.ZipInfo("leak")
    link.create_system = 3
    link.external_attr = 0o120777 << 16
    jar.writestr(link, "/etc/hosts")
    jar.writestr("../escape.html", "escaped\n")
EOF
  sign "$jar"
}

# standard_mirror: 42.0.0 and 42.0.0.jre7 with their files, plus versions
# that the filter skips.
standard_mirror() {
  mirror 42.0.0 42.0.0.jre7 9.4.1212 42.0.0-rc1
  add_jar 42.0.0
  add_jar 42.0.0.jre7
  add_javadoc 42.0.0
}

# fetch [<release-version>]: runs fetch-central.sh into a new site
# directory, with its output in $work/log.
fetch() {
  rm -rf "$work/out"
  mkdir -p "$work/out/site"
  "$scripts/fetch-central.sh" "$work/out/site" "$@" > "$work/log" 2>&1
}

# downloads: the file names under the site's download/, on one line.
downloads() {
  (cd "$work/out/site/download" && ls) | tr '\n' ' '
}

javadoc_is() {
  grep -qx "Javadoc $1" "$work/out/site/documentation/publicapi/index.html"
}

standard_mirror
fetch || fail "1: fetch from a correct mirror"
[[ $(downloads) == "postgresql-42.0.0.jar postgresql-42.0.0.jre7.jar " ]] ||
  fail "1: download/ holds $(downloads)"
javadoc_is 42.0.0 || fail "1: the Javadoc of 42.0.0 is not unpacked"
[[ ! -e $work/out/site/documentation/publicapi/META-INF ]] ||
  fail "1: META-INF is unpacked"
[[ -z $(find "$work/out" -type l) ]] ||
  fail "1: the Javadoc unpacks a symbolic link"
[[ ! -e $work/out/site/documentation/escape.html ]] ||
  fail "1: the Javadoc unpacks a file outside its directory"
[[ $(cat "$work/out/central-versions.txt") == $'42.0.0\n42.0.0.jre7' ]] ||
  fail "1: central-versions.txt lists $(cat "$work/out/central-versions.txt")"
echo "PASS 1: a correct, signed mirror gives the jars from 42.0.0 on and" \
  "the Javadoc"

standard_mirror
echo 0000000000000000000000000000000000000000 \
  > "$work/central/42.0.0.jre7/postgresql-42.0.0.jre7.jar.sha1"
if fetch; then fail "2: a changed .sha1 is accepted"; fi
grep -q "postgresql-42.0.0.jre7.jar: SHA-1 .* does not match Maven Central's" \
  "$work/log" || fail "2: the message does not name the SHA-1 mismatch"
echo "PASS 2: a changed .sha1 fails the fetch"

standard_mirror
if RELEASE_ATTEMPTS=1 fetch 42.0.1; then
  fail "3: a release version absent from the mirror is accepted"
fi
grep -q 'postgresql-42.0.1.jar is not on Maven Central yet; retry 1 of 1' \
  "$work/log" || fail "3: the release version is not retried"
echo "PASS 3: a release version absent from the mirror is retried, then" \
  "fails the fetch"

standard_mirror
add_jar 42.0.1
add_javadoc 42.0.1
fetch 42.0.1 || fail "4: a release version missing from the metadata"
[[ -f $work/out/site/download/postgresql-42.0.1.jar ]] ||
  fail "4: the jar of the release version is not fetched"
javadoc_is 42.0.1 || fail "4: the Javadoc is not the release version's"
! grep -qx 42.0.1 "$work/out/central-versions.txt" ||
  fail "4: central-versions.txt lists the release version"
echo "PASS 4: the release version is fetched before the metadata lists it"

standard_mirror
add_jar 42.0.1
if RELEASE_ATTEMPTS=0 fetch 42.0.1; then
  fail "5: a release version without its javadoc jar is accepted"
fi
echo "PASS 5: a release version without its javadoc jar fails the fetch"

standard_mirror
cp "$work/central/42.0.0/postgresql-42.0.0.jar.asc" \
  "$work/central/42.0.0.jre7/postgresql-42.0.0.jre7.jar.asc"
if fetch; then fail "6: a signature of another file is accepted"; fi
grep -q 'postgresql-42.0.0.jre7.jar: signature does not verify against' \
  "$work/log" || fail "6: the message does not name the bad signature"
echo "PASS 6: a signature that does not match its jar fails the fetch"

standard_mirror
rm "$work/central/42.0.0.jre7/postgresql-42.0.0.jre7.jar.asc"
if fetch; then fail "7: a jar without its .asc is accepted"; fi
grep -q 'postgresql-42.0.0.jre7.jar.asc is not on Maven Central' \
  "$work/log" || fail "7: the message does not name the missing .asc"
echo "PASS 7: a jar without its .asc fails the fetch"

mirror 42.0.0 42.0.0.jre7 42.2.14
add_jar 42.0.0
add_jar 42.0.0.jre7
add_jar 42.2.14
add_javadoc 42.2.14
cp "$work/central/42.0.0/postgresql-42.0.0.jar.asc" \
  "$work/central/42.2.14/postgresql-42.2.14.jar.asc"
fetch || fail "8: a jar on the unchecked list fails its signature check"
unchecked='postgresql-42.2.14.jar: signature not checked'
unchecked+=' (signed with an encryption-only subkey)'
grep -qxF "$unchecked" "$work/log" ||
  fail "8: the unchecked jar is not reported"
grep -q '^Fetched 3 jars, 2 of them with a checked signature,' "$work/log" ||
  fail "8: the summary does not count the unchecked jar"
cp "$work/central/42.0.0/postgresql-42.0.0.jar.asc" \
  "$work/central/42.0.0.jre7/postgresql-42.0.0.jre7.jar.asc"
if fetch; then fail "8: a bad signature off the unchecked list is accepted"; fi
grep -q 'postgresql-42.0.0.jre7.jar: signature does not verify' "$work/log" ||
  fail "8: the message does not name the bad signature"
echo "PASS 8: a jar on the unchecked list skips only the signature check"

mirror 41.9.9 42.0.0 43.0.0 100.0.0 4.20.0 042.0.0
add_jar 42.0.0
add_jar 43.0.0
add_jar 100.0.0
add_javadoc 100.0.0
fetch || fail "9: fetch from a mirror with later major versions"
[[ $(downloads) == \
  "postgresql-100.0.0.jar postgresql-42.0.0.jar postgresql-43.0.0.jar " ]] ||
  fail "9: download/ holds $(downloads)"
javadoc_is 100.0.0 || fail "9: the Javadoc is not the highest version's"
fetch 42.0.0 || fail "9: fetch with the backport release version 42.0.0"
javadoc_is 100.0.0 || fail "9: the Javadoc follows the release version"
echo "PASS 9: every major version from 42 on is fetched, none before, and" \
  "the Javadoc is the highest version's"

# site [<link>...]: writes a fixture site whose home page names 42.0.0 as
# the current release, whose download page links each <link>, and which
# holds the jar of 42.0.0. Central's list, $work/versions.txt, has 42.0.0.
site() {
  rm -rf "$work/site"
  mkdir -p "$work/site/download"
  echo '<p>Current release is 42.0.0. A summary.</p>' \
    > "$work/site/index.html"
  for link in "$@"; do
    echo "<a href=$link>jar</a>"
  done > "$work/site/download/index.html"
  echo "jar 42.0.0" > "$work/site/download/postgresql-42.0.0.jar"
  echo 42.0.0 > "$work/versions.txt"
}

# check <mode> [<javadoc-version>]: runs check-site.sh on the fixture,
# with its output in $work/log.
check() {
  "$scripts/check-site.sh" "$work/site" "$work/versions.txt" \
    "${2:-42.0.0}" "$1" > "$work/log" 2>&1
}

site /download/postgresql-42.0.0.jar
check deploy || fail "10: the fixture fails in deploy mode"
echo "PASS 10: the fixture passes in deploy mode"

site /download/postgresql-42.0.0.jar /download/postgresql-41.0.0.jar
check pr || fail "11: a jar that Central does not list fails in pr mode"
grep -q '::warning::/download/postgresql-41.0.0.jar is linked' "$work/log" ||
  fail "11: the warning is missing"
echo "PASS 11: a jar that Central does not list is a warning in pr mode"

if check deploy; then
  fail "12: a jar that is not in the site passes in deploy mode"
fi
grep -q 'postgresql-41.0.0.jar is linked from download/index.html but' \
  "$work/log" || fail "12: the message does not name the missing jar"
echo "PASS 12: a jar that is not in the site fails in deploy mode"

site /download/postgresql-42.0.0.jar /download/postgresql-42.0.1.jar
echo 42.0.1 >> "$work/versions.txt"
if check pr; then fail "13: a listed jar missing from the site passes"; fi
grep -q 'postgresql-42.0.1.jar is linked from download/index.html but' \
  "$work/log" || fail "13: the message does not name the missing jar"
echo "PASS 13: a jar that Central lists but the site lacks fails in pr mode"

site
if check pr; then fail "14: a site without jar links passes"; fi
grep -q 'no link on the site matches' "$work/log" ||
  fail "14: the message does not say that no link matches"
echo "PASS 14: a site without jar links fails"

site /download/postgresql-42.0.0.jar
echo '<p>A home page without the release sentence.</p>' \
  > "$work/site/index.html"
if check pr; then fail "15: a home page without the release passes"; fi
grep -q 'index.html does not contain the sentence' "$work/log" ||
  fail "15: the message does not name the missing sentence"
echo "PASS 15: a home page without \"Current release is\" fails"

site /download/postgresql-42.0.0.jar
if check pr 41.0.0; then
  fail "16: a Javadoc of another listed release passes in pr mode"
fi
grep -q 'names 42.0.0 as the current release, but the Javadoc is 41.0.0' \
  "$work/log" || fail "16: the message does not name both versions"
echo "PASS 16: a Javadoc of another release fails in pr mode when Central" \
  "lists the current release"

site /download/postgresql-42.0.0.jar
ln -s /etc/hosts "$work/site/download/leak"
if check pr; then fail "17: a symbolic link in the site passes"; fi
grep -q 'download/leak is a symbolic link' "$work/log" ||
  fail "17: the message does not name the symbolic link"
echo "PASS 17: a symbolic link in the site fails"

# row <path> <file>: prints the manifest row of <path> under the name
# <file>.
row() {
  printf '%s\t%s\t%s\tlegacy\n' "$2" "$(wc -c < "$1" | tr -d ' ')" \
    "$(sha256sum "$1" | cut -c1-64)"
}

# legacy [<file>]: writes a directory of two old jars that LEGACY_URL
# points at, and a manifest of them in LEGACY_MANIFEST. With <file>, the
# manifest names <file> in place of the second jar. The site already has
# postgresql-42.0.0.jar, as fetch-central.sh writes it.
legacy() {
  rm -rf "$work/legacy" "$work/out"
  mkdir -p "$work/legacy" "$work/out/site/download"
  echo "old jar 1" > "$work/legacy/pg73jdbc1.jar"
  echo "old jar 2" > "$work/legacy/postgresql-8.0-310.jdbc2.jar"
  echo "jar 42.0.0" > "$work/out/site/download/postgresql-42.0.0.jar"
  export LEGACY_URL=file://$work/legacy
  export LEGACY_MANIFEST=$work/legacy.tsv
  {
    printf 'file\tbytes\tsha256\tsource\n'
    row "$work/legacy/pg73jdbc1.jar" pg73jdbc1.jar
    row "$work/legacy/postgresql-8.0-310.jdbc2.jar" \
      "${1:-postgresql-8.0-310.jdbc2.jar}"
  } > "$LEGACY_MANIFEST"
}

# fetch_legacy: runs fetch-legacy.sh on the site, with its output in
# $work/log.
fetch_legacy() {
  "$scripts/fetch-legacy.sh" "$work/out/site" > "$work/log" 2>&1
}

legacy
fetch_legacy || fail "18: fetch from a correct manifest"
for jar in pg73jdbc1.jar postgresql-8.0-310.jdbc2.jar; do
  cmp -s "$work/legacy/$jar" "$work/out/site/download/$jar" ||
    fail "18: $jar is not in download/ with its bytes"
done
echo "PASS 18: a correct manifest puts the old jars into download/"

legacy
zeros=$(printf '%064d' 0)
sed -i.bak "2s/[0-9a-f]\{64\}/$zeros/" "$LEGACY_MANIFEST"
if fetch_legacy; then fail "19: a wrong sha256 is accepted"; fi
grep -q "does not match the manifest's" "$work/log" ||
  fail "19: the message does not name the SHA-256 mismatch"
echo "PASS 19: a wrong sha256 fails the fetch"

legacy
sed -i.bak $'2s/\t[0-9]*\t/\t1\t/' "$LEGACY_MANIFEST"
if fetch_legacy; then fail "20: a wrong bytes is accepted"; fi
grep -q 'bytes, but the manifest pins' "$work/log" ||
  fail "20: the message does not name the size mismatch"
echo "PASS 20: a wrong bytes fails the fetch"

legacy postgresql-8.0-311.jdbc2.jar
if fetch_legacy; then fail "21: a file missing from the release passes"; fi
grep -q 'is not in the release legacy-downloads' "$work/log" ||
  fail "21: the message does not name the missing file"
echo "PASS 21: a file missing from the release fails the fetch"

for name in ../x.jar a/b.jar .x.jar x.txt x..y.jar; do
  legacy "$name"
  if fetch_legacy; then fail "22: the name $name is accepted"; fi
  grep -q 'is not a file name this site serves' "$work/log" ||
    fail "22: the message does not refuse the name $name"
done
echo "PASS 22: a name that is not a jar in download/ fails the fetch"

legacy postgresql-42.0.0.jar
if fetch_legacy; then fail "23: a manifest row replaces a Central jar"; fi
grep -q 'already wrote it' "$work/log" ||
  fail "23: the message does not name the Central jar"
echo "PASS 23: a manifest row cannot replace a jar from Central"

legacy
sed -i.bak '1s/^file/name/' "$LEGACY_MANIFEST"
if fetch_legacy; then fail "24: a manifest with another header passes"; fi
grep -q 'the header is not file, bytes, sha256, source' "$work/log" ||
  fail "24: the message does not name the header"
echo "PASS 24: a manifest with another header fails the fetch"

# Each bad row is line 3 of its manifest, after a correct line 2. The last
# one quotes the name: csv's default quoting would read it as a correct
# row, but with quoting turned off the quotes stay part of the name.
legacy
jar=postgresql-8.0-310.jdbc2.jar
bytes=$(wc -c < "$work/legacy/$jar" | tr -d ' ')
sha=$(sha256sum "$work/legacy/$jar" | cut -c1-64)
upper=$(tr 'a-f' 'A-F' <<< "$sha")
tab=$'\t'
bad_rows=(
  "pg73jdbc1.jar$tab$bytes$tab$sha${tab}legacy"
  "$jar$tab$bytes${tab}legacy"
  "$jar${tab}1x$tab$sha${tab}legacy"
  "$jar$tab$bytes$tab$upper${tab}legacy"
  "$jar$tab$bytes$tab$sha${tab}foo"
  "\"$jar\"$tab$bytes$tab$sha${tab}legacy"
)
for bad in "${bad_rows[@]}"; do
  legacy
  { head -2 "$LEGACY_MANIFEST"; printf '%s\n' "$bad"; } > "$work/bad.tsv"
  mv "$work/bad.tsv" "$LEGACY_MANIFEST"
  if fetch_legacy; then fail "25: the bad row $bad is accepted"; fi
  grep -q 'legacy.tsv line 3: ' "$work/log" ||
    fail "25: the message for the bad row $bad does not name line 3"
done
echo "PASS 25: a repeated name, a wrong field count, bytes, sha256 or" \
  "source, and a quoted field each fail the fetch"
