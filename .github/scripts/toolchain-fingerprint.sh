#!/bin/sh
# CI-06 toolchain fingerprint: prints the Java version and the exact Debian
# package versions behind jpegtran and jbig2, so the dev container image
# and the runtime image can be compared line by line (DC-01, CT-01).
#
# Deliberately narrow: libc and friends are left out. They come from the
# two base images, which are pinned separately; failing on a libc patch
# level nobody's output depends on would teach people to ignore the check.
# Only the libraries the tools link whose names match jpeg or lept are
# included, for the same reason.
#
# POSIX sh, no new dependency in either image: java, dpkg-query, dpkg -S
# and ldd all ship with the Temurin Debian bases. Output is sorted, so a
# plain diff decides.

set -eu

# Java specification version (CT-01 asks for the same major, not the patch).
java -XshowSettings:properties -version 2>&1 \
    | grep 'java.specification.version' \
    | tr -d ' '

packages=""
for tool in jpegtran jbig2; do
    path="$(command -v "$tool")" || {
        echo "missing tool in image: $tool" >&2
        exit 1
    }
    # dpkg -S answers "package: path" (several packages comma-separated
    # at most); the first one owns the file.
    packages="$packages $(dpkg -S "$path" | cut -d: -f1 | cut -d, -f1)"
    # Linked libraries as absolute paths; vdso and the loader have none
    # and drop out here. readlink first: ldd prints /lib/..., dpkg knows
    # /usr/lib/... (/lib is a symlink), and dpkg -S does not resolve that.
    for lib in $(ldd "$path" | awk '/=> \// { print $3 }'); do
        lib="$(readlink -f "$lib")"
        case "$lib" in
            *jpeg* | *lept*)
                packages="$packages $(dpkg -S "$lib" | cut -d: -f1 | cut -d, -f1)"
                ;;
        esac
    done
done

# shellcheck disable=SC2086
dpkg-query -W -f='${Package}=${Version}\n' $packages | LC_ALL=C sort -u
