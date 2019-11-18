#!/bin/bash


CURRENT_VERSION=`mvn -B -N org.apache.maven.plugins:maven-help-plugin:2.1.1:evaluate -Dexpression=project.version | grep -v '\['`
VERS=${CURRENT_VERSION/-SNAPSHOT}
DATE_YMD=$(date '+%Y-%m-%d')
RELEASE_FILE=$(find docs/_posts -name "*-$VERS-release.md" | head -n 1)
if [[ "x$RELEASE_FILE" == "x" ]]; then
  RELEASE_FILE=docs/_posts/$DATE_YMD-$VERS-release.md
fi
echo file: $RELEASE_FILE

if [ -f $RELEASE_FILE ]; then
  if [[ "x$1" == "x-o" ]]; then
    echo Removing file $RELEASE_FILE
    rm "$RELEASE_FILE"
  fi
fi

if [ -f $RELEASE_FILE ]; then
  echo File $RELEASE_FILE already exists. If you want to overwrite it, pass -o parameter
else
  # Makes all the output get printed to the file as well (concept from http://stackoverflow.com/a/3403786/267224)
  exec > >(tee -a $RELEASE_FILE)
  exec 2>&1
fi

PREV_VERSION=`git describe --match 'REL*' --abbrev=0`

echo ---
echo title:  "PostgreSQL JDBC Driver ${VERS} Released"
echo date:   $(date '+%Y-%m-%d %H:%M:%S %z')
echo categories:
echo '  - new_release'
echo version: ${VERS}
echo ---


echo **Notable changes**
echo
awk "/^## \[Unreleased\]/,/^## \[${PREV_VERSION:3}\]/ {print}" CHANGELOG.md | sed -e '1d' -e '$d'
echo
echo '<!--more-->'
echo
echo **Commits by author**
echo

git shortlog --format="%s@@@%H@@@%h@@@" --grep="maven-release-plugin|update versions in readme.md" --extended-regexp --invert-grep --no-merges $PREV_VERSION..HEAD | perl release_notes_filter.pl ${VERS}

# Update CHANGELOG.md with the new version
NL=$'\n'
if grep -q "$VERS" CHANGELOG.md; then
  : # nothing to do here, CHANGELOG has already been updated
else
  sed -i -e "s/^## \[Unreleased\]$/## [Unreleased]\\$NL### Changed\\$NL\\$NL### Added\\$NL\\$NL### Fixed\\$NL\\$NL## [${VERS}] (${DATE_YMD})/" CHANGELOG.md
  sed -i -e "s/^\[Unreleased\]: /[${VERS}]: /" CHANGELOG.md
  sed -i -e "s/$PREV_VERSION\.\.\.HEAD/$PREV_VERSION...REL${VERS}/" CHANGELOG.md
  echo "[Unreleased]: https://github.com/pgjdbc/pgjdbc/compare/REL${VERS}...HEAD" >> CHANGELOG.md
fi
