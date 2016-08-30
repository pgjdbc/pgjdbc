#!/usr/bin/env bash
set -x -e

sudo service postgresql stop
sudo apt-get remove postgresql libpq-dev libpq5 postgresql-client-common postgresql-common -qq --purge
sudo apt-get -y install libxml2

git clone --depth=1 https://github.com/postgres/postgres.git
cd postgres

# Build PostgreSQL from source
sudo ./configure --with-libxml && sudo make && sudo make install
sudo ln -s /usr/local/pgsql/bin/psql /usr/bin/psql
# Build contrib from source
cd contrib
sudo make all && sudo make install

#Post compile actions
LD_LIBRARY_PATH=/usr/local/pgsql/lib
export LD_LIBRARY_PATH
sudo /sbin/ldconfig /usr/local/pgsql/lib

sudo mkdir -p ${PG_DATADIR}
sudo chmod 777 ${PG_DATADIR}
sudo chown -R postgres:postgres ${PG_DATADIR}

sudo su postgres -c "/usr/local/pgsql/bin/pg_ctl -D ${PG_DATADIR} -U postgres initdb"