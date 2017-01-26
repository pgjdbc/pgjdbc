#!/usr/bin/env bash

check_core_dump() {
    local status=0
    while IFS= read -r core_dump_file
    do
        status=1
        echo "Detected core dump: ${core_dump_file}"
        echo bt full | sudo gdb --quiet /usr/local/pgsql/bin/postgres "${core_dump_file}"
    done < <(sudo find "${PG_DATADIR}" -type f -iname "core*")
    return ${status}
}

print_logs() {
    if [[ "${PG_VERSION}" = "HEAD" ]]
    then
      sudo cat /tmp/postgres.log
    else
      cat "/var/log/postgresql/postgresql-${PG_VERSION}-main.log"
    fi
}

check_core_dump
if [[ $? -ne 0 ]]
then
    print_logs
    exit 1
fi