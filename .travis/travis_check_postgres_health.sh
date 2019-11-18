#!/usr/bin/env bash

check_core_dump() {
    local command_to_find="find ${PG_DATADIR} -type f -iname core*"
    if [ ! -r "${PG_DATADIR}" ]
    then
        command_to_find="sudo ${command_to_find}"
    fi

    local status=0
    while IFS= read -r core_dump_file
    do
        status=1
        echo "Detected core dump: ${core_dump_file}"
        echo bt full | sudo gdb --quiet /usr/local/pgsql/bin/postgres "${core_dump_file}"
    done < <(${command_to_find})
    return ${status}
}

print_logs() {
    local log_file
    if [[ "${PG_VERSION}" = "HEAD" ]]
    then
      log_file="/tmp/postgres.log"
    else
      log_file="/var/log/postgresql/postgresql-${PG_VERSION}-main.log"
    fi

    if [ -r "${log_file}" ]
    then
        cat "${log_file}"
    else
        sudo cat "${log_file}"
    fi
}

check_core_dump
if [[ $? -ne 0 ]]
then
    print_logs
    exit 1
fi