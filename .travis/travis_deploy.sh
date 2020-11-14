#!/usr/bin/env bash
set -x -e

# Skip tests and checkstype to speed up snapshot deployment
MVN_ARGS="clean deploy -B -V -DskipTests -Dcheckstyle.skip=true -Dskip.assembly=true --settings settings.xml"

mvn ${MVN_ARGS} -P release-artifacts,release -Dskip.unzip-jdk-src=false
