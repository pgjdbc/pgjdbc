#!/bin/sh

psql -c "CREATE USER test WITH PASSWORD 'test'"

for db in test hostssldb hostnossldb certdb hostsslcertdb
do
  createdb -O test $db
  psql $db -c "CREATE EXTENSION sslinfo"
done
