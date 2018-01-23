FROM index.docker.io/fedora:27
MAINTAINER pgjdbc team

ENV HOME=/rpm
ENV container="docker"

RUN dnf -y --setopt=tsflags=nodocs install dnf-plugins-core \
    && dnf -y copr enable praiskup/srpm-tools \
    && dnf -y --setopt=tsflags=nodocs install \
        srpm-tools \
        copr-cli \
    && dnf -y --setopt=tsflags=nodocs clean all --enablerepo='*'

ADD copr-ci-git /usr/bin/
RUN chmod +rx /usr/bin/copr-ci-git

WORKDIR /rpm

CMD ["bash"]
