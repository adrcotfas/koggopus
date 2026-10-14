#!/bin/sh
# Builds the desktop JVM library for Linux inside a manylinux_2_28 image, so that it only
# needs glibc 2.28 (2018) and loads on any distribution since. Needs Docker; run it on a
# machine of the target architecture.
#
# Usage: native/build-jvm-linux.sh <x64|arm64> <output directory>
# The output directory is laid out like the jar; pass it to Gradle as
# -Pkoggopus.prebuiltNatives=<output directory>.
set -eu

arch=$1
out=$2
case $arch in
    x64) image=quay.io/pypa/manylinux_2_28_x86_64 ;;
    arm64) image=quay.io/pypa/manylinux_2_28_aarch64 ;;
    *) echo "unknown architecture: $arch" >&2; exit 1 ;;
esac

dir=$out/io/github/adrcotfas/koggopus/native/linux-$arch
mkdir -p "$dir"
out=$(cd "$dir" && pwd)

# Built in the container's own directory, so the checkout gets no root-owned files.
docker run --rm -v "$(pwd):/src:ro" -v "$out:/out" "$image" sh -euc '
    dnf install -y -q java-17-openjdk-devel
    # The checkout belongs to another user; without this, git describe fails and the
    # libopusenc version in every stream would read "unknown".
    git config --global --add safe.directory "*"
    cmake -S /src/native -B /tmp/build -DCMAKE_BUILD_TYPE=Release -DJAVA_HOME=/usr/lib/jvm/java-17-openjdk
    cmake --build /tmp/build --target koggopus --parallel
    cp /tmp/build/libkoggopus.so /out/
    chown "$(stat -c %u:%g /out)" /out/libkoggopus.so
'
