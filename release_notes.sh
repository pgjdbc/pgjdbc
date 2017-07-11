#!/bin/sh


CURRENT_VERSION=`mvn -B -N org.apache.maven.plugins:maven-help-plugin:2.1.1:evaluate -Dexpression=project.version | grep -v '\['`
VERS=${CURRENT_VERSION/-SNAPSHOT}

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
echo '*'
echo '<!--more-->'
echo

git shortlog --format="%s@@@%H@@@%h@@@" --grep="maven-release-plugin|update versions in readme.md" --extended-regexp --invert-grep --no-merges $PREV_VERSION..HEAD | perl release_notes_filter.pl ${VERS}
