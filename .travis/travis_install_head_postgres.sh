#!/usr/bin/env bash
set -x -e

git clone --depth=1 https://github.com/postgres/postgres.git
cd postgres

PREFIX=$HOME/pg_head

./configure --prefix=$PREFIX

# Build PostgreSQL from source and start the DB
make && make install && $PREFIX/bin/pg_ctl -D $PREFIX/data initdb && $PREFIX/bin/pg_ctl -D $PREFIX/data -l postgres.log start
