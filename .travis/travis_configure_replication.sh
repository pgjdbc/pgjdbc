#!/usr/bin/env bash
set -x -e

set_conf_property() {
    local key=${1}
    local value=${2}

    sudo sed -i -e "s/^#\?${key}.*/${key} = ${value}/g" ${PG_DATADIR}/postgresql.conf
}

if [ "${REPLICATION}" = "Y" ]
then
    if [ "${PG_VERSION}" = "HEAD" ]
    then
        PG_VERSION="9.5"
    fi

    if (( $(echo "${PG_VERSION} >= 9.1" | bc -l)))
    then
        set_conf_property "max_wal_senders" "10"
        set_conf_property "wal_keep_segments" "10"
        set_conf_property "wal_sender_timeout" "5s"
        sudo sed -i -e 's/^#local\s\+replication\s\+postgres\s\+\(.*\)/local replication all \1/g' ${PG_DATADIR}/pg_hba.conf
        sudo sed -i -e 's/^#host\s\+replication\s\+postgres\s\+\(.*\)\s\+\(.*\)/host replication all \1 \2/g' ${PG_DATADIR}/pg_hba.conf
        if (( $(echo "${PG_VERSION} >= 9.4" | bc -l)))
        then
            set_conf_property "wal_level" "logical"
            set_conf_property "max_replication_slots" "10"
        fi
    fi
fi
