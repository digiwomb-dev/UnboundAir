# Runtime image for UnboundAir (CT-01).
#
# Deliberately mirrors the system dependencies of the dev container
# (.devcontainer/Dockerfile): same JDK major version (26), same jpegtran,
# same jbig2. If the two drift apart, tests pass in the dev container and
# the service breaks in production.
#
# No architecture is hardcoded. The base image is a multi-manifest listing,
# so the builder picks the matching variant wherever this is built. Never
# add a platform-specific suffix or a platform-specific download URL here.
#
# Ubuntu Noble variant, because it ships a package manager - jpegtran and
# jbig2 have to come from somewhere (docs/plan.md, "Artefakte"). jbig2
# lives in Ubuntu universe (package `jbig2`, source package `jbig2enc`), so
# the base image must have universe enabled. Pinned by digest so the runtime
# image is reproducible; the tag alone would silently move.
FROM eclipse-temurin:26-jre-noble@sha256:44ea8920aed64bdc43b5a6eaab0a6b40503f17636b9e661d0b6c6e3fa1442f2c

# jpegtran (libjpeg-turbo-progs) does the lossless cropping and grayscale
# conversion. jbig2 is the second system dependency beside jpegtran - it
# encodes 1-bit pages to JBIG2 for the PDF path (SV-08). The package is
# named `jbig2`, not `jbig2enc` - `jbig2enc` is the source package, and
# installing by the wrong name fails the build.
RUN apt-get -o APT::Sandbox::User=root update \
    && apt-get -o APT::Sandbox::User=root install --no-install-recommends --yes \
        libjpeg-turbo-progs \
        jbig2 \
    && rm -rf /var/lib/apt/lists/*

# Do not run as root: the outbox is a mounted volume, and a root-owned
# outbox is an operational annoyance that is free to avoid now and expensive
# to change later. The base image already ships an `ubuntu` user at UID
# 1000, so no UID is forced here: useradd takes the next free one for the
# dedicated service user instead of colliding with it.
RUN if ! id -u unboundair >/dev/null 2>&1; then \
        useradd --create-home --shell /bin/bash unboundair; \
    fi

# The jar is built by Gradle outside (bootJar names it unboundair.jar) and
# copied in. No multi-stage Gradle build here: a build stage would download
# the whole dependency set on every image build.
COPY --chown=unboundair:unboundair build/libs/unboundair.jar /app/unboundair.jar

USER unboundair

WORKDIR /app

# Plain `java -jar` with no baked-in subcommand, so `status`, `scan`,
# `measure` and `run` all stay reachable. No HEALTHCHECK and nothing
# runtime-specific: DO-03 keeps the image runtime-neutral.
ENTRYPOINT ["java", "-jar", "/app/unboundair.jar"]
