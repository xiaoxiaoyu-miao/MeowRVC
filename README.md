# 喵喵RVC 🐱🎤

[English](#english) | [中文](#中文)

<a name="中文"></a>

# 喵喵RVC

基于 RVC（Retrieval-based Voice Conversion）的 Android 实时变声应用。使用 ONNX Runtime 在手机上直接运行 AI 变声模型。

## 特性

- **AI 实时变声** — 本地推理，无需联网
- **ONNX Runtime 加速** — 支持 NNAPI/NPU/GPU
- **延时可调** — 0.5~5 秒，低延迟或高质量
- **QQ 语音替换** — 处理后的音频可导出为 SLK 格式并自动替换 QQ 语音
- **内置 SILK 编码器** — 无需额外工具

## 构建

```bash
git clone https://github.com/你的用户名/喵喵RVC.git
cd 喵喵RVC
./gradlew :app:assembleDebug
```

需要 Android SDK 35 + NDK 27

## 模型

将 PC 转换好的 `.onnx` 文件夹放到 `/sdcard/models/` 下，App 自动扫描。

转换工具：`python convert_rvc.py --rvc_root RVC-Project --synthesizer model.pth --output_dir output`

---

<a name="english"></a>

# MeowRVC

Real-time voice conversion app for Android based on RVC (Retrieval-based Voice Conversion). Runs AI voice conversion models locally using ONNX Runtime.

## Features

- **Real-time AI voice conversion** — Local inference, no network needed
- **ONNX Runtime acceleration** — Supports NNAPI/NPU/GPU
- **Adjustable latency** — 0.5~5s, low latency or high quality
- **QQ voice replacement** — Export processed audio as SLK and auto-replace QQ voice messages
- **Built-in SILK encoder** — No extra tools needed

## Build

```bash
git clone https://github.com/yourusername/MeowRVC.git
cd MeowRVC
./gradlew :app:assembleDebug
```

Requires Android SDK 35 + NDK 27

## Models

Place `.onnx` model folders under `/sdcard/models/`. The app will scan automatically.

Conversion tool: `python convert_rvc.py --rvc_root RVC-Project --synthesizer model.pth --output_dir output`

## Tech Stack

- **ONNX Runtime** — Model inference
- **SILK Codec** — QQ voice format encoding
- **Android AudioRecord/AudioTrack** — Audio capture and playback
- **Material 3** — UI design
