#!/usr/bin/env bash
# Usage: check-site.sh <site-dir> <central-versions-file> <javadoc-version>
#                      pr|deploy
#
# Checks a site after fetch-central.sh. CONTRIBUTING.md, "Deploying the
# website", lists the checks and the pull request exception.
set -euo pipefail

if (($# != 4)) || [[ $4 != pr && $4 != deploy ]]; then
  echo "usage: $0 <site-dir> <central-versions-file> <javadoc-version>" \
    "pr|deploy" >&2
  exit 2
fi
site=$1 list=$2 javadoc=$3 mode=$4
failed=0

error() {
  echo "::error::$1" >&2
  failed=1
}

# not_listed <version>: in pr mode, a version that Central does not list
# yet gives a warning, and the caller skips its error.
not_listed() {
  if [[ $mode == pr ]] && ! grep -qxF "$1" "$list"; then
    echo "::warning::/download/postgresql-$1.jar is linked but Maven" \
      "Central does not list $1 yet. A deploy fails until it does."
    return 0
  fi
  return 1
}

# S1 and S3. Each line of $links is "<version> <page>", one a version.
links=$(cd "$site" && grep -rHoE --include='*.html' \
  'href="?/download/postgresql-[^" >]+\.jar' . |
  sed -E 's|^\./([^:]*):href="?/download/postgresql-(.*)\.jar$|\2 \1|' |
  sort -s -u -k1,1) || true
if [[ -z $links ]]; then
  error "no link on the site matches /download/postgresql-<version>.jar;\
 check docs/layouts/partials/jar-url.html"
fi
while read -r version page; do
  [[ -n $version ]] || continue
  if [[ ! -f $site/download/postgresql-$version.jar ]] &&
    ! not_listed "$version"; then
    error "/download/postgresql-$version.jar is linked from $page but is\
 not in the site"
  fi
done <<< "$links"

# S2 and S4.
current=$(grep -oE 'Current release is [0-9]+\.[0-9]+\.[0-9]+\.' \
  "$site/index.html" | sed -E 's/^Current release is (.*)\.$/\1/') || true
if [[ $(grep -c . <<< "$current") != 1 ]]; then
  error 'index.html does not contain the sentence "Current release is\
 <version>." exactly once'
elif [[ $current != "$javadoc" ]] && ! not_listed "$current"; then
  error "the home page names $current as the current release, but the\
 Javadoc is $javadoc"
fi

# S5. actions/upload-pages-artifact archives with tar --dereference, so a
# symbolic link would publish the file it points at.
while read -r link; do
  [[ -n $link ]] && error "${link#"$site"/} is a symbolic link, which the\
 site must not contain"
done <<< "$(find "$site" -type l)"

((failed == 0)) || exit 1
echo "Checked $(grep -c . <<< "$links") jar links and the current release" \
  "$current"
