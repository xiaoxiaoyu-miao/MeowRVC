# MeowRVC (喵喵RVC)

AI real-time voice conversion app for Android. Runs RVC (Retrieval-based Voice Conversion) models locally using ONNX Runtime.

## Features

- **Real-time voice conversion** - Select a model, speak into the mic, hear the converted voice instantly
- **Record & save to file** - Record, convert, save the result to `/sdcard/rvc/` without playing out loud
- **QQ bot (Lagrange.OneBot)** - Start a WebSocket server on port 2536, receive voice in QQ groups, auto-convert and reply; or send any converted file to a group / QQ account with one tap
- **Adjustable latency** - 0.5~5 seconds, lower for real-time, higher for better quality
- **Pitch shift** - +/- 15 semitones
- **ONNX Runtime acceleration** - Supports NNAPI/NPU/GPU

## 一键部署 QQ 机器人 (Termux)

基于 Lagrange.OneBot（dotnet），无需安装 NapCat。

**第一步** - 安装依赖（wget、.NET SDK 10.0、sfextract）：

```bash
curl -o install.sh https://raw.githubusercontent.com/xiaoxiaoyu-miao/MeowRVC/napcat/install.sh && bash install.sh
```

完成后**请先关闭并重新打开 Termux**，再执行第二步：

**第二步** - 部署 Lagrange：

```bash
curl -o deploy.sh https://raw.githubusercontent.com/xiaoxiaoyu-miao/MeowRVC/napcat/deploy.sh && bash deploy.sh
```

`deploy.sh` 会自动克隆仓库、解包 Lagrange、下载 `libSilkCodec.so` 与 Realm 原生库并生成启动脚本。部署完成后在 Termux 输入 `rvc` 即可启动机器人。首次登录按提示扫码（或输密码）后，机器人会以 ReverseWebSocket 连接 App 的 2536 端口。

### 前置条件

- 已安装并打开 MeowRVC App（会启动 2536 WS 服务器）
- Termux 已执行 `termux-setup-storage`（App 与 Termux 共享 `/sdcard/rvc` 变声文件）

## How It Works

```
1. Select model -> Start recording
2. Record -> Stop -> RVC converts -> saves to /sdcard/rvc/
3. In-app send button -> pick the file -> Lagrange sends it as voice to the target QQ group / account
4. (Optional) QQ group voice messages are auto-converted and replied back by the bot
```

## Requirements

- Android 8.0+ (API 26+)
- ~4GB free RAM for model inference
- RVC model files (`.onnx`) in `/sdcard/models/`
- Termux with .NET SDK 10.0 for the QQ bot (see one-click deploy above)

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
