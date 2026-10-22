# syntax=docker/dockerfile:1@sha256:ecfaec9ed6d810b56388c508f4121597bfbba70d41a6dfeee4d8cad5f295fc32
# MIT Kerberos KDC for the GSSAPI tests (pgjdbc-gss-test). The realm, the
# principals, and the server keytab are created at build time; the container
# copies the keytab to /keytab, where the PostgreSQL container reads it.

FROM debian:trixie-slim@sha256:d7e12182ce18b85b93007c1dedf31f2d29e01ccf3182cc4017c709b6259bc132

# The build keeps the output of apt-get and the Kerberos tools to itself
# unless a command fails, so the CI log of a passing run stays short.
RUN { apt-get update \
        && apt-get install -y --no-install-recommends krb5-kdc krb5-admin-server \
        && rm -rf /var/lib/apt/lists/*; \
    } >/tmp/build.log 2>&1 || { cat /tmp/build.log; exit 1; }

COPY krb5.conf /etc/krb5.conf
COPY kdc.conf /etc/krb5kdc/kdc.conf

# gss_test1 logs in with a password. The driver builds the service principal
# from the host name in the JDBC URL, so the tests connect to localhost.
RUN { kdb5_util create -s -P masterkey \
        && kadmin.local -q "addprinc -pw secret1 gss_test1" \
        && kadmin.local -q "addprinc -randkey postgres/localhost" \
        && kadmin.local -q "ktadd -k /etc/krb5kdc/postgres.keytab postgres/localhost"; \
    } >/tmp/build.log 2>&1 || { cat /tmp/build.log; exit 1; }

COPY start.sh /usr/local/bin/

EXPOSE 88
CMD ["start.sh"]
