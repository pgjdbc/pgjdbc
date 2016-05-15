#!/usr/bin/env bash
set -x -e

# Build project
MVN_ARGS="clean package -B -V"
MVN_PROFILES="release"

if [[ "${NO_WAFFLE_NO_OSGI}" == *"Y"* ]];
then
    MVN_ARGS="$MVN_ARGS -DwaffleEnabled=false -DosgiEnabled=false -DexcludePackageNames=org.postgresql.osgi:org.postgresql.sspi"
fi

if [[ "${OSGI}" == *"Y"* ]];
then
    # OSGi logs a lot to commons logging, thus we turn it off
    MVN_ARGS="$MVN_ARGS -Dorg.apache.commons.logging.Log=no_commmons_logging_please"
else
    # Exclude OSGi test module
    MVN_ARGS="$MVN_ARGS -Dpgjdbc-osgi-test=skip"
fi

if [[ "${COVERAGE}" == *"Y"* ]];
then
    MVN_PROFILES="$MVN_PROFILES,coverage"
fi

if [[ "${TRAVIS_JDK_VERSION}" == *"jdk6"* ]];
then
    git clone --depth=50 https://github.com/pgjdbc/pgjdbc-jre6.git pgjdbc-jre6
    cd pgjdbc-jre6
    mvn ${MVN_ARGS} -P ${MVN_PROFILES},skip-unzip-jdk
elif [[ "${TRAVIS_JDK_VERSION}" == *"jdk7"* ]];
then
    git clone --depth=50 https://github.com/pgjdbc/pgjdbc-jre7.git pgjdbc-jre7
    cd pgjdbc-jre7
    mvn ${MVN_ARGS} -P ${MVN_PROFILES},skip-unzip-jdk

    if [[ "${OSGI}" == *"Y"* ]];
    then
      PGJDBC_VERSION=$(mvn -q -Dexec.executable='echo' -Dexec.args='${project.version}' --non-recursive exec:exec)
      cd ../pgjdbc-osgi-test
      mvn ${MVN_ARGS} -P ${MVN_PROFILES} -Dpgjdbc.version=$PGJDBC_VERSION
    fi
elif [ "${PG_VERSION}" == "9.4" ];
then
# Build javadocs for Java 8 and PG 9.4 only
    mvn ${MVN_ARGS} -P ${MVN_PROFILES},release-artifacts
else
    mvn ${MVN_ARGS} -P ${MVN_PROFILES}
fi

if [[ "${COVERAGE}" == "Y" ]];
then
    pip install --user codecov
    codecov
fi
