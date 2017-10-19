#!/usr/bin/env bash

set -o xtrace -o errexit

if [[ "${TRAVIS_SUDO}" == "true" && -n "${OPENJ9}" ]]
then
  # Build to Download
  BUILD=${OPENJ9}
  # Download from AdoptOpenJDK
  wget --quiet -O /tmp/openj9.tar.gz https://github.com/AdoptOpenJDK/openjdk9-openj9-releases/releases/download/jdk-9%2B${BUILD}/OpenJDK9-OPENJ9_x64_Linux_jdk-9.${BUILD}.tar.gz
  tar xfz /tmp/openj9.tar.gz -C ${HOME} # extract to ${HOME}/jdk-9+${BUILD}/
  ${HOME}/jdk-9+${BUILD}/bin/java -version
fi
