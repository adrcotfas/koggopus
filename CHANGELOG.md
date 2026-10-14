# Changelog

## Unreleased

- Desktop JVM: the `koggopus-jvm` artifact bundles the native library for macOS (arm64,
  x64) and Linux (x64, arm64, glibc 2.28 or later). Gradle picks it from the `koggopus`
  coordinate.
- The jvm jar includes the ProGuard keep rules under `META-INF/proguard/`.

## 0.2.0 - 2026-10-14

- Kotlin Multiplatform: the same API now runs on iOS (`iosArm64`, `iosSimulatorArm64`,
  `iosX64`), with the native code linked in statically.
- The decoder can return little-endian bytes: `feedBytes`, and `read` into a `ByteArray`.
- The Android artifact is now `koggopus-android`. Gradle picks it automatically from the
  `koggopus` coordinate, so dependencies do not need changing.

## 0.1.0 - 2026-10-13

- First version: `OggOpusEncoder` and `OggOpusDecoder` for Android, built on
  libopus 1.6.1, libogg 1.3.6 and libopusenc 0.3.
