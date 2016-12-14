#!/usr/bin/env bash
# Adapted from https://github.com/dockyard/reefpoints/blob/master/source/posts/2013-03-29-running-postgresql-9-2-on-travis-ci.md
set -x -e

if [ -z "$PG_VERSION" ]
then
    echo "env PG_VERSION not define";
elif [ "${PG_VERSION}" = "HEAD" ]
then
    ./.travis/travis_install_head_postgres.sh
elif [ ! -d "${PG_DATADIR}" ]
then
    sudo cp /etc/postgresql/9.4/main/pg_hba.conf ./
    sudo apt-get -qq purge postgresql* libpq-dev libpq5
    source /etc/lsb-release
    echo "deb http://apt.postgresql.org/pub/repos/apt/ $DISTRIB_CODENAME-pgdg main ${PG_VERSION}" > pgdg.list
    sudo mv pgdg.list /etc/apt/sources.list.d/
    wget --quiet -O - https://apt.postgresql.org/pub/repos/apt/ACCC4CF8.asc | sudo apt-key add -
    sudo apt-get -qq update
    sudo apt-get -qq -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confnew" install postgresql-${PG_VERSION} postgresql-contrib-${PG_VERSION}
    sudo cp ./pg_hba.conf /etc/postgresql/${PG_VERSION}/main/
    sudo service postgresql restart
    # sudo tail /var/log/postgresql/postgresql-${PG_VERSION}-main.log
fi
