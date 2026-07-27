# VoiceChanger

[中文](README.md) | [English](README.en.md) | **日本語** | [한국어](README.ko.md)

[SoundTouch](https://codeberg.org/soundtouch/soundtouch) ベースの Android ボイスチェンジャーライブラリ。録音 → 変声（男声↔女声、ロリ、おじさん、トムキャット…）→ 保存/再生に加え、**リアルタイム変声**（話しながら変換音声を聴ける）に対応。すべてすぐに使えます。

> **2.x で全面リライト**：Kotlin + コルーチン API。SoundTouch 2.4.1 のソースを同梱し CMake でソースからビルド（arm64-v8a を含む全 ABI 対応）。JNI バインディング同梱（`net.surina.soundtouch.SoundTouch` の欠落問題を解消、[#2](https://github.com/neboyang/VoiceChanger/issues/2)）。Android から削除された非公開 API `AmrInputStream` の代わりに AAC/M4A と WAV を出力。完全なデモアプリ（[#3](https://github.com/neboyang/VoiceChanger/issues/3)）と[パラメータ調整ガイド](docs/voice-tuning.md)（[#1](https://github.com/neboyang/VoiceChanger/issues/1)）付き。移行方法は [CHANGELOG](CHANGELOG.md) を参照。

## 特徴

- 🎙 **録音**：`AudioRecord` からディスクへストリーミング書き込み、一時停止/再開、リアルタイム音量コールバック、長時間録音でもメモリ一定
- 🎭 **変声**：ピッチ（半音）、テンポ（音程を変えず速度変更）、レート（速度と音程を同時変更）の 3 パラメータを自由に組み合わせ。プリセット 7 種内蔵
- 🎧 **リアルタイムモード**：マイク → ピッチシフト → イヤホン。実行中にピッチを変更可能
- 🌊 **ストリーム処理**：`processStream` で任意の PCM ストリームを処理（ネットワーク、パイプ、ソケット）
- 💾 **出力**：WAV（ロスレス）または AAC/M4A（圧縮、全 Android バージョンで使える公開 API）
- 🧵 **モダン API**：Kotlin ファーストのコルーチン + `StateFlow`。非 suspend の低レベルクラスは Java からも利用可能
- 📦 **追加設定不要**：ネイティブコードはライブラリと一緒にビルド。`.so` の手動配置は不要。armeabi-v7a / arm64-v8a / x86 / x86_64 対応
- 🔒 **Scoped Storage 対応**：アプリ専用ディレクトリに出力するためストレージ権限不要（Android 10+）

## クイックスタート

### 1. 依存関係の追加

[JitPack](https://jitpack.io) 経由：

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

またはソースモジュールとして導入：本リポジトリの `voicechanger` ディレクトリをプロジェクトにコピーし `include(":voicechanger")` するだけ（NDK/CMake は Android Studio が自動ダウンロード）。

### 2. 権限

ライブラリの Manifest に `RECORD_AUDIO` は宣言済み。録音前に**ランタイム権限**をリクエストするだけです：

```kotlin
registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> ... }
    .launch(Manifest.permission.RECORD_AUDIO)
```

### 3. 3 ステップで変声

```kotlin
val changer = VoiceChanger(context)

// ① 録音
changer.startRecording()
// changer.pauseRecording() / changer.resumeRecording()

// ② 停止して変声（suspend 関数、コルーチン内で呼び出す）
lifecycleScope.launch {
    val recording = changer.stopRecording()
    val file = changer.changeVoice(VoiceEffect.UNCLE)   // .m4a を出力
    // ③ 再生
    changer.play(file) { /* 再生完了 */ }
}
```

録音状態と音量の監視：

```kotlin
changer.recorder.state.collect { state -> ... }       // IDLE / RECORDING / PAUSED
changer.recorder.amplitude.collect { amp -> ... }     // 0~1、プログレスバーに直結可能
```

### リアルタイム変声

```kotlin
val realtime = RealtimeVoiceChanger()
realtime.pitchSemiTones = 7f      // 実行中も変更可能、即時反映
realtime.start()                  // ハウリング防止のためイヤホン着用
...
realtime.stop()
```

リアルタイムモードはピッチシフトのみ対応（tempo/rate は出力の長さが変わるためライブ処理では使用不可）。キャプチャソースは `VOICE_COMMUNICATION` で、多くの端末でシステムのエコーキャンセルが有効になります。

## 内蔵プリセット

| プリセット | pitchSemiTones | tempo | rate | 効果 |
|---|---|---|---|---|
| `VoiceEffect.NONE` | 0 | 1.0 | 1.0 | 原音 |
| `VoiceEffect.KITTY` | +4 | 1.02 | 1.2 | 子猫、高く速い |
| `VoiceEffect.ROSE` | +12.8 | 1.0 | 1.0 | 人形ボイス（誇張） |
| `VoiceEffect.WOMAN` | +7 | 1.0 | 1.0 | 男声 → 女声（自然） |
| `VoiceEffect.UNCLE` | −3.9 | 1.0 | 1.0 | おじさんボイス |
| `VoiceEffect.MAN` | −7 | 1.0 | 1.0 | 女声 → 男声 |
| `VoiceEffect.TOM` | +10 | 1.005 | 0.993 | トムキャット |

多言語 UI では安定した識別子 `VoicePreset.entries` を列挙し、表示名をリソースから取得してください。`VoiceEffect.PRESETS` は中国語の簡易マップとして残されています。

カスタムボイスは `VoiceEffect` を作るだけ：

```kotlin
val myEffect = VoiceEffect(pitchSemiTones = 8.5f, tempo = 1.1f)
```

パラメータの調整方法と目的の声を作るレシピは **[調整ガイド](docs/voice-tuning.md)**（中国語）を参照。

## 高度な使い方

ファサードを使わず、下位コンポーネントを直接組み合わせる：

```kotlin
// 指定ファイルへ録音
val recorder = VoiceRecorder(AudioConfig(sampleRate = 16000, channels = 1))
recorder.start(File(dir, "input.pcm"))
val result = recorder.stop()

// 変声：.wav はロスレス WAV、.m4a/.mp4 は AAC（MPEG-4）
VoiceProcessor.process(
    input = result.file,
    output = File(dir, "output.wav"),
    effect = VoiceEffect(pitchSemiTones = -6f),
    config = result.config,
    onProgress = { p -> ... },     // 0~1
)

// ストリーム変声：任意の PCM ストリーム → 変声後の PCM ストリーム
VoiceProcessor.processStream(inputStream, outputStream, VoiceEffect.WOMAN)

// SoundTouch を直接使って任意の PCM チャンクを処理することも可能
SoundTouch(sampleRate = 44100, channels = 1).use { st ->
    st.setPitchSemiTones(6f)
    st.putSamples(pcmChunk)
    val buf = ShortArray(4096)
    while (true) { val n = st.receiveSamples(buf); if (n <= 0) break; /* 消費 */ }
    st.flush()  // 末尾の残りサンプル
    while (true) { val n = st.receiveSamples(buf); if (n <= 0) break; /* 消費 */ }
}
```

WAV 入力は実際の `fmt ` / `data` chunk を解析し、ファイル内のサンプルレートとチャンネル設定を自動的に使用します。未対応の出力拡張子はコンテナとの不一致を防ぐため拒否されます。

完全な API リファレンス：[docs/api.md](docs/api.md)（中国語）。

## デモ

リポジトリには実行可能なデモ（`app` モジュール）が同梱：録音コントロール、プリセットのワンタップ切替、3 パラメータのスライダー、処理進捗と再生、リアルタイムモード。

```bash
./gradlew :app:installDebug
```

## ビルド要件

- Android Studio（Ladybug 以降）/ AGP 8.7、Gradle 8.10（wrapper 同梱）
- JDK 17
- NDK 27.0.12077973 と CMake 3.22.1（プロジェクトと CI で固定）
- minSdk 21、compileSdk 35

## アーキテクチャ

オフライン処理：`AudioRecord → PCM ファイル → SoundTouch（ピッチ/テンポ）→ flush → WAV または MediaCodec AAC → MediaPlayer`。
リアルタイム処理：`AudioRecord → SoundTouch → AudioTrack`。

ライブラリのソースは `voicechanger/src/main/java/io/github/neboyang/voicechanger/`、同梱の SoundTouch 2.4.1 ソースと JNI バインディングは `voicechanger/src/main/cpp/` にあります。

## FAQ

**なぜ 1.x の AMR ではなく M4A を出力するのか？**
1.x は非公開 API `android.media.AmrInputStream` に依存しており、通常のプロジェクトではコンパイルできず、Android 9 で削除されました。AAC は同等サイズで音質が良く、公開 API のみを使用します。ロスレスが必要なら WAV を出力してください。

**リアルタイムモードがピッチのみなのはなぜ？**
tempo/rate は出力の長さを変えるため、ライブ処理では入出力レートが一致せずバッファが無限に溜まるか途切れます。オフライン処理には制限はありません。

## 継続的インテグレーション

[Android CI](.github/workflows/android.yml) は単体テスト、Lint、Debug ビルドを実行します。変更を送信する前に `./gradlew test lint assembleDebug` をローカルで実行してください。

## ライセンス

- 本プロジェクトのコード：[Apache License 2.0](LICENSE)
- 同梱の SoundTouch ライブラリ：[LGPL v2.1](voicechanger/src/main/cpp/soundtouch/COPYING.TXT)（独立した `libsoundtouch.so` として動的リンク。商用利用の際は LGPL の条項に従ってください）
- 第三者ライセンスの詳細：[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
