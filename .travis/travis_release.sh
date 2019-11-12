#!/usr/bin/env bash

# Fail script on error
set -e

cd .travis
mkdir secrets

# GPG is required for artifact signing
openssl aes-256-cbc -k "$SUPER_SECRET_KEY" -in secrets.tar.enc -out secrets/secrets.tar -d

cd secrets
tar xvf secrets.tar

gpg --import gpg-secret.key
gpg --import-ownertrust gpg-ownertrust

# Decrypt GitHub SSH key
chmod 600 github_deploy
eval $(ssh-agent -s)
ssh-add ./github_deploy

cd ..
rm -rf ./secrets

cd ..

git config --global user.name "pgjdbc CI"
git config --global user.email "pgsql-jdbc@postgresql.org"

# By default Travis checks out commit, and maven-release-plugin wants to know branch name
# On top of that, maven-release-plugin publishes branch, and it would terminate Travis job (current one!),
# so we checkout a non-existing branch, so it won't get published
# Note: at the end, we need to update "master" branch accordingly" (see $ORIGINAL_BRANCH)
TMP_BRANCH=tmp/$TRAVIS_BRANCH

CURRENT_VERSION=$(mvn -B -N org.apache.maven.plugins:maven-help-plugin:2.1.1:evaluate -Dexpression=project.version | grep -v '\[')
RELEASE_VERSION=${CURRENT_VERSION/-SNAPSHOT}

ORIGINAL_BRANCH=${TRAVIS_BRANCH#release/}

REPO=$TRAVIS_REPO_SLUG
JRE=
if [[ "${TRAVIS_JDK_VERSION}" == *"jdk7"* ]]; then
  JRE=-jre7
elif [[ "${TRAVIS_JDK_VERSION}" == *"jdk6"* ]]; then
  JRE=-jre6
fi

# Remove tmp branch if exists
git push git@github.com:$TRAVIS_REPO_SLUG$JRE.git ":$TMP_BRANCH" || true

set -x

RELEASE_TAG=REL$RELEASE_VERSION
if [[ "x$JRE" == "x" ]]; then
  # Note: release should be a fast-forward for the "master" branch
  # If push dry-run fails, the script just terminates
  git push --dry-run git@github.com:$TRAVIS_REPO_SLUG.git "HEAD:$ORIGINAL_BRANCH"

  git checkout -b "$TMP_BRANCH"
else
  git fetch --unshallow
  # Use RELx.y.z.jreN
  RELEASE_TAG=$RELEASE_TAG.${JRE#-}
  # The following updates pgjdbc submodule of pgjdbc-jreX to the relevant RELx.y.z release tag
  git clone -b "$ORIGINAL_BRANCH" --depth=50 https://github.com/$TRAVIS_REPO_SLUG$JRE.git pgjdbc$JRE

  cd pgjdbc$JRE

  # Use tmp branch for the release, so mvn release would use that
  git checkout -b "$TMP_BRANCH"

  git submodule update --init

  # Force relevant version for jreN repository
  mvn -DnewVersion=$RELEASE_VERSION.${JRE#-}-SNAPSHOT versions:set versions:commit
  # Add all known pom.xml files in the repository
  git add -u \*pom.xml

  cd pgjdbc
  git fetch
  git checkout "$ORIGINAL_BRANCH"
  git reset --hard REL$RELEASE_VERSION
  cd ..
  git add pgjdbc
  git commit -m "Update pgjdbc to $RELEASE_VERSION"

  # Note: we are IN pgjdbc-jreX sub-folder, and the subsequent release would release "jre-specific" version
fi

# Remove release tag if exists just in case
git push git@github.com:$TRAVIS_REPO_SLUG$JRE.git :$RELEASE_TAG || true

# -Darguments here is for maven-release-plugin
MVN_SETTINGS=$(pwd)/settings.xml
mvn -B --settings settings.xml -Darguments="--settings '${MVN_SETTINGS}' -Dskip.unzip-jdk-src=false" -Dskip.unzip-jdk-src=false release:prepare release:perform

# Point "master" branch to "next development snapshot commit"
git push git@github.com:$TRAVIS_REPO_SLUG$JRE.git "HEAD:$ORIGINAL_BRANCH"
# Removal of the temporary branch is a separate command, so it left behind for the analysis in case "push to master" fails
git push git@github.com:$TRAVIS_REPO_SLUG$JRE.git ":$TMP_BRANCH"
