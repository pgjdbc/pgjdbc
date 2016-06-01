#!/usr/bin/env bash
set -x -e

if [[ "${FEDORA_CI}" == *"Y" ]];
then
  PARENT_VERSION=$(mvn -B -N org.apache.maven.plugins:maven-help-plugin:2.1.1:evaluate -Dexpression=project.parent.version | grep -v '\[')
  sed -i "s/^%global parent_ver	.*/%global parent_ver	${PARENT_VERSION}/" packaging/rpm/postgresql-jdbc.spec.tpl
  exec ./packaging/rpm_ci
fi

# Build project
MVN_ARGS="clean package -B -V $MVN_CUSTOM_ARGS"
MVN_PROFILES="release"

if [[ "${NO_WAFFLE_NO_OSGI}" == *"Y"* ]];
then
    MVN_ARGS="$MVN_ARGS -DwaffleEnabled=false -DosgiEnabled=false -DexcludePackageNames=org.postgresql.osgi:org.postgresql.sspi"
fi

if [[ "${COVERAGE}" == *"Y"* ]];
then
    MVN_PROFILES="$MVN_PROFILES,coverage"
fi

if [[ "${JDK}" == *"9"* ]];
then
    export MAVEN_SKIP_RC=true
    MVN_ARGS="$MVN_ARGS -Dcurrent.jdk=1.9 -Djavac.target=1.9"
fi

if [[ "$JDOC" == *"Y"* ]];
then
    # Build javadocs for Java 8 only
    mvn ${MVN_ARGS} -P ${MVN_PROFILES},release-artifacts
elif [[ "${TRAVIS_JDK_VERSION}" == *"jdk6"* ]];
then
    git clone --depth=50 https://github.com/pgjdbc/pgjdbc-jre6.git pgjdbc-jre6
    cd pgjdbc-jre6
    mvn ${MVN_ARGS} -P ${MVN_PROFILES},skip-unzip-jdk
elif [[ "${TRAVIS_JDK_VERSION}" == *"jdk7"* ]];
then
    git clone --depth=50 https://github.com/pgjdbc/pgjdbc-jre7.git pgjdbc-jre7
    cd pgjdbc-jre7
    mvn ${MVN_ARGS} -P ${MVN_PROFILES},skip-unzip-jdk
else
    mvn ${MVN_ARGS} -P ${MVN_PROFILES}
fi

if [[ "${COVERAGE}" == "Y" ]];
then
    pip install --user codecov
    codecov
fi
