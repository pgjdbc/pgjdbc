#!/usr/bin/env bash
set -euo pipefail

. /custom/scripts/common.sh

get_password_encryption () {
    if is_option_enabled "${SCRAM}"; then
        is_pg_version_at_least "10" || err "SCRAM requires a minimum of 10 but PG_MAJOR=${PG_MAJOR}. Disable with SCRAM=off"
        printf "scram-sha-256"
    else
        printf "on"
    fi
}

create_replica () {
    local name="${1}"
    local port="${2}"

    local name_pattern='^[a-z][a-z0-9_]+[a-z0-9]$'
    [[ "${name}" =~ ${name_pattern} ]] || err "Invalid replica name: ${name}"
    local port_pattern='^[1-9][0-9]+$'
    [[ "${port}" =~ ${port_pattern} ]] || err "Invalid replica port: ${port}"

    log "Creating replica ${name}"

    local full_name="replica_${name}"
    local replica_data_dir="/tmp/${full_name}"
    local replication_slot_name="${full_name}"
    local replication_user="${full_name}"
    local replication_pass="test"

    psql_super "${POSTGRES_DB}" "
        SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots WHERE slot_name = '${replication_slot_name}';
        DROP USER IF EXISTS ${replication_user};
        CREATE USER ${replication_user} WITH REPLICATION PASSWORD '${replication_pass}';
        SELECT * FROM pg_create_physical_replication_slot('${replication_slot_name}');
    "
    pg_basebackup -D "${replica_data_dir}" -S "${replication_slot_name}" -X stream -P -Fp -R

    cat <<EOF >>"${replica_data_dir}/postgresql.conf"
port = ${port}
max_prepared_transactions = 64
hba_file = '/home/certdir/pg_hba.conf'
log_line_prefix = 'REPLICA<${name}>: %m [%p]'
EOF

    log "Starting replica ${name} that will listen on port ${port}"
    pg_ctl start -D "${replica_data_dir}"
}

main () {
    psql_super "${POSTGRES_DB}" "
      SELECT version()
    "

    local password_encryption
    password_encryption="$(get_password_encryption)"
    # Create primary test user
    psql_super "${POSTGRES_DB}" "
      SET password_encryption='${password_encryption}';
      CREATE USER test with password 'test';
    "
    psql_super "${POSTGRES_DB}" "
      CREATE DATABASE test OWNER test
    "

    if ! is_pg_version_at_least "9.0"; then
        # Older versions do not have plpgsql so we explicitly install it
        psql_super "test" "CREATE LANGUAGE plpgsql"
    fi

    if is_pg_version_at_least "9.1"; then
        psql_super "test" "CREATE EXTENSION hstore"
    fi

    # Create additional databases for SSL testing
    local database_name_list=(
        hostdb
        hostssldb
        hostnossldb
        certdb
        hostsslcertdb
    )

    for database_name in ${database_name_list[@]}
    do
        psql_super "${POSTGRES_DB}" "
          CREATE DATABASE ${database_name}
        "
        if is_pg_version_at_least "9.1"; then
            psql_super "${database_name}" "
              CREATE EXTENSION sslinfo
            "
        fi
    done

    if is_option_enabled "${CREATE_REPLICAS}"; then
        create_replica one 5433
        create_replica two 5434
    fi
}

main "$@"
