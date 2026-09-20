# Development environment for UnboundAir.
#
# Deliberately mirrors the system dependencies of the runtime image (CT-01):
# same JDK major version, same jpegtran. If the two drift apart, tests pass
# here and the service breaks in production.
#
# No architecture is hardcoded. The base image is a multi-arch manifest, so
# this builds on arm64 and amd64 alike; the runtime image will do the same.

# Ubuntu Noble variant, because it ships a package manager - jpegtran has to
# come from somewhere. Pinned by digest so the environment is reproducible;
# the tag alone would silently move.
FROM eclipse-temurin:26-jdk-noble@sha256:abe71c9b7140affd9a80d6ed313c428e3393d4d18c235823b1745670d4702053

# jpegtran (libjpeg-turbo-progs) does the lossless cropping and grayscale
# conversion. git is needed to work in the repository, curl to fetch the
# Gradle distribution on first wrapper run, sudo to fix up the ownership of
# the mounted Gradle cache volume (see devcontainer.json).
RUN apt-get update \
    && apt-get install --no-install-recommends --yes \
        libjpeg-turbo-progs \
        git \
        curl \
        sudo \
    && rm -rf /var/lib/apt/lists/*

# Ubuntu Noble ships a 'ubuntu' user with UID 1000, but do not rely on the
# Temurin image keeping it: create it only if it is missing. Working as
# UID 1000 means files written in the container belong to the host user,
# who usually has the same UID.
RUN if ! id -u ubuntu >/dev/null 2>&1; then \
        useradd --uid 1000 --create-home --shell /bin/bash ubuntu; \
    fi \
    && echo 'ubuntu ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/ubuntu \
    && chmod 0440 /etc/sudoers.d/ubuntu \
    && install -d -o ubuntu -g ubuntu /home/ubuntu/.gradle

USER ubuntu

WORKDIR /workspaces/UnboundAir

# Gradle caches downloads here. Declaring it keeps a rebuild from
# re-fetching the whole dependency set.
ENV GRADLE_USER_HOME=/home/ubuntu/.gradle
