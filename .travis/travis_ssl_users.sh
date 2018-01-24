#!/usr/bin/env bash

create_databases() {
    for db in hostssldb hostnossldb certdb hostsslcertdb; do
        createdb -U postgres $db
        psql -U postgres $db -c "create extension sslinfo"
    done
}

create_databases
