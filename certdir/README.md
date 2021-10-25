To run the SSL tests, the following properties are used:

* certdir: directory where the certificates and keys are store
* enable_ssl_tests: enables SSL tests

In order to configure PostgreSQL for SSL tests, the following changes should be applied:

* Copy server/server.crt, server/server.key, and server/root.crt to $PGDATA directory
* In $PGDATA directory: chmod 0600 server.crt server.key root.crt
* Set ssl=on in postgresql.conf
* Set ssl_cert_file=server.crt in postgresql.conf
* Set ssl_key_file=server.key in postgresql.conf
* Set ssl_ca_file=root.crt in postgresql.conf
* Add databases for SSL tests. Note: sslinfo extension is used in tests to tell if connection is using SSL or not

      for db in hostdb hostssldb hostnossldb certdb hostsslcertdb; do
        createdb $db
        psql $db -c "create extension sslinfo"
      done
* Add test databases to pg_hba.conf. If you do not overwrite the pg_hba.conf then remember to comment out all lines
  starting with "host all".
* Uncomment enable_ssl_tests=true in ssltests.properties
* The username for connecting to postgres as specified in build.local.properties tests has to be "test".

The certificates are generated with Makefile.

* To remove all certificates: `make clean`
* To generate certificates: `make all`
* To update a single certificate: remove the file, and execute `make all`
