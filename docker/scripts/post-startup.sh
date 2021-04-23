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
}

main "$@"
