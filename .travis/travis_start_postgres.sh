#!/usr/bin/env bash
set -x -e

if [ -z "$PG_VERSION" ]
then
    echo "env PG_VERSION not define";
elif [ "x${PG_VERSION}" = "xHEAD" ]
then
    #Start head postgres
    sudo su postgres -c "/usr/local/pgsql/bin/pg_ctl -D ${PG_DATADIR} -w -t 300 -c -o '-p 5432' -l /tmp/postgres.log start"
    sudo tail /tmp/postgres.log
elif [ "$XA" = "true" ] || [ "${REPLICATION}" = "Y" ] || [ "${SSLTEST}" = "Y" ]
then
    sudo service postgresql stop
    sudo service postgresql start ${PG_VERSION}
    sudo tail /var/log/postgresql/postgresql-${PG_VERSION}-main.log
fi
