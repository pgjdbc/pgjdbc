#!/bin/sh
set -e
# The PostgreSQL server runs as another user, so the keytab has to be readable.
install -m 0644 /etc/krb5kdc/postgres.keytab /keytab/postgres.keytab
# -n keeps the KDC in the foreground, so its log goes to the container output.
exec krb5kdc -n
