FROM ubuntu:20.04

RUN export DEBIAN_FRONTEND=noninteractive \
    && apt-get update \
    && apt-get -y install \
    clang \
    bison \
    build-essential \
    dumb-init \
    flex \
    git \
    libperl-dev \
    libreadline-dev \
    libssl-dev \
    libxml2-dev \
    libxml2-utils \
    libxslt-dev \
    llvm \
    locales \
    python3-dev \
    tcl-dev \
    xsltproc \
    zlib1g-dev \
    && localedef -i en_US -c -f UTF-8 -A /usr/share/locale/locale.alias en_US.UTF-8

ARG GIT_URL="https://github.com/postgres/postgres"
ARG GIT_TAG="master"
# If specified, this allows us to help Docker cache the clone step
ARG GIT_SHA

RUN mkdir -p /build/postgres \
    && git clone -b "${GIT_TAG}" --single-branch "${GIT_URL}" --depth 10 /build/postgres \
    && cd /build/postgres \
    && [ -z "${GIT_SHA:-}" ] || git reset --hard "${GIT_SHA:-}"

# Default configure options that can be overridden with build arg
ARG CONFIGURE_OPTS=" \
    --enable-debug \
    --enable-cassert \
    --with-llvm \
    --with-tcl \
    --with-perl \
    --with-python \
    --with-ssl=openssl \
    --with-libxml \
    "
# Additional configure options to append to defaults
ARG CONFIGURE_ADD_OPTS

WORKDIR /build/postgres
RUN ./configure ${CONFIGURE_OPTS} ${CONFIGURE_ADD_OPTS:-}
ARG MAKE_OPTS="-j 8"
ARG MAKE_ADD_OPTS
RUN make ${MAKE_OPTS} ${MAKE_ADD_OPTS}
RUN make install

ARG MAKE_CONTRIB_OPTS
ARG MAKE_ADD_OPTS

WORKDIR /build/postgres/contrib
RUN make ${MAKE_CONTRIB_OPTS:-${MAKE_OPTS}} ${MAKE_CONTRIB_ADD_OPTS}
RUN make install

ENV LANG en_US.utf8
ENV GIT_SHA=$GIT_SHA
ENV GIT_TAG=$GIT_TAG
ENV PATH $PATH:/usr/local/pgsql/bin
ENV PGDATA /var/lib/postgresql/data

# explicitly set user/group IDs
RUN set -eux; \
    groupadd -r postgres --gid=999; \
    useradd -r -g postgres --uid=999 --home-dir=/var/lib/postgresql --shell=/bin/bash postgres; \
    mkdir -p /var/lib/postgresql; \
    chown -R postgres:postgres /var/lib/postgresql; \
    mkdir -p "$PGDATA" ; \
    chown -R postgres:postgres "$PGDATA"

USER postgres
ADD scripts/entrypoint.sh /
ENTRYPOINT ["/usr/bin/dumb-init", "--"]
CMD [ "/entrypoint.sh" ]
