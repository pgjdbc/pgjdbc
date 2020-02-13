#!/usr/bin/env bash

whoami
chown postgres:postgres /home/certdir/*.key
chmod 0600 /home/certdir/*.key

cp /home/certdir/pg_hba.conf /home/pg_hba.conf
sed -i 's/127.0.0.1\/32/0.0.0.0\/0/g' /home/pg_hba.conf

docker-entrypoint.sh $@
