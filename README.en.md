# VoiceChanger

[中文](README.md) | **English** | [日本語](README.ja.md) | [한국어](README.ko.md)

An Android voice-changing library built on [SoundTouch](https://codeberg.org/soundtouch/soundtouch): record → transform (male↔female, loli, uncle, Tom-cat…) → save/play, with **real-time voice changing** (hear yourself transformed as you speak). Everything works out of the box.

> **Complete 2.x rewrite**: Kotlin + coroutines API; SoundTouch 2.4.1 sources bundled and compiled from source via CMake (all ABIs including arm64-v8a); JNI bindings included (no more missing `net.surina.soundtouch.SoundTouch`, see [#2](https://github.com/neboyang/VoiceChanger/issues/2)); AAC/M4A and WAV output (replacing the hidden API `AmrInputStream` removed from Android); full demo app (see [#3](https://github.com/neboyang/VoiceChanger/issues/3)) and a [voice-tuning guide](docs/voice-tuning.md) (see [#1](https://github.com/neboyang/VoiceChanger/issues/1)). Migration notes in the [CHANGELOG](CHANGELOG.md).

## Features

- 🎙 **Recording**: `AudioRecord` streamed straight to disk, pause/resume, live amplitude callback, constant memory regardless of length
- 🎭 **Voice effects**: pitch (semitones), tempo (speed without pitch change), rate (speed + pitch) — three independent parameters, 7 built-in presets
- 🎧 **Real-time mode**: microphone → pitch shift → headphones, adjustable while running
- 🌊 **Stream processing**: `processStream` works on any PCM stream (network, pipe, socket)
- 💾 **Output**: WAV (lossless) or AAC/M4A (compressed, public APIs available on all Android versions)
- 🧵 **Modern API**: Kotlin-first coroutines + `StateFlow`; non-suspending low-level classes remain Java-callable
- 📦 **Zero extra setup**: native code builds with the library — no manual `.so` files; armeabi-v7a / arm64-v8a / x86 / x86_64
- 🔒 **Scoped-storage friendly**: writes to app-specific directories, no storage permission needed on Android 10+

## Quick start

### 1. Add the dependency

Via [JitPack](https://jitpack.io):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.neboyang:VoiceChanger:2.1.0")
}
```

Or include the sources: copy the `voicechanger` directory into your project and `include(":voicechanger")` (requires NDK/CMake, downloaded automatically by Android Studio).

### 2. Permissions

The library manifest already declares `RECORD_AUDIO`; you only need to request the **runtime permission** before recording:

```kotlin
registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> ... }
    .launch(Manifest.permission.RECORD_AUDIO)
```

### 3. Three steps

```kotlin
val changer = VoiceChanger(context)

// ① Record
changer.startRecording()
// changer.pauseRecording() / changer.resumeRecording()

// ② Stop and transform (suspend functions — call from a coroutine)
lifecycleScope.launch {
    val recording = changer.stopRecording()
    val file = changer.changeVoice(VoiceEffect.UNCLE)   // outputs .m4a
    // ③ Play
    changer.play(file) { /* playback finished */ }
}
```

Observe recording state and volume:

```kotlin
changer.recorder.state.collect { state -> ... }       // IDLE / RECORDING / PAUSED
changer.recorder.amplitude.collect { amp -> ... }     // 0~1, bind directly to a progress bar
```

### Real-time voice changing

```kotlin
val realtime = RealtimeVoiceChanger()
realtime.pitchSemiTones = 7f      // adjustable while running, takes effect immediately
realtime.start()                  // wear headphones to avoid feedback
...
realtime.stop()
```

Real-time mode supports pitch shifting only (tempo/rate change the output duration, which is impossible in a live loop). The capture source is `VOICE_COMMUNICATION`, which enables system echo cancellation on most devices.

## Built-in presets

| Preset | pitchSemiTones | tempo | rate | Effect |
|---|---|---|---|---|
| `VoiceEffect.NONE` | 0 | 1.0 | 1.0 | Original |
| `VoiceEffect.KITTY` | +4 | 1.02 | 1.2 | Kitten — high and fast |
| `VoiceEffect.ROSE` | +12.8 | 1.0 | 1.0 | Doll voice (exaggerated) |
| `VoiceEffect.WOMAN` | +7 | 1.0 | 1.0 | Male → female (natural) |
| `VoiceEffect.UNCLE` | −3.9 | 1.0 | 1.0 | Deep "uncle" voice |
| `VoiceEffect.MAN` | −7 | 1.0 | 1.0 | Female → male |
| `VoiceEffect.TOM` | +10 | 1.005 | 0.993 | Tom-cat |

For localized UIs, iterate over the stable `VoicePreset.entries` identifiers and resolve display names from resources. `VoiceEffect.PRESETS` remains a Chinese convenience map.

Custom voices are just a `VoiceEffect`:

```kotlin
val myEffect = VoiceEffect(pitchSemiTones = 8.5f, tempo = 1.1f)
```

For how to tune the three parameters and recipes for specific voices, see the **[voice-tuning guide](docs/voice-tuning.md)** (Chinese).

## Advanced usage

Skip the facade and compose the lower-level components directly:

```kotlin
// Record to a specific file
val recorder = VoiceRecorder(AudioConfig(sampleRate = 16000, channels = 1))
recorder.start(File(dir, "input.pcm"))
val result = recorder.stop()

// Transform: .wav produces lossless WAV; .m4a/.mp4 produces AAC in MPEG-4
VoiceProcessor.process(
    input = result.file,
    output = File(dir, "output.wav"),
    effect = VoiceEffect(pitchSemiTones = -6f),
    config = result.config,
    onProgress = { p -> ... },     // 0~1
)

// Stream-to-stream: any PCM stream in → transformed PCM stream out
VoiceProcessor.processStream(inputStream, outputStream, VoiceEffect.WOMAN)

// Or drive SoundTouch directly on arbitrary PCM chunks
SoundTouch(sampleRate = 44100, channels = 1).use { st ->
    st.setPitchSemiTones(6f)
    st.putSamples(pcmChunk)
    val buf = ShortArray(4096)
    while (true) { val n = st.receiveSamples(buf); if (n <= 0) break; /* consume */ }
    st.flush()  // trailing samples
    while (true) { val n = st.receiveSamples(buf); if (n <= 0) break; /* consume */ }
}
```

WAV input is parsed by its actual `fmt ` and `data` chunks, and its sample rate/channel configuration is used automatically. Unknown output extensions are rejected to prevent mismatched names and containers.

Full API reference: [docs/api.md](docs/api.md) (Chinese).

## Demo

The repository ships a runnable demo (`app` module): recording controls, one-tap presets, three parameter sliders, processing progress, playback, and real-time mode.

```bash
./gradlew :app:installDebug
```

## Build requirements

- Android Studio (Ladybug+) / AGP 8.7, Gradle 8.10 (wrapper included)
- JDK 17
- NDK 27.0.12077973 and CMake 3.22.1 (pinned in the project and CI)
- minSdk 21, compileSdk 35

## Architecture

Offline pipeline: `AudioRecord → PCM file → SoundTouch (pitch/tempo) → flush → WAV or MediaCodec AAC → MediaPlayer`.
Real-time pipeline: `AudioRecord → SoundTouch → AudioTrack`.

Library sources live under `voicechanger/src/main/java/io/github/neboyang/voicechanger/`; the bundled SoundTouch 2.4.1 sources and JNI bindings are under `voicechanger/src/main/cpp/`.

## FAQ

**Why M4A instead of the 1.x AMR output?**
1.x depended on the hidden API `android.media.AmrInputStream`, which normal projects cannot compile against and which was removed in Android 9. AAC sounds better at similar sizes and uses public APIs. Use WAV for lossless output.

**Why is real-time mode pitch-only?**
tempo/rate change the output duration — in a live loop the input and output rates must match, otherwise buffers grow without bound or underrun. Offline processing has no such limit.

## Continuous integration

[Android CI](.github/workflows/android.yml) runs unit tests, Lint, and the Debug build. Before submitting changes, run `./gradlew test lint assembleDebug` locally.

## License

- Project code: [Apache License 2.0](LICENSE)
- Bundled SoundTouch library: [LGPL v2.1](voicechanger/src/main/cpp/soundtouch/COPYING.TXT) (dynamically linked as a standalone `libsoundtouch.so`; observe the LGPL for commercial use)
- Full third-party notice: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
