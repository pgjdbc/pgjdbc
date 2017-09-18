#!/usr/bin/env bash

set -o xtrace -o errexit

if [[ "${TRAVIS_SUDO}" == "true" && -n "${ZULU_JDK}" ]]
then
    # Install OpenJDK Zulu from repository
    sudo apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys 0x219BD9C9
    sudo apt-add-repository 'deb http://repos.azulsystems.com/ubuntu stable main'
    sudo apt-get update -qq && sudo apt-get install zulu-${ZULU_JDK} -y
fi
