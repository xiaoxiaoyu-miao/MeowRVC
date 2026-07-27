# Changelog

## Unreleased

### 完善
- PCM 流支持任意短读取并跨块保留残帧；EOF 半帧会明确报错
- WAV 输入改为解析 RIFF chunk，支持 `LIST`/`JUNK`/扩展 `fmt `，并校验格式与数据边界
- 录音和实时变声增加并发启动保护、主动解除阻塞读取与可靠资源清理
- 实时 AudioTrack 支持短写重试和错误码检查
- AAC 编码支持协程取消检查，所有初始化失败路径均释放资源并删除不完整输出
- JNI 入口统一捕获 native 异常，SoundTouch 构造失败时释放 native handle
- 公开的协程和 AndroidX 注解依赖改为 Maven `api` 依赖
- 增加 PCM 帧边界、WAV chunk、参数校验测试及 Android CI
- 增加 SoundTouch 第三方许可证声明

## 2.1.0 (2026-07)

### 新增
- **实时变声** `RealtimeVoiceChanger`：麦克风 → SoundTouch → 耳机，边说边听；`pitchSemiTones` 运行中实时调整；`VOICE_COMMUNICATION` 采集源（系统回声消除）
- **流式处理** `VoiceProcessor.processStream(InputStream, OutputStream, ...)`：任意 PCM 流变声（网络流、管道、Socket 等场景）
- Demo 新增「实时变声」入口，音调滑杆实时生效
- README 增加英文（README.en.md）、日文（README.ja.md）、韩文（README.ko.md）版本

## 2.0.0 (2026-07)

**完全重写。** 针对 issues [#1](https://github.com/neboyang/VoiceChanger/issues/1)、[#2](https://github.com/neboyang/VoiceChanger/issues/2)、[#3](https://github.com/neboyang/VoiceChanger/issues/3) 及大量历史缺陷。

### 新增
- 内置 SoundTouch **2.4.1** 源码，CMake 从源编译，支持 armeabi-v7a / **arm64-v8a** / x86 / x86_64（1.x 仅有 32 位预编译 `.so`）
- 内置 JNI 绑定 `SoundTouch.kt`（1.x 缺失 `net.surina.soundtouch.SoundTouch`，无法编译，#2）
- **Demo app**（`app` 模块）：录音控制、预设切换、三参数滑杆实时调参（#3）
- 文档：README 重写、[API 文档](docs/api.md)、[音色调参指南](docs/voice-tuning.md)（#1）
- 输出 **AAC/M4A**（MediaCodec + MediaMuxer 公开 API）与 **WAV**
- Kotlin 协程 API：`StateFlow` 状态/音量、suspend 函数、进度回调、协程取消
- 新预设 `WOMAN`（+7 半音）/ `MAN`（−7 半音），更自然的男女声互换
- 单元测试（WAV 头）；JitPack 发布配置

### 修复（1.x 缺陷）
- WAV 头采样率写死 16000Hz 而数据为 8000Hz，导致 WAV 播放快一倍
- SoundTouch 处理完未调用 `flush()`，音频结尾被截断
- 录音数据全量驻留内存（`LinkedList<short[]>`），长录音 OOM → 改为流式写盘
- 录音暂停忙等空转 CPU → 锁等待，暂停期间释放采集
- 录音与变声线程共享 `LinkedList` 无同步，并发使用抛 `ConcurrentModificationException`
- `AudioRecord` 初始化失败后未 return 继续录音
- 音量计算把整个缓冲区（含旧数据）计入、`len=0` 时除零
- `VoicePlayer.stop()` 空指针；`Log.e` 传 null message 二次崩溃
- 输出路径写外置存储根目录（Android 10+ 不可写）→ 改为应用专属目录，无需存储权限

### 变更（Breaking）
- 包名 `com.hello1987.voicechanger` → `io.github.neboyang.voicechanger`
- 移除 AMR 输出：依赖的 `android.media.AmrInputStream` 为隐藏 API 且已在 Android 9 移除
- `VoiceType` 枚举 → `VoiceEffect` 数据类（参数可自由组合，原预设保留同名常量）
- Handler 消息码回调 → 协程 + `StateFlow` + 异常
- minSdk 15 → 21；构建迁移到 AGP 8.7 / Gradle 8.10 / Kotlin 2.1 / JDK 17

### 1.x → 2.0 迁移对照

| 1.x | 2.0 |
|---|---|
| `new VoiceChanger()` + `setOnVoiceChangeListener` | `VoiceChanger(context)` + 协程 |
| `startRecording()` | `startRecording()`（不变） |
| `stopRecording()` + 等待 `MSG_RECORDING_FINISHED` | `stopRecording()`（suspend，直接返回结果） |
| `changeVoice(VoiceType.VT_UNCLE)` + `MSG_CHANGING_FINISHED` | `changeVoice(VoiceEffect.UNCLE)`（suspend，返回 File） |
| `VoicePlayer.newInstance().startPlaying(path)` | `changer.play(file)` |
| 输出 `/sdcard/audio/*.amr` | 应用专属目录 `*.m4a` / `*.wav` |

## 1.0 (2016-02)

初始版本：Java + 预编译 32 位 `.so`，输出 AMR。
