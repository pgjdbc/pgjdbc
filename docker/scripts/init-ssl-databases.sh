#!/usr/bin/env bash
set -euo pipefail

main () {
    local database_name_list=(
        hostdb
        hostssldb
        hostnossldb
        certdb
        hostsslcertdb
    )

    for database_name in ${database_name_list[@]}
    do
        createdb -U postgres "${database_name}"
        psql -U postgres "${database_name}" -c "CREATE EXTENSION sslinfo"
    done    
}

main "$@"
