#!/usr/bin/env bash

chown postgres:postgres /home/certdir/*.key
chmod 0600 /home/certdir/*.key

PG_HBA=/home/pg_hba.conf

cp /home/certdir/pg_hba.conf $PG_HBA
sed -i 's/127.0.0.1\/32/0.0.0.0\/0/g' $PG_HBA

set_conf_property() {
    local key=$1
    local value=$2
    local file=$3
    echo Configuring $key=$value in $file
    sed -i -e "s/^#\?$key\b.*/$key = $value/" $file
}

enable_ssl_property() {
    local property=$1
    local file=$2
    echo Enabling $property in $file
    sed  -i -e "s/^#$property\b/$property/" $file
}

disable_ssl_property() {
    local property=$1
    local file=$2
    echo Disabling $property in $file
    sed  -i -e "s/^$property\b/#$property/" $file
}

OPTS="-c hba_file=$PG_HBA"

if [[ $SSL == *"yes"* ]];
then
    enable_ssl_property hostssl $PG_HBA
    OPTS="$OPTS -c ssl=on"
    OPTS="$OPTS -c ssl_cert_file=/home/certdir/server.crt"
    OPTS="$OPTS -c ssl_key_file=/home/certdir/server.key"
    OPTS="$OPTS -c ssl_ca_file=/home/certdir/root.crt"
else
    disable_ssl_property hostssl $PG_HBA
fi

if [[ $XA == *"yes"* ]];
then
    OPTS="$OPTS -c max_prepared_transactions=64"
fi

if [[ x$OPTS == *"x"* ]];
then
  echo Extra PostgreSQL options: $OPTS
fi

docker-entrypoint.sh "$@" $OPTS
