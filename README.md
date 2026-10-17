# koggopus

koggopus encodes and decodes Ogg Opus audio streams in Kotlin on Android.

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

Add the dependency to the build file of your module:

```kotlin
dependencies {
    implementation("io.github.adrcotfas:koggopus:0.4.1")
}
```

- Minimum version: Android 5.0 (API 21).
- The AAR contains JNI libraries for `arm64-v8a`, `armeabi-v7a`, `x86_64` and `x86`.
  They are aligned for 16 KB page sizes. Size: 0.3 MB to 0.5 MB for each ABI.
- You do not have to install other software. The AAR contains the native code.

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

## Build from source

### Requirements

- The Android SDK with NDK 30.0.16248370 and CMake 4.1.2. To install them, run
  `sdkmanager "ndk;30.0.16248370" "cmake;4.1.2"`.
- A C compiler for the build computer. The unit tests run on its JVM, so Gradle also
  builds the native library for it. Gradle uses the CMake from the Android SDK, or else the
  CMake on the `PATH`.
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
   | `./gradlew :koggopus:testDebugUnitTest` | Runs the tests on the JVM of the build computer. |
   | `./gradlew :koggopus:connectedDebugAndroidTest` | Runs the tests on an Android device or emulator. |
   | `./gradlew :koggopus:assembleRelease` | Makes the AAR. |
   | `./gradlew :consumer-check:connectedMinifiedAndroidTest` | Tests the library in an app that R8 shrinks. |

### Project structure

| Location | Contents |
|---|---|
| `koggopus/src/main` | The public Kotlin API, and its `external` functions. |
| `native/CMakeLists.txt` | The native build. Gradle runs it one time for each Android ABI, and one time for the build computer for the unit tests. |
| `native/core/` | Plain C code over libopusenc, libogg and libopus. |
| `native/jni/` | The JNI binding. |
| `native/test/hostile.c` | A test that sends corrupt streams to the decoder. |

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

A weekly GitHub Actions job opens an issue when a library has a newer release tag.

## Roadmap

- Float PCM.
- Packet loss concealment: audio in place of missing packets.

## License

MIT. See [LICENSE](LICENSE). The bundled libraries use BSD licenses. See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
