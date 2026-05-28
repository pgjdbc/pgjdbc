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

    if is_option_enabled "${OAUTH:-no}" && is_pg_version_at_least "18"; then
        echo "Installing pg_oidc_validator for OAuth testing..."
        apt-get update -qq
        apt-get install -y -qq wget libcurl4
        local deb_url="https://github.com/Percona-Lab/pg_oidc_validator/releases/download/latest/pg-oidc-validator-pgdg18.deb"
        wget -q -O /tmp/pg_oidc_validator.deb "${deb_url}"
        apt-get install -y /tmp/pg_oidc_validator.deb
        rm /tmp/pg_oidc_validator.deb

        # Username mapping: JWT preferred_username "testoauth" → DB role "testoauth"
        cat > /tmp/pg_ident.conf <<'IDENT'
# map-name    system-username    database-username
oauthmap      testoauth          testoauth
IDENT

        # The oauth auth method only exists on PG 18+.
        # Insert the OAuth rule above all host rules so it takes precedence.
        sed -i '/^# TYPE\b/a\
host    all         testoauth   all      oauth   scope=pgjdbc,issuer=http://keycloak:8080/realms/pgjdbc,map=oauthmap' "${pg_hba}"

        add_pg_opt "-c oauth_validator_libraries=pg_oidc_validator"
        add_pg_opt "-c pg_oidc_validator.authn_field=preferred_username"
        add_pg_opt "-c ident_file=/tmp/pg_ident.conf"
    fi

    if is_option_enabled "${XA}"; then
        pg_opts="${pg_opts} -c max_prepared_transactions=64"
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
