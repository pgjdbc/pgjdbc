PostgreSQL development image built from apt.postgresql.org's `*-pgdg-snapshot`
suite. Drop-in compatible with the official `postgres:*` image.

The build picks the highest `postgresql-N` available in pgdg-snapshot at build
time; pass `--build-arg PG_MAJOR=18` to pin a specific major. The configure
flags match the released pgdg builds (`--with-gssapi`, `--with-llvm`,
`--with-icu`, `--with-lz4`, `--with-zstd`, `--with-perl`, `--with-python`,
`--with-tcl`).

See [docker/postgres-server/README.md](../postgres-server/README.md) for how
to run this image through the shared compose.
