#!/usr/bin/env bash
set -euo pipefail

. /custom/scripts/common.sh

main () {
    # The official postgres image exports PG_MAJOR; the from-source image does
    # not. Derive a bare integer major from the server binary when unset so the
    # version checks below (and the upstream docker-entrypoint.sh) work.
    : "${PG_MAJOR:=$(postgres --version | grep -oE '[0-9]+' | head -n1)}"
    export PG_MAJOR

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

    if is_option_disabled "${AUTO_VACUUM}"; then
        add_pg_opt "-c autovacuum=off"
    fi

    if is_option_disabled "${TRACK_COUNTS}"; then
        add_pg_opt "-c track_counts=off"
    fi

    # Customize pg_hba.conf
    local pg_hba="/home/certdir/pg_hba.conf"
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

    if is_option_enabled "${DOMAIN_SOCKET:-no}"; then
        # The socket directory is bind-mounted from the host so that tests running on the host can
        # connect via a Unix domain socket. Make it writable by postgres and world-accessible so the
        # host user (a different uid) can connect to the socket.
        mkdir -p /var/run/postgresql
        chown postgres:postgres /var/run/postgresql
        chmod 0777 /var/run/postgresql
        add_pg_opt "-c unix_socket_directories=/var/run/postgresql"
        add_pg_opt "-c unix_socket_permissions=0777"
        # Allow local (Unix socket) connections for the unprivileged test user. The default
        # pg_hba.conf only grants local access to the postgres superuser.
        echo "local   all             all                               trust" >> "${pg_hba}"
    fi

    if is_pg_version_less_than "10"; then
        add_pg_opt "-c max_locks_per_transaction=256"
    fi

    if is_pg_version_at_least "10"; then
        # PostgreSQL 10+ supports scram
        echo "host    authtest        scram           all            scram-sha-256" >> "${pg_hba}"
    fi

    if ! is_pg_version_at_least "12"; then
      # PostgreSQL <= 11 supports clientcert=0|1 only
      sed -i -e "s/clientcert=verify-full/clientcert=1/" "${pg_hba}"
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
