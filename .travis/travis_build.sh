#!/usr/bin/env bash
set -x -e

if [[ "${FEDORA_CI}" == *"Y" ]];
then
  # Try to prevent "stdout: write error"
  # WA is taken from https://github.com/travis-ci/travis-ci/issues/4704#issuecomment-348435959
  python -c 'import os,sys,fcntl; flags = fcntl.fcntl(sys.stdout, fcntl.F_GETFL); fcntl.fcntl(sys.stdout, fcntl.F_SETFL, flags&~os.O_NONBLOCK);'
  export PROJECT_VERSION=$(mvn -B -N org.apache.maven.plugins:maven-help-plugin:2.1.1:evaluate -Dexpression=project.version | grep -v '\[')
  export PARENT_VERSION=$(mvn -B -N org.apache.maven.plugins:maven-help-plugin:2.1.1:evaluate -Dexpression=project.parent.version | grep -v '\[')
  exec ./packaging/rpm_ci
fi

# Build project
MVN_ARGS="clean package -B -V $MVN_CUSTOM_ARGS"
MVN_PROFILES="release"

if [[ "${NO_WAFFLE_NO_OSGI}" == *"Y"* ]];
then
    MVN_ARGS="$MVN_ARGS -DwaffleEnabled=false -DosgiEnabled=false -DexcludePackageNames=org.postgresql.osgi:org.postgresql.sspi"
fi

if [[ "x${QUERY_MODE}" == *"x"* ]];
then
    MVN_ARGS="$MVN_ARGS -DpreferQueryMode=$QUERY_MODE"
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

# Run Scala-based and Clojure-based tests
if [[ "${TEST_CLIENTS}" == *"Y" ]];
then
  # Pgjdbc should be in "local maven repository" so the clients can use it. Mvn commands above just package it.
  mvn -DskipTests install

  mkdir -p $HOME/.sbt/launchers/0.13.12
  curl -L -o $HOME/.sbt/launchers/0.13.12/sbt-launch.jar http://dl.bintray.com/typesafe/ivy-releases/org.scala-sbt/sbt-launch/0.13.12/sbt-launch.jar

  PROJECT_VERSION=$(mvn -B -N org.apache.maven.plugins:maven-help-plugin:2.1.1:evaluate -Dexpression=project.version | grep -v '\[')

  cd test-anorm-sbt
  sed -i "s/\"org.postgresql\" % \"postgresql\" % \"[^\"]*\"/\"org.postgresql\" % \"postgresql\" % \"${PROJECT_VERSION}\"/" build.sbt
  sbt test

  cd ..

  # Uncomment when https://github.com/clojure/java.jdbc/pull/44 is merged in
  #git clone --depth=10 https://github.com/clojure/java.jdbc.git
  #cd java.jdbc
  #TEST_DBS=postgres TEST_POSTGRES_USER=test TEST_POSTGRES_DBNAME=test mvn test -Djava.jdbc.test.pgjdbc.version=$PROJECT_VERSION
fi
