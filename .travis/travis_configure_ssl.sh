#!/usr/bin/env bash

set -x -e
set_conf_property() {
    local key=${1}
    local value=${2}

    sudo sed -i -e "s/^#\?${key}.*/${key} = '\/etc\/postgresql\/${PG_VERSION}\/main\/${value}'/" /etc/postgresql/${PG_VERSION}/main/postgresql.conf
}

enable_ssl_property() {
    local property=${1}
    sed  -i -e "s/^#${property}\(.*\)/${property}\1/" ssltest.properties
}

if [ -z "$PG_VERSION" ]
then
    echo "env PG_VERSION is not defined";

else
set_conf_property "ssl_cert_file" "server.crt"
set_conf_property "ssl_key_file" "server.key"
set_conf_property "ssl_ca_file" "root.crt"

enable_ssl_property "sslhostnossl9"
enable_ssl_property "sslhostgh9"
enable_ssl_property "sslhostbh9"
enable_ssl_property "sslhostsslgh9"
enable_ssl_property "sslhostsslbh9"
enable_ssl_property "sslhostsslcertgh9"
enable_ssl_property "sslhostsslcertbh9"
enable_ssl_property "sslcertgh9"
enable_ssl_property "sslcertbh9"

PG_DATA_DIR="/etc/postgresql/${PG_VERSION}/main/"
sudo cp certdir/server/pg_hba.conf "/etc/postgresql/${PG_VERSION}/main/pg_hba.conf"
sudo cp certdir/server/root.crt "${PG_DATA_DIR}"
sudo chmod 0600 "${PG_DATA_DIR}/root.crt"
sudo chown postgres:postgres "${PG_DATA_DIR}/root.crt"
sudo cp certdir/server/server.crt "${PG_DATA_DIR}"
sudo chmod 0600 "${PG_DATA_DIR}/server.crt"
sudo chown postgres:postgres "${PG_DATA_DIR}/server.crt"
sudo cp certdir/server/server.key "${PG_DATA_DIR}"
sudo chmod 0600 "${PG_DATA_DIR}/server.key"
sudo chown postgres:postgres "${PG_DATA_DIR}/server.key"

sudo cat "/etc/postgresql/${PG_VERSION}/main/pg_hba.conf" 2>&1

fi
