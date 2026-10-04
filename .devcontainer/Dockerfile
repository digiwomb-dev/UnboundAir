# Development environment for UnboundAir.
#
# Deliberately mirrors the system dependencies of the runtime image (CT-01):
# same JDK major version, same jpegtran, same jbig2. If the two drift
# apart, tests pass here and the service breaks in production.
#
# No architecture is hardcoded. The base image is a multi-arch manifest, so
# this builds on arm64 and amd64 alike; the runtime image will do the same.

# Ubuntu Noble variant, because it ships a package manager - jpegtran has to
# come from somewhere. jbig2 lives in Ubuntu universe (package `jbig2`,
# source package `jbig2enc`), so the base image must have universe enabled -
# this is where it would show if not. Pinned by digest so the environment is
# reproducible; the tag alone would silently move.
FROM eclipse-temurin:26-jdk-noble@sha256:abe71c9b7140affd9a80d6ed313c428e3393d4d18c235823b1745670d4702053

# jpegtran (libjpeg-turbo-progs) does the lossless cropping and grayscale
# conversion. jbig2 is the second system dependency beside jpegtran - it
# encodes 1-bit pages to JBIG2 for the PDF path (SV-08). The package is
# named `jbig2`, not `jbig2enc` - `jbig2enc` is the source package, and
# installing by the wrong name fails the build. It lives in Ubuntu
# universe; Noble carries 0.29, and since the base image is pinned by
# digest, that version is fixed and is the basis for the golden files of
# this milestone. git is needed to work in the repository, curl to fetch
# the Gradle distribution on first wrapper run, sudo to run privileged
# commands inside the container.
#
# APT::Sandbox::User=root disables the unprivileged download sandbox. apt
# normally drops to the "_apt" user for downloads, which needs a second UID.
# When the build itself runs inside a user namespace that only maps a single
# UID, that drop fails with "seteuid 42 failed" and the build dies. Running
# the download as root is the documented workaround and changes nothing on
# hosts where the sandbox would work.
RUN apt-get -o APT::Sandbox::User=root update \
    && apt-get -o APT::Sandbox::User=root install --no-install-recommends --yes \
        libjpeg-turbo-progs \
        jbig2 \
        git \
        curl \
        sudo \
    && rm -rf /var/lib/apt/lists/*

# Ubuntu Noble ships a 'ubuntu' user with UID 1000, but do not rely on the
# Temurin image keeping it: create it only if it is missing. Working as
# UID 1000 means files written in the container belong to the host user,
# who usually has the same UID.
#
# The chown is allowed to fail: when the build runs inside a user namespace
# that maps only a single UID, changing ownership is impossible - but also
# unnecessary, because every file already belongs to that one UID. On hosts
# with a normal ID range it does what it says.
RUN if ! id -u ubuntu >/dev/null 2>&1; then \
        useradd --uid 1000 --create-home --shell /bin/bash ubuntu; \
    fi \
    && echo 'ubuntu ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/ubuntu \
    && chmod 0440 /etc/sudoers.d/ubuntu \
    && mkdir -p /home/ubuntu/.gradle \
    && { chown ubuntu:ubuntu /home/ubuntu/.gradle || true; }

USER ubuntu

# No WORKDIR: the dev containers CLI mounts the workspace at
# /workspaces/<folder name> and sets the working directory from
# devcontainer.json, which derives it from that same folder name. A fixed
# path here would be wrong for every checkout not named "UnboundAir",
# a git worktree in particular.

# Gradle caches downloads here. Declaring it keeps a rebuild from
# re-fetching the whole dependency set.
ENV GRADLE_USER_HOME=/home/ubuntu/.gradle
