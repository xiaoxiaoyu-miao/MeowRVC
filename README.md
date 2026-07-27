# MeowRVC (喵喵RVC)

AI real-time voice conversion app for Android. Runs RVC (Retrieval-based Voice Conversion) models locally using ONNX Runtime.

## Features

- **Real-time voice conversion** - Select a model, speak into the mic, hear the converted voice instantly
- **Floating mic overlay** - Record audio from any app, process through AI, auto-playback through speaker for other apps to capture
- **Auto-detect microphone usage** - When WeChat/QQ starts recording, automatically plays the latest processed audio through the speaker at max volume (acoustic coupling)
- **Adjustable latency** - 0.5~5 seconds, lower for real-time, higher for better quality
- **Pitch shift** - +/- 15 semitones
- **ONNX Runtime acceleration** - Supports NNAPI/NPU/GPU

## How It Works

```
1. Select model -> Start floating mic overlay
2. Tap overlay to record -> Stop -> RVC processes -> saves to /sdcard/rvc/
3. Open WeChat/QQ -> Send voice message
4. App detects mic is busy -> Auto-plays latest processed audio through speaker at max volume
5. WeChat's mic picks up the speaker output -> Friend hears the converted voice
```

## Requirements

- Android 8.0+ (API 26+)
- Root (Magisk) for `setCommunicationDevice` / speaker routing
- ~4GB free RAM for model inference
- RVC model files (`.onnx`) in `/sdcard/models/`

## Build

```bash
git clone https://github.com/xiaoxiaoyu-miao/MeowRVC.git
cd MeowRVC
./gradlew :app:assembleDebug
```

Requires: Android SDK 35, NDK 27, JDK 17

The `onnxruntime.jar` and `.so` files are not included in the repository (`.gitignore`). Download them from Maven Central or GitHub Releases:

- `onnxruntime-android-1.18.0.aar` → extract `classes.jar` to `voicechanger/libs/onnxruntime.jar`
- Extract `jni/arm64-v8a/libonnxruntime*.so` to `voicechanger/src/main/jniLibs/arm64-v8a/`

## Model Files

Place your RVC ONNX model folder under `/sdcard/models/`. The app scans for:

- `hubert.onnx` - ContentVec/HuBERT feature extractor (v2, 768-dim)
- `text_encoder.onnx` - Synthesizer text encoder
- `flow.onnx` - Normalizing flow (reverse)
- `generator.onnx` - HiFi-GAN NSF generator
- `config.json` - Model metadata

Convert your `.pth` RVC model on PC:

```bash
python convert_rvc.py --rvc_root RVC-Project --synthesizer model.pth --output_dir output
```

## Tech Stack

- **ONNX Runtime** - Model inference
- **Android AudioRecord/AudioTrack** - Audio capture and playback
- **AudioManager.AudioRecordingCallback** - Microphone usage detection
- **setCommunicationDevice** - Speaker routing (Android 14+)
- **Material 3** - UI

## License

MIT
