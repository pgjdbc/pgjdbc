FROM index.docker.io/fedora:23
MAINTAINER pgjdbc team

ENV HOME=/rpm
ENV container="docker"

RUN dnf -y --setopt=tsflags=nodocs install \
        copr-cli \
        git \
        perl \
        perl-YAML \
        rpm-build \
        rpmdevtools \
    && dnf -y --setopt=tsflags=nodocs clean all --enablerepo='*' \
    && dnf -y update copr-cli python-copr --enablerepo updates-testing

ADD build_local copr-ci-git srpmgen /usr/bin/

WORKDIR /rpm

CMD ["bash"]
