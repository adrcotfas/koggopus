#!/bin/sh
# Builds the Linux native libraries inside a manylinux_2_28 image, so that they only need
# glibc 2.28 (2018) and work on any distribution since: the desktop JVM library, and the
# static libraries of the Kotlin/Native Linux target. Needs Docker; run it on a machine of
# the target architecture.
#
# Usage: native/build-linux.sh <x64|arm64> <output directory>
# Pass the output directory to Gradle as -Pkoggopus.prebuiltNatives=<output directory>.
# Its jvm/ directory is laid out like the jvm jar; static/<target>/ holds the static
# libraries for the Kotlin/Native target.
set -eu

arch=$1
out=$2
case $arch in
    x64) image=quay.io/pypa/manylinux_2_28_x86_64 target=linuxX64 ;;
    arm64) image=quay.io/pypa/manylinux_2_28_aarch64 target=linuxArm64 ;;
    *) echo "unknown architecture: $arch" >&2; exit 1 ;;
esac

jvm=$out/jvm/io/github/adrcotfas/koggopus/native/linux-$arch
static=$out/static/$target
mkdir -p "$jvm" "$static"

# Built in the container's own directory, so the checkout gets no root-owned files.
docker run --rm -v "$(pwd):/src:ro" -v "$(cd "$jvm" && pwd):/jvm" -v "$(cd "$static" && pwd):/static" "$image" sh -euc '
    dnf install -y -q java-17-openjdk-devel
    # The checkout belongs to another user; without this, git describe fails and the
    # libopusenc version in every stream would read "unknown".
    git config --global --add safe.directory "*"
    cmake -S /src/native -B /tmp/build -DCMAKE_BUILD_TYPE=Release -DJAVA_HOME=/usr/lib/jvm/java-17-openjdk
    cmake --build /tmp/build --target koggopus koggcore --parallel
    cd /tmp/build
    cp libkoggopus.so /jvm/
    cp libkoggcore.a libopusenc.a opus/libopus.a ogg/libogg.a /static/
    chown -R "$(stat -c %u:%g /jvm)" /jvm /static
'
