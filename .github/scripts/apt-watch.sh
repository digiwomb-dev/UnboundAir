#!/bin/sh
# Monthly apt pin watch (follow-up of Spike F, #249, for #252): fails when
# the Ubuntu archive offers a different version than the Dockerfiles pin.
#
# Usage: apt-watch.sh [Dockerfile ...] (default: Dockerfile
# .devcontainer/Dockerfile). Compares the pinned pkg=version pairs across
# the given files first — both Dockerfiles must pin identically, or the
# images drift by construction — then holds each against the archive
# candidate (noble plus updates and security pockets, amd64; arm64 tracks
# the same source versions, and CI-06's cross-architecture fingerprint
# diff catches an actual divergence).
#
# Needs docker (an ubuntu:noble container answers apt-cache policy) and
# network for the archive. POSIX sh, no new dependency.
set -eu

if [ "$#" -eq 0 ]; then
    set -- Dockerfile .devcontainer/Dockerfile
fi

reference=""
for file in "$@"; do
    # Pinned pairs look like name=version starting with a digit; option
    # assignments (APT::Sandbox::User=root) do not match by construction.
    pins="$(grep -oE '[a-z0-9][a-z0-9+.-]*=[0-9][^ \\"]*' "$file" | LC_ALL=C sort -u)"
    if [ -z "$reference" ]; then
        reference="$pins"
        reference_file="$file"
    elif [ "$pins" != "$reference" ]; then
        echo "pinned versions differ between $reference_file and $file:" >&2
        echo "--- $reference_file" >&2
        printf '%s\n' "$reference" >&2
        echo "--- $file" >&2
        printf '%s\n' "$pins" >&2
        exit 1
    fi
done
echo "both Dockerfiles pin identically:"
printf '%s\n' "$reference"

# shellcheck disable=SC2086
docker run --rm ubuntu:noble sh -c '
    apt-get update -qq 2>/dev/null
    outdated=0
    for spec in "$@"; do
        pkg=${spec%%=*}
        pinned=${spec#*=}
        candidate=$(apt-cache policy "$pkg" | sed -n "s/^ *Candidate: //p")
        if [ "$candidate" != "$pinned" ]; then
            echo "OUTDATED: $pkg pinned $pinned, archive offers ${candidate:-(none)}"
            outdated=1
        fi
    done
    exit $outdated
' sh $reference
