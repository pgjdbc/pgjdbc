#!/usr/bin/env bash

set -o xtrace -o errexit

if [[ "$TRAVIS_SUDO" == "true" && -n "$ZULU_JDK" ]]
then
    # Install OpenJDK Zulu from repository
    sudo apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys 0x219BD9C9
    sudo apt-add-repository 'deb http://repos.azulsystems.com/ubuntu stable main'
    sudo apt-get update -qq && sudo apt-get install zulu-$ZULU_JDK -y

    # Build using Toolchain OpenJDK Zulu
    if [ "$ZULU_JDK" -eq 6 ]; then
      export JDK6_HOME=/usr/lib/jvm/zulu-$ZULU_JDK-amd64
    elif [ "$ZULU_JDK" -eq 7 ]; then
      export JDK7_HOME=/usr/lib/jvm/zulu-$ZULU_JDK-amd64
    elif [ "$ZULU_JDK" -eq 8 ]; then
      export JDK8_HOME=/usr/lib/jvm/zulu-$ZULU_JDK-amd64
    elif [ "$ZULU_JDK" -eq 9 ]; then
      export JDK9_HOME=/usr/lib/jvm/zulu-$ZULU_JDK-amd64
    fi
fi
