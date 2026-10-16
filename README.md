<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/header-dark.svg">
  <img alt="koggopus: an Ogg Opus encoder and decoder for live audio in Kotlin Multiplatform, on Android, iOS, macOS, Linux and the desktop JVM" src="docs/header-light.svg">
</picture>

koggopus encodes and decodes Ogg Opus audio streams in Kotlin Multiplatform, on Android,
iOS, macOS, Linux and the desktop JVM (macOS, Linux and Windows).

- The encoder changes PCM audio into Ogg pages.
- The decoder changes Ogg data into PCM audio.

Use koggopus for live audio. For example:

- Send microphone audio through a network connection.
- Play a stream while it arrives.

koggopus contains the reference libraries from [Xiph](https://xiph.org). These libraries
do all the work for the Ogg container format: pages, checksums, granule positions,
pre-skip and end trimming.

## Terms

| Term | Meaning |
|---|---|
| PCM | Uncompressed audio. koggopus uses 16-bit signed integers. |
| Sample | One PCM value for one channel. |
| Frame | One sample for each channel. A stereo frame has two samples. |
| Page | The unit of data in an Ogg stream. |
| Chunk | A part of the input data. A chunk can have any size. |

## Features

**Encoder**

- Input: 16-bit PCM, mono or stereo, at any sample rate.
- Output: complete Ogg pages. The encoder returns each page when it is complete. You can
  send each result as one message.

**Decoder**

- Input: chunks of an Ogg Opus stream.
- Output: 16-bit PCM at the sample rate and channel count that you select.
- The decoder accepts chained streams. It skips corrupt data.

## Installation

Add the dependency to the build file of your module.

Kotlin Multiplatform project: add it to `commonMain`.

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.adrcotfas:koggopus:0.4.0")
        }
    }
}
```

Android-only project: add it to `dependencies`.

```kotlin
dependencies {
    implementation("io.github.adrcotfas:koggopus:0.4.0")
}
```

| Platform | Minimum version | Native code in the package |
|---|---|---|
| Android | API 21 | JNI libraries for `arm64-v8a`, `armeabi-v7a`, `x86_64` and `x86`. They are aligned for 16 KB page sizes. Size: 0.3 MB to 0.6 MB for each ABI. |
| iOS | iOS 14 | Static libraries for `iosArm64`, `iosSimulatorArm64` and `iosX64`. Your framework links them. |
| macOS (Kotlin/Native) | macOS 11 | Static libraries for `macosArm64`. Your binary links them. |
| Linux (Kotlin/Native) | glibc 2.28 | Static libraries for `linuxX64` and `linuxArm64`. Your binary links them. |
| Desktop JVM | Java 11. macOS 11 (Apple silicon or Intel), Linux with glibc 2.28 (x64 or arm64), or Windows 10 (x64). | One JNI library for each OS and architecture, in the jar. Size: approximately 0.5 MB each. |

The API is the same on all platforms. You do not have to install other software. The
published packages contain the native code.

On the desktop JVM, koggopus copies the library from the jar to a temporary file and
loads it from there. Java 24 and later show a warning when the library loads, unless the
application runs with `--enable-native-access=ALL-UNNAMED`.

## Encode audio

```kotlin
val encoder = OggOpusEncoder(
    sampleRate = 16000,
    channels = 1,
    bitrate = 24000,
    application = OpusApplication.VOIP,
    pageDurationMs = 80,
)

send(encoder.headers())            // the two header pages
while (recording) {
    val pcm: ShortArray = readFromMicrophone()
    val pages = encoder.write(pcm) // empty until a page is complete
    if (pages.isNotEmpty()) send(pages)
}
send(encoder.finish())             // the remaining audio and the end-of-stream page
encoder.close()
```

1. Make an `OggOpusEncoder`.
2. Send the two header pages from `headers()`.
3. Give the PCM audio to `write()`. `write()` returns an empty array until a page is
   complete.
4. When the recording stops, call `finish()`. It returns the remaining audio and the
   end-of-stream page.
5. Call `close()`.

> [!NOTE]
> - `write()` also accepts little-endian 16-bit PCM as a `ByteArray`.
> - The input does not have to agree with the Opus frame size. The encoder keeps the
>   input in a buffer and divides it into Opus frames.

### Page duration

`pageDurationMs` controls the latency and the overhead:

- Each page has a header of approximately 27 bytes, plus 1 byte for each packet.
- Short pages decrease the latency, but they increase the overhead.
- At 24 kbit/s, 80 ms pages add approximately 13% overhead. 20 ms pages add
  approximately 47% overhead.

## Decode audio

```kotlin
val decoder = OggOpusDecoder(sampleRate = 24000)

for (chunk in incomingChunks) {              // any size, any alignment
    val pcm: ShortArray = decoder.feed(chunk) // empty until audio is complete
    if (pcm.isNotEmpty()) play(pcm)
}
decoder.close()
```

- `feed()` accepts a chunk of any size and alignment. It returns an empty array until the
  audio is complete.
- When the stream headers arrive, `decoder.head` and `decoder.tags` contain them.

### Output channels

The output channel layout does not change after the decoder knows it.

- To select the layout, set `channels = 1` (mono) or `channels = 2` (stereo).
- If you do not set `channels`, the first stream sets the layout. `decoder.channels`
  shows it.
- If a chained stream has a different channel count, the decoder converts it. Thus you
  can send all the audio to one output, for example an `AudioTrack`.
- Stereo output is interleaved.

### Decode a limited quantity of audio at a time

> [!CAUTION]
> A small quantity of valid Opus data can decode to a large quantity of silence. 1 KB of
> input can make up to approximately 3 MB of mono PCM, or 6 MB of stereo PCM, at 48 kHz.
> `feed()` returns all the audio that a chunk completes.

Use `push()` and `read()` instead of `feed()` when:

- The stream comes from a source that you do not trust.
- You decode directly into a playback buffer.

`push()` adds the input to a queue. `read()` writes the audio into your buffer. `read()`
never writes more audio than your buffer can hold.

```kotlin
val buffer = ShortArray(4096)
for (chunk in incomingChunks) {
    decoder.push(chunk)
    while (true) {
        val count = decoder.read(buffer)   // samples written; 0 when more input is necessary
        if (count == 0) break
        play(buffer, count)
    }
}
```

- If one `feed()` call must return more than 2^30 samples, it throws `OggOpusException`.
  It also discards that audio.
- `read()` does not have this limit.

### Get the audio as bytes

To get the audio as little-endian bytes, for example for a socket or a file:

- Use `feedBytes()`, or
- Give `read()` a `ByteArray`.

## Threads

koggopus serializes the calls on one encoder or decoder. Thus you can call `close()` from
a different thread, for example to stop a stream.

## Errors

| Cause | Exception |
|---|---|
| An argument is not valid. | `IllegalArgumentException` |
| A call after `close()`. | `IllegalStateException` |
| A failure in the native code. | `OggOpusException` |

After an `OggOpusException`, you can continue to use the decoder. This table shows what
the decoder does with bad input:

| Input | What the decoder does |
|---|---|
| A stream that it cannot decode, for example surround | It reports the stream one time and skips it. `read()` first returns the audio before that stream. The next `read()` call throws the exception. |
| Corrupt pages and packets | It skips them. It does not show an error. |
| A comment header larger than 16 MB | It skips the header. `decoder.tags` is then null. |

## Limits

- Mono and stereo only. The decoder rejects surround streams.
- 16-bit integer PCM only.
- No seeking. The decoder reads a stream from the start to the end.
- No Kotlin/Native target for Windows (`mingwX64`), no Windows on Arm, and no web targets.

## Build from source

### Requirements

- The Android SDK with NDK 30.0.16248370. Gradle does not download the NDK. To install
  it, run `sdkmanager "ndk;30.0.16248370"`.
- CMake 3.22.1 or later.
- A C compiler for the build computer.
- For the iOS and macOS targets: a Mac with Xcode.
- Optional: `opusenc`, `opusdec` and `opusinfo` from
  [opus-tools](https://opus-codec.org/downloads/). The interoperability tests compare
  the results with these tools. If the tools are not installed, Gradle skips these
  tests.

### Procedure

1. Clone the repository with its submodules and its full history:

   ```sh
   git clone --recurse-submodules https://github.com/adrcotfas/koggopus
   cd koggopus
   ```

   You must have the full history. The bundled libraries get their versions from their
   git tags.

2. Run the command that you need:

   | Command | Result |
   |---|---|
   | `./gradlew :koggopus:jvmTest` | Runs the tests on the JVM of the build computer. |
   | `./gradlew :koggopus:iosSimulatorArm64Test` | Runs the tests on the iOS simulator (macOS only). |
   | `./gradlew :koggopus:macosArm64Test` | Runs the tests as a native macOS binary (macOS only). |
   | `./gradlew :koggopus:connectedAndroidDeviceTest` | Runs the tests on an Android device or emulator. |
   | `./gradlew :koggopus:assemble` | Makes the Android AAR and the jvm jar. On macOS, it also makes the Apple libraries. |
   | `./gradlew :consumer-check:connectedMinifiedAndroidTest` | Tests the library in an app that R8 shrinks. |

### Project structure

| Location | Contents |
|---|---|
| `commonMain` | The public Kotlin API. Each platform has a small binding below it: JNI on Android and the desktop JVM (`jniMain`), cinterop on iOS, macOS and Linux (`nativeMain`). |
| `native/CMakeLists.txt` | The native build. Gradle runs it one time for each Android ABI, each Kotlin/Native target, and each desktop platform that the build computer can make: both macOS architectures on a Mac, its own architecture on Linux. |
| `native/build-linux.sh` | Builds the Linux libraries in Docker on glibc 2.28, for one architecture. |
| `native/build-windows.sh` | Builds the Windows x64 JNI library with MSVC. |
| `native/core/` | Plain C code over libopusenc, libogg and libopus. |
| `native/jni/` | The JNI binding for Android and the desktop JVM. |
| `native/test/hostile.c` | A test that sends corrupt streams to the decoder. |

### Linux and Windows libraries for a release

A local jvm jar contains only the libraries that the build computer can make. On a Mac,
Gradle also leaves out the Linux Kotlin/Native targets and shows a warning. A release
needs all the libraries. Run `native/build-linux.sh` on an x64 and on an arm64 Linux
computer, and `native/build-windows.sh` in Git Bash on Windows. Put their output in one
directory and give it to Gradle with `-Pkoggopus.prebuiltNatives=<dir>`. On Windows, the
JVM tests also need this property. The upload to Maven Central fails if a library or a
target is missing.

### Test with corrupt input

`hostile.c` decodes each input in three ways: in one piece, in chunks, and with
`push()` and `read()`. The three results must be the same. Run the test with
AddressSanitizer:

```sh
cmake -S native -B build/sanitize -DKOGG_BUILD_JNI=OFF -DKOGG_BUILD_TESTS=ON \
  -DCMAKE_C_FLAGS="-fsanitize=address,undefined -O1"
cmake --build build/sanitize --target kogg_hostile && build/sanitize/kogg_hostile
```

### Fuzz test

The same test is also a [libFuzzer](https://llvm.org/docs/LibFuzzer.html) target. CI
runs it for one minute on each push.

To run it for a longer time, you must have a clang that contains libFuzzer. The clang
from Apple does not contain it. On macOS:

1. Install `llvm` from Homebrew.
2. In the commands below, replace `-DCMAKE_C_COMPILER=clang` with
   `-DCMAKE_C_COMPILER=$(brew --prefix llvm)/bin/clang`.

```sh
cmake -S native -B build/fuzz -DKOGG_BUILD_JNI=OFF -DKOGG_BUILD_TESTS=ON -DKOGG_BUILD_FUZZER=ON \
  -DCMAKE_C_COMPILER=clang -DCMAKE_C_FLAGS="-fsanitize=fuzzer-no-link,address,undefined -O1"
cmake --build build/fuzz --target kogg_hostile kogg_fuzz
mkdir -p build/fuzz/corpus && build/fuzz/kogg_hostile --seeds build/fuzz/corpus
build/fuzz/kogg_fuzz -dict=native/test/oggopus.dict -max_len=65536 build/fuzz/corpus
```

## Bundled libraries

| Library | Version |
|---|---|
| [libopus](https://github.com/xiph/opus) | 1.6.1 |
| [libogg](https://github.com/xiph/ogg) | 1.3.6 |
| [libopusenc](https://github.com/xiph/libopusenc) | 0.3 |

The libraries are git submodules in `native/third_party/`. To update a library:

1. Check out a newer release tag of the library.
2. Run the tests.

## Roadmap

- A scheduled job that reports new releases of the three bundled libraries.
- Float PCM.
- Packet loss concealment: audio in place of missing packets.

## License

MIT. See [LICENSE](LICENSE). The bundled libraries use BSD licenses. See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
