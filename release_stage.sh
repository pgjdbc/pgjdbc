#!/bin/bash -x

# This script should be run from pgjdbc clone, and it deploys pgjdbc, pgjdbc-jre7, and pgjdbc-jre6 to Staging repository
# Later the staged repositories can be inspected or promoted
# Note: if script fails in the middle, manual git reset, and removal of the tag might be required
# Note: the script should be run like ./release_stage.sh 42.1.1 42.1.2

RELEASE_VERSION=$1
NEXT_VERSION=$2

if [ -z "$RELEASE_VERSION" ]; then
  echo "Release version is not set"
  exit 1
fi
if [ -z "$NEXT_VERSION" ]; then
  echo "Next version is not set"
  exit 1
fi

function clone_prev {
  JRE=$1
  if [ -d pgjdbc-jre$JRE ]; then
    echo pgjdbc-jre$JRE already exists
    return
  fi
  echo Cloning pgjdbc-jre$JRE
  echo git clone https://github.com/pgjdbc/pgjdbc-jre$JRE
  echo cd pgjdbc-jre$JRE
  echo git submodule update --init
}

(clone_prev 6)
(clone_prev 7)

function mvn_release {
  SUFFIX=$1
  if [ -f "released_$SUFFIX" ]
  then
    echo "$SUFFIX has already been released"
    return
  fi
  echo
  echo
  echo Releasing $SUFFIX
  echo =================
  echo

  git reset --hard
  mvn release:clean release:prepare -DreleaseVersion=$RELEASE_VERSION$SUFFIX -DdevelopmentVersion=$NEXT_VERSION$SUFFIX-SNAPSHOT -Dtag=REL$RELEASE_VERSION$SUFFIX &&\
  mvn release:perform &&\
  echo https://oss.sonatype.org/content/repositories/staging/org/postgresql/postgresql/$RELEASE_VERSION$SUFFIX/ > released_$SUFFIX
}

function release_prev {
  JRE=$1
  cd pgjdbc-$JRE/pgjdbc &&\
  git fetch &&\
  git reset --hard REL$RELEASE_VERSION &&\
  cd .. &&\
  git checkout master &&\
  git reset --hard origin/master &&\
  git add pgjdbc &&\
  git commit -m "Update pgjdbc to $RELEASE_VERSION" &&\
  mvn_release .$JRE
}

mvn_release &&\
(release_prev jre7) &&\
(release_prev jre6)
