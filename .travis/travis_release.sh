#!/usr/bin/env bash

# Fail script on error
set -e

cd .travis
mkdir secrets

# GPG is required for artifact signing
echo $SUPER_SECRET_KEY | gpg --passphrase-fd 0 secrets.tar.gpg

mv secrets.tar secrets
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

# Remove tmp branch if exists
git push git@github.com:$TRAVIS_REPO_SLUG$JRE.git ":$TMP_BRANCH" || true

set -x

RELEASE_TAG=REL$RELEASE_VERSION
if [[ "x$JRE" == "x" ]]; then
  # Note: release should be a fast-forward for the "master" branch
  # If push dry-run fails, the script just terminates
  git push --dry-run git@github.com:$TRAVIS_REPO_SLUG.git "HEAD:$ORIGINAL_BRANCH"

  git checkout -b "$TMP_BRANCH"


# Remove release tag if exists just in case
git push git@github.com:$TRAVIS_REPO_SLUG$JRE.git :$RELEASE_TAG || true

# -Darguments here is for maven-release-plugin
MVN_SETTINGS=$(pwd)/settings.xml
mvn -B --settings settings.xml -Darguments="--settings '${MVN_SETTINGS}' -Dskip.unzip-jdk-src=false" -Dskip.unzip-jdk-src=false release:prepare release:perform -Darguments=-Dgpg.passphrase=$GPG_PASSPHRASE

# Point "master" branch to "next development snapshot commit"
git push git@github.com:$TRAVIS_REPO_SLUG.git "HEAD:$ORIGINAL_BRANCH"
# Removal of the temporary branch is a separate command, so it left behind for the analysis in case "push to master" fails
git push git@github.com:$TRAVIS_REPO_SLUG.git ":$TMP_BRANCH"
