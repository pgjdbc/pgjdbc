#!/usr/bin/env bash
set -x -e

sudo service postgresql stop
sudo apt-get update -qq
sudo apt-get remove postgresql libpq-dev libpq5 postgresql-client-common postgresql-common -qq --purge
sudo apt-get -y install libxml2 gdb

if [[ -z ${POSTGRES_SOURCE_SHA} ]]
then
    git clone --depth=1 https://github.com/postgres/postgres.git
    cd postgres
else
    git clone https://github.com/postgres/postgres.git
    cd postgres
    git checkout ${POSTGRES_SOURCE_SHA}
fi

# Build PostgreSQL from source
if [[ "${COMPILE_PG_WITH_DEBUG_FLAG}" == "Y" ]]
then
    sudo ./configure --enable-debug --with-libxml CFLAGS="-ggdb"
else
    sudo ./configure --with-libxml CFLAGS="-ggdb"
fi

sudo make && sudo make install
sudo ln -sf /usr/local/pgsql/bin/psql /usr/bin/psql

# Build contrib from source
cd contrib
sudo make all && sudo make install

#Post compile actions
LD_LIBRARY_PATH=/usr/local/pgsql/lib
export LD_LIBRARY_PATH
sudo /sbin/ldconfig /usr/local/pgsql/lib

sudo rm -rf ${PG_DATADIR}
sudo mkdir -p ${PG_DATADIR}
sudo chmod 777 ${PG_DATADIR}
sudo chown -R postgres:postgres ${PG_DATADIR}

sudo su postgres -c "/usr/local/pgsql/bin/pg_ctl -D ${PG_DATADIR} -U postgres initdb"
