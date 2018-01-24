#!/usr/bin/env bash

set -x -e
set_conf_property() {
    local key=${1}
    local value=${2}

    sudo sed -i -e "s/^#\?${key}.*/${key} = ${value}/g" /etc/postgresql/${PG_VERSION}/main/postgresql.conf
}

enable_ssl_property() {
    local property=${1}
    sed  -i -e "s/^#${property}\(.*\)/${property}\1 /" ssltest.properties
}

create_databases() {
    for db in hostssldb hostnossldb certdb hostsslcertdb; do
        createdb $db
        psql $db -c "create extension sslinfo"
    done

}
if [ -z "$PG_VERSION" ]
then
    echo "env PG_VERSION is not defined";

else
set_conf_property "ssl" "on"
set_conf_property "ssl_cert_file" "server.crt"
set_conf_property "ssl_key_file" "server.key"
set_conf_property "ssl_ca_file" "root.crt"

enable_ssl_property "sslhostgh9"

enable_ssl_property "sslhostbh9"

enable_ssl_property "sslhostsslgh9"
enable_ssl_property "sslhostsslbh9"

enable_ssl_property "sslhostsslcertgh9"
enable_ssl_property "sslhostsslcertbh9"

enable_ssl_property "sslcertgh9"
enable_ssl_property "sslcertbh9"

sudo cp certdir/server/pg_hba.conf "/etc/postgresql/${PG_VERSION}/main/pg_hba.conf"
sudo cp certdir/server/root.crt "${PG_DATADIR}"
sudo chmod 0600 "${PG_DATADIR}/root.crt"
sudo cp certdir/server/server.crt "${PG_DATADIR}"
sudo chmod 0600 "${PG_DATADIR}/server.crt"
sudo cp certdir/server/server.key "${PG_DATADIR}"
sudo chmod 0600 "${PG_DATADIR}/server.key"

create_databases
fi
