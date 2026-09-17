Docker Compose script helps to start a PostgreSQL instance for tests

Typical usage
=============

    docker compose up # starts the database

    ...
    Ctrl+C

    docker compose up -d # launch the container in background

    docker compose rm # removes the container (e.g. to recreate the db)

Environment variables
=====================

* `PGV=9.5|9.6|10|11|12|latest` (default: `latest`). Configures PostgreSQL Docker image.
   See the [list of available tags](https://github.com/docker-library/docs/blob/master/postgres/README.md#supported-tags-and-respective-dockerfile-links) at the official page.
* `SCRAM=yes|no` (default: `yes`). Configures `password_encryption=scram-sha-256`
* `SSL=yes|no` (default: `yes`). Configures SSL
* `OAUTH=yes|no` (default: `no`). Configures the server to support OAuth authentication

Example usages
==============

    docker compose down && PGV=10 SSL=no XA=yes SCRAM=yes docker compose up

    docker compose down && PGV=latest docker compose up

Testing against PostgreSQL HEAD
===============================

To run the driver tests against an unreleased PostgreSQL, build the devel
image (from `apt.postgresql.org`'s `pgdg-snapshot` suite) inline via the
shared compose:

    PG_IMAGE=pgjdbc/postgres-devel:local docker compose up --build

Or, equivalently, without the `--build` flag:

    PG_IMAGE=pgjdbc/postgres-devel:local PG_PULL_POLICY=build docker compose up

The build picks the highest `postgresql-N` available in `pgdg-snapshot`. Pass
`PG_MAJOR=18` to pin a specific major. See [docker/postgres-head/README.md](../postgres-head/README.md)
for the Dockerfile.

Alternative Foreground Helper
=============================
Run a database server configured for testing the driver in the foreground via:

    $ docker/bin/postgres-server

The server will run in an ephemeral container so killing it via Ctrl-C and restarting
it will always give you a consistently empty test environment.
