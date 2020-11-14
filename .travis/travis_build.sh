#!/usr/bin/env bash
set -x -e

if [[ $FEDORA_CI == "Y" ]];
then
  # Prepare "source release" archive
  ./gradlew :postgresql:sourceDistribution -Prelease

  # Copy file to packaging directory, so rpm_ci would use it rather that downloading it from release URL
  cp pgjdbc/build/distributions/postgresql-*-src.tar.gz packaging/rpm

  # Try to prevent "stdout: write error"
  # WA is taken from https://github.com/travis-ci/travis-ci/issues/4704#issuecomment-348435959
  python -c 'import os,sys,fcntl; flags = fcntl.fcntl(sys.stdout, fcntl.F_GETFL); fcntl.fcntl(sys.stdout, fcntl.F_SETFL, flags&~os.O_NONBLOCK);'
  export PROJECT_VERSION=$(grep pgjdbc.version gradle.properties | cut -d "=" -f2-)
  # Removal of PARENT_VERSION requires rebuild of praiskup/copr-and-jdbc-ci Docker image
  export PARENT_VERSION=unused_but_passed_to_make_script_inside_docker_happy

  exec ./packaging/rpm_ci
fi

# Build project
# TODO: run SlowTests as well
GRADLE_ARGS="--no-daemon -PskipAutostyle -PskipCheckstyle build $MVN_CUSTOM_ARGS"
MVN_PROFILES="release"

if [[ $REPLICATION != "Y" ]];
then
    GRADLE_ARGS="$GRADLE_ARGS -PskipReplicationTests"
fi

if [[ $SLOW_TESTS != "Y" ]];
then
    GRADLE_ARGS="$GRADLE_ARGS -PincludeTestTags=!org.postgresql.test.SlowTests"
fi

if [[ $QUERY_MODE != "" ]];
then
    GRADLE_ARGS="$GRADLE_ARGS -DpreferQueryMode=$QUERY_MODE"
fi

if [[ $COVERAGE == "Y" ]];
then
    GRADLE_ARGS="$GRADLE_ARGS jacocoReport"
fi

if [[ $JDK == "9" ]];
then
    export MAVEN_SKIP_RC=true
    GRADLE_ARGS="$GRADLE_ARGS -Dcurrent.jdk=1.9 -Djavac.target=1.9"
fi

if [[ $BUILD_SCAN == "Y" ]];
then
    GRADLE_ARGS="$GRADLE_ARGS --scan"
fi

if [[ $JDOC == "Y" ]];
then
    # Build javadocs for Java 8 only
    ./gradlew $GRADLE_ARGS javadoc
else
    ./gradlew $GRADLE_ARGS
fi

if [[ $COVERAGE == "Y" ]];
then
    pip install --user codecov
    codecov
fi

# Run Scala-based and Clojure-based tests
if [[ $TEST_CLIENTS == "Y" ]];
then
  # Pgjdbc should be in "local maven repository" so the clients can use it
  ./gradlew publishToMavenLocal -Ppgjdbc.version=1.0.0-dev-master -PskipJavadoc

  mkdir -p $HOME/.sbt/launchers/0.13.12
  curl -L -o $HOME/.sbt/launchers/0.13.12/sbt-launch.jar http://dl.bintray.com/typesafe/ivy-releases/org.scala-sbt/sbt-launch/0.13.12/sbt-launch.jar

  cd test-anorm-sbt
  sed -i "s/\"org.postgresql\" % \"postgresql\" % \"[^\"]*\"/\"org.postgresql\" % \"postgresql\" % \"1.0.0-dev-master-SNAPSHOT\"/" build.sbt
  sbt test

  cd ..

  # Uncomment when https://github.com/clojure/java.jdbc/pull/44 is merged in
  #git clone --depth=10 https://github.com/clojure/java.jdbc.git
  #cd java.jdbc
  #TEST_DBS=postgres TEST_POSTGRES_USER=test TEST_POSTGRES_DBNAME=test mvn test -Djava.jdbc.test.pgjdbc.version=$PROJECT_VERSION
fi
