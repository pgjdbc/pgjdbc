#!/bin/bash
set -e

whoami

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE USER test;
    CREATE DATABASE test OWNER test;
    CREATE USER root;
EOSQL
