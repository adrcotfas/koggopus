#!/bin/sh
# Builds the Windows x64 desktop JVM library with MSVC. Run it in Git Bash on Windows with
# Visual Studio installed and JAVA_HOME set.
#
# Usage: native/build-windows.sh <output directory>
# Pass the output directory to Gradle as -Pkoggopus.prebuiltNatives=<output directory>.
# Its jvm/ directory is laid out like the jvm jar.
set -eu

out=$1
jvm=$out/jvm/io/github/adrcotfas/koggopus/native/windows-x64
mkdir -p "$jvm"

cmake -S native -B build/windows -A x64 -DJAVA_HOME="$JAVA_HOME"
cmake --build build/windows --config Release --target koggopus --parallel
cp build/windows/Release/koggopus.dll "$jvm/"
