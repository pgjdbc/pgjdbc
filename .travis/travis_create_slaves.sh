#!/usr/bin/env bash
set -x -e

if [ -z "$PG_VERSION" ]
then
    echo "env PG_VERSION not define";
elif [ "x${PG_VERSION}" = "xHEAD" ]
then
    PG_CTL="/usr/local/pgsql/bin/pg_ctl"
    PG_BASEBACKUP="/usr/local/pgsql/bin/pg_basebackup"
else 
    PG_CTL="/usr/lib/postgresql/${PG_VERSION}/bin/pg_ctl"
    PG_BASEBACKUP="/usr/lib/postgresql/${PG_VERSION}/bin/pg_basebackup"
fi

#Create Slave 1
sudo rm -rf ${PG_SLAVE1_DATADIR}
sudo mkdir -p ${PG_SLAVE1_DATADIR}
sudo chmod 700 ${PG_SLAVE1_DATADIR}
sudo chown -R postgres:postgres ${PG_SLAVE1_DATADIR}

sudo su postgres -c "$PG_BASEBACKUP -Upostgres -D ${PG_SLAVE1_DATADIR} -X stream"

sudo su postgres -c "echo standby_mode = \'on\' >${PG_SLAVE1_DATADIR}/recovery.conf"
sudo su postgres -c "echo primary_conninfo = \'host=localhost port=5432 user=test password=test\' >>${PG_SLAVE1_DATADIR}/recovery.conf"
sudo su postgres -c "echo recovery_target_timeline = \'latest\' >>${PG_SLAVE1_DATADIR}/recovery.conf"

if [[ "x${PG_VERSION}" != "xHEAD" ]]
then
    sudo su postgres -c "echo 'local all all trust' > ${PG_SLAVE1_DATADIR}/pg_hba.conf"
    sudo su postgres -c "echo 'host all all 127.0.0.1/32 trust' >> ${PG_SLAVE1_DATADIR}/pg_hba.conf"
    sudo su postgres -c "echo 'host all all ::1/128 trust' >> ${PG_SLAVE1_DATADIR}/pg_hba.conf"

    sudo su postgres -c "touch ${PG_SLAVE1_DATADIR}/pg_ident.conf"

    sudo su postgres -c "cp -f ${PG_DATADIR}/postgresql.conf ${PG_SLAVE1_DATADIR}/postgresql.conf"
    sudo sed -i -e "/^[ \t]*data_directory.*/d" ${PG_SLAVE1_DATADIR}/postgresql.conf
    sudo sed -i -e "/^[ \t]*hba_file.*/d" ${PG_SLAVE1_DATADIR}/postgresql.conf
    sudo sed -i -e "/^[ \t]*ident_file.*/d" ${PG_SLAVE1_DATADIR}/postgresql.conf
    sudo sed -i -e "s/^#\?hot_standby.*/hot_standby = on/g" ${PG_SLAVE1_DATADIR}/postgresql.conf
fi

#Start Slave 1
sudo su postgres -c "$PG_CTL -D ${PG_SLAVE1_DATADIR} -w -t 300 -c -o '-p 5433' -l /tmp/postgres_slave1.log start" || (sudo tail /tmp/postgres_slave1.log ; exit 1)
sudo tail /tmp/postgres_slave1.log

#Create Slave 2
sudo rm -rf ${PG_SLAVE2_DATADIR}
sudo mkdir -p ${PG_SLAVE2_DATADIR}
sudo chmod 700 ${PG_SLAVE2_DATADIR}
sudo chown -R postgres:postgres ${PG_SLAVE2_DATADIR}

sudo su postgres -c "$PG_BASEBACKUP -Upostgres -D ${PG_SLAVE2_DATADIR} -X stream"

sudo su postgres -c "echo standby_mode = \'on\' >${PG_SLAVE2_DATADIR}/recovery.conf"
sudo su postgres -c "echo primary_conninfo = \'host=localhost port=5432 user=test password=test\' >>${PG_SLAVE2_DATADIR}/recovery.conf"
sudo su postgres -c "echo recovery_target_timeline = \'latest\' >>${PG_SLAVE2_DATADIR}/recovery.conf"

if [[ "x${PG_VERSION}" != "xHEAD" ]]
then
    sudo su postgres -c "echo 'local all all trust' > ${PG_SLAVE2_DATADIR}/pg_hba.conf"
    sudo su postgres -c "echo 'host all all 127.0.0.1/32 trust' >> ${PG_SLAVE2_DATADIR}/pg_hba.conf"
    sudo su postgres -c "echo 'host all all ::1/128 trust' >> ${PG_SLAVE2_DATADIR}/pg_hba.conf"

    sudo su postgres -c "touch ${PG_SLAVE2_DATADIR}/pg_ident.conf"

    sudo su postgres -c "cp -f ${PG_DATADIR}/postgresql.conf ${PG_SLAVE2_DATADIR}/postgresql.conf"
    sudo sed -i -e "/^[ \t]*data_directory.*/d" ${PG_SLAVE2_DATADIR}/postgresql.conf
    sudo sed -i -e "/^[ \t]*hba_file.*/d" ${PG_SLAVE2_DATADIR}/postgresql.conf
    sudo sed -i -e "/^[ \t]*ident_file.*/d" ${PG_SLAVE2_DATADIR}/postgresql.conf
    sudo sed -i -e "s/^#\?hot_standby.*/hot_standby = on/g" ${PG_SLAVE2_DATADIR}/postgresql.conf
fi

#Start Slave 2
sudo su postgres -c "$PG_CTL -D ${PG_SLAVE2_DATADIR} -w -t 300 -c -o '-p 5434' -l /tmp/postgres_slave2.log start" || (sudo tail /tmp/postgres_slave2.log ; exit 1)
sudo tail /tmp/postgres_slave2.log
