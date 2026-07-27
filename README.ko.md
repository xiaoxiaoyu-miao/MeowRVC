# VoiceChanger

[中文](README.md) | [English](README.en.md) | [日本語](README.ja.md) | **한국어**

[SoundTouch](https://codeberg.org/soundtouch/soundtouch) 기반 Android 음성 변조 라이브러리. 녹음 → 변조(남↔여, 로리, 아저씨, 톰캣…) → 저장/재생은 물론, **실시간 음성 변조**(말하면서 변조된 목소리 듣기)까지 지원합니다. 모든 기능이 바로 사용 가능합니다.

> **2.x 전면 재작성**: Kotlin + 코루틴 API. SoundTouch 2.4.1 소스를 내장하여 CMake로 소스 빌드(arm64-v8a 포함 전체 ABI 지원). JNI 바인딩 내장(`net.surina.soundtouch.SoundTouch` 누락 문제 해결, [#2](https://github.com/neboyang/VoiceChanger/issues/2)). Android에서 제거된 히든 API `AmrInputStream` 대신 AAC/M4A와 WAV 출력. 완전한 데모 앱([#3](https://github.com/neboyang/VoiceChanger/issues/3))과 [파라미터 튜닝 가이드](docs/voice-tuning.md)([#1](https://github.com/neboyang/VoiceChanger/issues/1)) 포함. 마이그레이션은 [CHANGELOG](CHANGELOG.md) 참고.

## 특징

- 🎙 **녹음**: `AudioRecord` 스트리밍 디스크 기록, 일시정지/재개, 실시간 음량 콜백, 긴 녹음에도 메모리 사용량 일정
- 🎭 **음성 변조**: 피치(반음), 템포(음정 유지 속도 변경), 레이트(속도+음정 동시 변경) 3개 파라미터 자유 조합, 프리셋 7종 내장
- 🎧 **실시간 모드**: 마이크 → 피치 시프트 → 이어폰, 실행 중 피치 조절 가능
- 🌊 **스트림 처리**: `processStream`으로 임의의 PCM 스트림 처리(네트워크, 파이프, 소켓)
- 💾 **출력**: WAV(무손실) 또는 AAC/M4A(압축, 모든 Android 버전에서 사용 가능한 공개 API)
- 🧵 **모던 API**: Kotlin 우선 코루틴 + `StateFlow`; suspend가 아닌 저수준 클래스는 Java에서도 사용 가능
- 📦 **추가 설정 불필요**: 네이티브 코드는 라이브러리와 함께 빌드, `.so` 수동 배치 불필요. armeabi-v7a / arm64-v8a / x86 / x86_64 지원
- 🔒 **Scoped Storage 대응**: 앱 전용 디렉터리에 출력하므로 저장소 권한 불필요(Android 10+)

## 빠른 시작

### 1. 의존성 추가

[JitPack](https://jitpack.io) 사용:

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

또는 소스 모듈로 도입: 이 저장소의 `voicechanger` 디렉터리를 프로젝트에 복사하고 `include(":voicechanger")` 하면 됩니다(NDK/CMake는 Android Studio가 자동 다운로드).

### 2. 권한

라이브러리 Manifest에 `RECORD_AUDIO`가 이미 선언되어 있으므로, 녹음 전에 **런타임 권한**만 요청하면 됩니다:

```kotlin
registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> ... }
    .launch(Manifest.permission.RECORD_AUDIO)
```

### 3. 3단계로 완성

```kotlin
val changer = VoiceChanger(context)

// ① 녹음
changer.startRecording()
// changer.pauseRecording() / changer.resumeRecording()

// ② 정지 후 변조(suspend 함수, 코루틴에서 호출)
lifecycleScope.launch {
    val recording = changer.stopRecording()
    val file = changer.changeVoice(VoiceEffect.UNCLE)   // .m4a 출력
    // ③ 재생
    changer.play(file) { /* 재생 완료 */ }
}
```

녹음 상태와 음량 관찰:

```kotlin
changer.recorder.state.collect { state -> ... }       // IDLE / RECORDING / PAUSED
changer.recorder.amplitude.collect { amp -> ... }     // 0~1, 프로그레스 바에 바로 연결 가능
```

### 실시간 음성 변조

```kotlin
val realtime = RealtimeVoiceChanger()
realtime.pitchSemiTones = 7f      // 실행 중 변경 가능, 즉시 반영
realtime.start()                  // 하울링 방지를 위해 이어폰 착용
...
realtime.stop()
```

실시간 모드는 피치 시프트만 지원합니다(tempo/rate는 출력 길이가 변하므로 라이브 처리에서 사용 불가). 캡처 소스는 `VOICE_COMMUNICATION`으로, 대부분의 기기에서 시스템 에코 캔슬이 활성화됩니다.

## 내장 프리셋

| 프리셋 | pitchSemiTones | tempo | rate | 효과 |
|---|---|---|---|---|
| `VoiceEffect.NONE` | 0 | 1.0 | 1.0 | 원음 |
| `VoiceEffect.KITTY` | +4 | 1.02 | 1.2 | 아기 고양이, 높고 빠름 |
| `VoiceEffect.ROSE` | +12.8 | 1.0 | 1.0 | 인형 목소리(과장) |
| `VoiceEffect.WOMAN` | +7 | 1.0 | 1.0 | 남성 → 여성(자연스러움) |
| `VoiceEffect.UNCLE` | −3.9 | 1.0 | 1.0 | 아저씨 목소리 |
| `VoiceEffect.MAN` | −7 | 1.0 | 1.0 | 여성 → 남성 |
| `VoiceEffect.TOM` | +10 | 1.005 | 0.993 | 톰캣 |

다국어 UI에서는 안정적인 식별자인 `VoicePreset.entries`를 순회하고 리소스에서 표시 이름을 가져오세요. `VoiceEffect.PRESETS`는 중국어 편의 맵으로 유지됩니다.

커스텀 보이스는 `VoiceEffect`를 만들기만 하면 됩니다:

```kotlin
val myEffect = VoiceEffect(pitchSemiTones = 8.5f, tempo = 1.1f)
```

파라미터 튜닝 방법과 원하는 목소리를 만드는 레시피는 **[튜닝 가이드](docs/voice-tuning.md)**(중국어)를 참고하세요.

## 고급 사용법

파사드 없이 하위 컴포넌트를 직접 조합:

```kotlin
// 지정 파일로 녹음
val recorder = VoiceRecorder(AudioConfig(sampleRate = 16000, channels = 1))
recorder.start(File(dir, "input.pcm"))
val result = recorder.stop()

// 변조: .wav는 무손실 WAV, .m4a/.mp4는 AAC(MPEG-4)
VoiceProcessor.process(
    input = result.file,
    output = File(dir, "output.wav"),
    effect = VoiceEffect(pitchSemiTones = -6f),
    config = result.config,
    onProgress = { p -> ... },     // 0~1
)

// 스트림 변조: 임의의 PCM 스트림 → 변조된 PCM 스트림
VoiceProcessor.processStream(inputStream, outputStream, VoiceEffect.WOMAN)

// SoundTouch를 직접 사용해 임의의 PCM 청크 처리도 가능
SoundTouch(sampleRate = 44100, channels = 1).use { st ->
    st.setPitchSemiTones(6f)
    st.putSamples(pcmChunk)
    val buf = ShortArray(4096)
    while (true) { val n = st.receiveSamples(buf); if (n <= 0) break; /* 소비 */ }
    st.flush()  // 잔여 샘플
    while (true) { val n = st.receiveSamples(buf); if (n <= 0) break; /* 소비 */ }
}
```

WAV 입력은 실제 `fmt ` / `data` 청크를 파싱하며 파일의 샘플 레이트와 채널 설정을 자동으로 사용합니다. 지원하지 않는 출력 확장자는 이름과 컨테이너 불일치를 막기 위해 거부됩니다.

전체 API 레퍼런스: [docs/api.md](docs/api.md)(중국어).

## 데모

저장소에는 실행 가능한 데모(`app` 모듈)가 포함되어 있습니다: 녹음 컨트롤, 프리셋 원탭 전환, 3개 파라미터 슬라이더, 처리 진행률과 재생, 실시간 모드.

```bash
./gradlew :app:installDebug
```

## 빌드 요구 사항

- Android Studio(Ladybug 이상) / AGP 8.7, Gradle 8.10(wrapper 내장)
- JDK 17
- NDK 27.0.12077973 및 CMake 3.22.1(프로젝트와 CI에 고정)
- minSdk 21, compileSdk 35

## 아키텍처

오프라인 파이프라인: `AudioRecord → PCM 파일 → SoundTouch(피치/템포) → flush → WAV 또는 MediaCodec AAC → MediaPlayer`.
실시간 파이프라인: `AudioRecord → SoundTouch → AudioTrack`.

라이브러리 소스는 `voicechanger/src/main/java/io/github/neboyang/voicechanger/`에, 내장된 SoundTouch 2.4.1 소스와 JNI 바인딩은 `voicechanger/src/main/cpp/`에 있습니다.

## FAQ

**왜 1.x의 AMR 대신 M4A를 출력하나요?**
1.x는 히든 API `android.media.AmrInputStream`에 의존했는데, 일반 프로젝트에서는 컴파일할 수 없고 Android 9에서 제거되었습니다. AAC는 비슷한 용량에 음질이 더 좋으며 공개 API만 사용합니다. 무손실이 필요하면 WAV로 출력하세요.

**실시간 모드는 왜 피치만 지원하나요?**
tempo/rate는 출력 길이를 바꾸기 때문에 라이브 루프에서는 입출력 속도가 일치하지 않아 버퍼가 무한히 쌓이거나 끊깁니다. 오프라인 처리에는 제한이 없습니다.

## 지속적 통합

[Android CI](.github/workflows/android.yml)는 단위 테스트, Lint 및 Debug 빌드를 실행합니다. 변경 사항을 제출하기 전에 `./gradlew test lint assembleDebug`를 로컬에서 실행하세요.

## 라이선스

- 프로젝트 코드: [Apache License 2.0](LICENSE)
- 내장 SoundTouch 라이브러리: [LGPL v2.1](voicechanger/src/main/cpp/soundtouch/COPYING.TXT)(독립적인 `libsoundtouch.so`로 동적 링크. 상용 이용 시 LGPL 조항을 준수하세요)
- 전체 서드파티 고지: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
