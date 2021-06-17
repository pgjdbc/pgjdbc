#!/usr/bin/env bash
set -euo pipefail

. /custom/scripts/common.sh

main () {
    # Make a copy of certdir so we can edit the files
    cp -r /custom/certdir /home/certdir
    chown postgres:postgres /home/certdir/*.key
    chmod 0600 /home/certdir/*.key

    local pg_opts=""
    add_pg_opt () {
        pg_opts="${pg_opts} ${1}"
    }

    if is_option_disabled "${FSYNC}"; then
        add_pg_opt "-c fsync=off"
    fi

    if is_option_disabled "${SYNC_COMMIT}"; then
        add_pg_opt "-c synchronous_commit=off"
    fi

    if is_option_disabled "${FULL_PAGE_WRITES}"; then
        add_pg_opt "-c full_page_writes=off"
    fi

    if is_option_disabled "${AUTO_VACCUUM}"; then
        add_pg_opt "-c autovacuum=off"
    fi

    if is_option_disabled "${TRACK_COUNTS}"; then
        add_pg_opt "-c track_counts=off"
    fi

    # Customize pg_hba.conf
    local pg_hba="/home/certdir/pg_hba.conf"
    sed -i 's/127.0.0.1\/32/0.0.0.0\/0/g' "${pg_hba}"
    add_pg_opt "-c hba_file=${pg_hba}"

    if is_option_enabled "${SSL}"; then
        is_pg_version_at_least "9.3" || err "SSL testing requires a minimum of 9.3 but PG_MAJOR=${PG_MAJOR}. Disable with SSL=off"
        add_pg_opt "-c ssl=on"
        add_pg_opt "-c ssl_cert_file=/home/certdir/server.crt"
        add_pg_opt "-c ssl_key_file=/home/certdir/server.key"
        add_pg_opt "-c ssl_ca_file=/home/certdir/root.crt"
    else
        sed  -i -e "s/^hostssl\b/#hostssl/" "${pg_hba}"
    fi

    if is_option_enabled "${XA}"; then
        pg_opts="${pg_opts} -c max_prepared_transactions=64"    
    fi

    if is_pg_version_at_least "9.4"; then
        pg_opts="${pg_opts} -c wal_level=logical"
        if ! is_pg_version_at_least "10"; then
            echo "host    replication         postgres         0.0.0.0/0          trust" >>"${pg_hba}"
            add_pg_opt "-c max_wal_senders=10"
            add_pg_opt "-c max_replication_slots=10"
        fi
        echo "local   replication         postgres                          trust" >>"${pg_hba}"
    fi

    cp /custom/scripts/post-startup.sh /docker-entrypoint-initdb.d/
    chmod +x /docker-entrypoint-initdb.d/post-startup.sh

    echo "Starting postgres version $PG_MAJOR with options: ${pg_opts} $@"

    local entrypoint_script
    if [[ -x /docker-entrypoint.sh ]]; then
        entrypoint_script='/docker-entrypoint.sh'
    elif [[ -x /usr/local/bin/docker-entrypoint.sh ]]; then
        # On 13+ the script is not longer symlinked to the root
        entrypoint_script='/usr/local/bin/docker-entrypoint.sh'
    else
        err "Could not find postgres container entry point script"
    fi
    exec "${entrypoint_script}" "$@" ${pg_opts}
}

main "$@"
