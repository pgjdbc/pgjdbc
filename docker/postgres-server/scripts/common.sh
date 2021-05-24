#!/usr/bin/env bash

if ! (return 0 2>/dev/null); then
    printf "This script should be sourced, not directly executed\n"
    exit 1
fi

log () {
    echo "$@" 1>&2
}

err () {
    echo "$@" 1>&2
    exit 1
}

is_pg_version_at_least () {
    local min_pg_major="${1}"
    [[ "${min_pg_major}" == "$(printf "${min_pg_major}\n${PG_MAJOR}\n" | sort -V | head -n1)" ]]
}

is_option_enabled () {
    local value="${1:-}"
    [[ "${value}" == "true" ]] || [[ "${value}" == "on" ]] || [[ "${value}" == "yes" ]]
}

is_option_disabled () {
    local value="${1:-}"
    [[ "${value}" == "false" ]] || [[ "${value}" == "off" ]] || [[ "${value}" == "no" ]]
}

psql_super () {
    local database_name="${1}"
    local sql="${2}"

    log "Will execute command on database ${database_name}: ${sql}"

    if is_pg_version_at_least "9.0"; then
        psql \
          -v ON_ERROR_STOP=1 \
          --username "$POSTGRES_USER" \
          --dbname "${database_name}" \
          <<<"${sql}"
    else
        gosu postgres postgres --single "${database_name}" -jE <<<"${sql}"
    fi
}
