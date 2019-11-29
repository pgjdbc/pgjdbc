#!/usr/bin/env bash
set -x -e

# Skip tests and checkstype to speed up snapshot deployment
MVN_ARGS="clean deploy -B -V -DskipTests -Dcheckstyle.skip=true -Dskip.assembly=true --settings settings.xml"

if [[ "${TRAVIS_JDK_VERSION}" == *"jdk6"* ]];
then
    cd pgjdbc-jre6
    mvn ${MVN_ARGS} -P release-artifacts,release,skip-unzip-jdk
elif [[ "${TRAVIS_JDK_VERSION}" == *"jdk7"* ]];
then
    cd pgjdbc-jre7
    mvn ${MVN_ARGS} -P release-artifacts,release,skip-unzip-jdk
else
    mvn ${MVN_ARGS} -P release-artifacts,release -Dskip.unzip-jdk-src=false
fi
