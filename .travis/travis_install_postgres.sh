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
    sudo apt-get remove postgresql libpq-dev libpq5 postgresql-client-common postgresql-common -qq --purge
    source /etc/lsb-release
    echo "deb http://apt.postgresql.org/pub/repos/apt/ $DISTRIB_CODENAME-pgdg main ${PG_VERSION}" > pgdg.list
    sudo mv pgdg.list /etc/apt/sources.list.d/
    wget --quiet -O - https://apt.postgresql.org/pub/repos/apt/ACCC4CF8.asc | sudo apt-key add -
    sudo apt-get update
    sudo apt-get -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confnew" install postgresql-${PG_VERSION} postgresql-contrib-${PG_VERSION} -qq

    sudo sh -c "echo 'local all postgres trust' > /etc/postgresql/${PG_VERSION}/main/pg_hba.conf"
    sudo sh -c "echo 'local all all trust' >> /etc/postgresql/${PG_VERSION}/main/pg_hba.conf"
    sudo sh -c "echo -n 'host all all 127.0.0.1/32 trust' >> /etc/postgresql/${PG_VERSION}/main/pg_hba.conf"

    if [ ${PG_VERSION} = '8.4' ]
    then
      sudo sed -i -e 's/port = 5433/port = 5432/g' /etc/postgresql/8.4/main/postgresql.conf
    fi

    sudo service postgresql restart ${PG_VERSION}
    sudo tail /var/log/postgresql/postgresql-${PG_VERSION}-main.log
fi