#!/bin/sh


CURRENT_VERSION=`mvn -B -N org.apache.maven.plugins:maven-help-plugin:2.1.1:evaluate -Dexpression=project.version | grep -v '\['`
VERS=${CURRENT_VERSION/-SNAPSHOT}

PREV_VERSION=`git describe --match 'REL*' --abbrev=0`

echo "<a name=\"version_${VERS}\"></a>"
echo "## Version ${VERS} ($(date +%Y-%m-%d))"
echo
echo Notable changes:
echo

git shortlog --format="%s@@@%H@@@%h@@@" --grep="maven-release-plugin|update versions in readme.md" --extended-regexp --invert-grep --no-merges $PREV_VERSION..HEAD | perl release_notes_filter.pl ${VERS}
