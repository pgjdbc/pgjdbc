#!/usr/bin/env bash
set -euo pipefail

log () {
    echo "$(date) - $@" 1>&2
}

gen_pg_hba_conf () {
    cat <<EOF
# LOCAL
local   all             all                                     trust
local   replication     all                                     trust
# HOST
host    all                 postgres         0.0.0.0/0          trust
host    all                 test_md5         0.0.0.0/0          md5
host    all                 test_scram       0.0.0.0/0          scram-sha-256
host    all                 all              0.0.0.0/0          scram-sha-256
host    replication         postgres         0.0.0.0/0          trust
EOF
}

gen_postgresql_conf () {
    cat <<EOF
listen_addresses = '*'
fsync = off
synchronous_commit = on
full_page_writes = off
max_wal_senders = 10
max_replication_slots = 10
wal_level = logical
EOF
}

main () {
    log "Initializing with PGDATA=${PGDATA}"

    initdb --no-sync
    gen_pg_hba_conf >"${PGDATA}/pg_hba.conf"
    gen_postgresql_conf >>"${PGDATA}/postgresql.conf"

    log "Creating test user and database"
    postgres --single -D /var/lib/postgresql/data/ <<<"
        CREATE USER test WITH PASSWORD 'test';
        CREATE DATABASE test WITH OWNER test;
    "

    log "Starting PostgreSQL server for GIT_SHA=${GIT_SHA:-} GIT_TAG=${GIT_TAG:-}"
    exec postgres -D "${PGDATA}"
}

main "$@"
