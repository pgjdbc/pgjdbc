#!/bin/bash
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    SET password_encryption='scram-sha-256';
    CREATE USER test with password 'test';
    CREATE DATABASE test OWNER test;
EOSQL

/home/scripts/travis_ssl_users.sh
