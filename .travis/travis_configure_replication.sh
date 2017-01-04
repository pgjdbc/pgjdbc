#!/usr/bin/env bash
set -x -e

if [ "${REPLICATION}" = "Y" ]
then
    if [ "${PG_VERSION}" = "HEAD" ]
    then
        PG_VERSION="9.5"
    fi

    if (( $(echo "${PG_VERSION} >= 9.1" | bc -l)))
    then
        sudo sed -i -e 's/#max_wal_senders = 0/max_wal_senders = 4/g' ${PG_DATADIR}/postgresql.conf
        sudo sed -i -e 's/#wal_keep_segments = 0/wal_keep_segments = 4/g' ${PG_DATADIR}/postgresql.conf
        sudo sed -i -e 's/#wal_sender_timeout = .*/wal_sender_timeout = 2000/g' ${PG_DATADIR}/postgresql.conf
        sudo sed -i -e 's/^#local\s\+replication\s\+postgres\s\+\(.*\)/local replication all \1/g' ${PG_DATADIR}/pg_hba.conf
        sudo sed -i -e 's/^#host\s\+replication\s\+postgres\s\+\(.*\)\s\+\(.*\)/host replication all \1 \2/g' ${PG_DATADIR}/pg_hba.conf
        if (( $(echo "${PG_VERSION} >= 9.4" | bc -l)))
        then
            sudo sed -i -e 's/#wal_level = minimal/wal_level = logical/g' ${PG_DATADIR}/postgresql.conf
            sudo sed -i -e 's/#max_replication_slots = 0/max_replication_slots = 4/g' ${PG_DATADIR}/postgresql.conf
        fi
    fi
fi
