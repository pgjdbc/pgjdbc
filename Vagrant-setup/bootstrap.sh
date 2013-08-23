#!/bin/sh

export DEBIAN_FRONTEND=noninteractive
export PG_VERSION=9.1

LAST_APT_UPDATE=/etc/vagrant_last_apt_update
LAST_UPDATE_SECONDS=999999999
# 1 week:
APT_UPDATE_SECONDS=$((7 * 24 * 60 * 60))
if [ -f "$LAST_APT_UPDATE" ]
then
  LAST_UPDATE_SECONDS=$(($(date +%s) - $(stat -c %Y "$LAST_APT_UPDATE")))
  echo "Last apt-get update was $(($LAST_UPDATE_SECONDS / 24 / 60 / 60)) days ago"
else
  echo "No apt-get last update file found (will update)"
fi

if [ "$LAST_UPDATE_SECONDS" -gt "$APT_UPDATE_SECONDS" ]
then
  echo "Updating all packages";
  # Update to latest and greatest:
  apt-get update
  apt-get upgrade -y
  touch "$LAST_APT_UPDATE"
else
  echo "Skipping apt-get update/upgrade";
fi

IS_APP_PROVISIONED="/etc/vagrant_provisioned_at"
if [ ! -f "$IS_APP_PROVISIONED" ]
then
  # Install PostgreSQL:
  apt-get -y install postgresql postgresql-contrib

  # Install Patch (used to patch postgresql.conf):
  apt-get -y install patch

  # PostgreSQL schema setup:
  IS_PG_SETUP="/etc/vagrant_pg_setup"
  if [ ! -f "$IS_PG_SETUP" ]
  then
    echo "Changing listen address to all addresses"
    cd /
    patch -p0 < /mnt/bootstrap/postgresql.conf.patch

    echo "Adding to pg_hba.conf:"
    cat /mnt/bootstrap/pg_hba.conf >> /etc/postgresql/${PG_VERSION}/main/pg_hba.conf

    echo "Switching to known SSL certificate for server"
    for ssl_file in server.key server.crt
    do
      cp /mnt/bootstrap/certs/${ssl_file} /var/lib/postgresql/${PG_VERSION}/main/
      chown postgres:postgres /var/lib/postgresql/${PG_VERSION}/main/${ssl_file}
      chmod 600 /var/lib/postgresql/${PG_VERSION}/main/${ssl_file}
    done

    echo "Copying client root certificate"
    cp /mnt/bootstrap/certs/root.crt /var/lib/postgresql/9.1/main/ 

    service postgresql restart

    echo "Setting up PostgreSQL schema"
    su - postgres -c /mnt/bootstrap/database-setup.sh
  else
    echo "PostgreSQL schema is already setup (skipping schema setup)"
  fi

  date > "$IS_APP_PROVISIONED"
else
  echo "Skipping PostgreSQL install (already done)"
fi
