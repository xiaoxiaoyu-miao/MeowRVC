# MeowRVC 变声插件 (NapCat)

QQ 语音变声插件：群里发语音 → 自动调用本机 RVC 引擎变声 → 以语音消息发回。

## 功能

1. **语音自动变声**：任何语音消息收到后，自动变声并回复（可开关）
2. **`#变声` 命令**：发送 `/sdcard/rvc` 目录下最新的本地变声文件
3. WebUI 可视化配置（NapCat 后台插件页）

## 架构

```
QQ 群 → NapCat → 本插件
                  ├─ 取到语音文件 → 解析 WAV
                  ├─ 16kHz 重采样
                  ├─ TCP 8181 → RVC 引擎 (Android App 内置服务器)
                  └─ 变声结果 → 写 WAV → 以语音消息发回群里
```

## 构建

```bash
npm install
npm run build   # 输出到 dist/
```

将 `dist/` 内容复制到 NapCat 的 `plugins` 目录即可。

## 配置项

| 配置 | 默认值 | 说明 |
|------|--------|------|
| rvcHost | 127.0.0.1 | RVC 引擎地址 |
| rvcPort | 8181 | RVC 引擎 TCP 端口 |
| f0UpKey | 0 | 变声升调键（半音） |
| autoConvert | true | 收到语音自动变声 |
| localVoiceDir | /sdcard/rvc | 本地变声文件目录 |
| sendLatestEnabled | true | 启用 #变声 命令 |

## 前置条件

- NapCat 与 Android App 在同一设备（Termux 运行 NapCat）
- App 已加载模型（启动 `startServer` TCP 8181）
- 语音文件需为 wav 格式（QQ 语音经 `get_record` 转换）

## 开发

```bash
npm run typecheck   # 类型检查
npm run build       # 构建
node scripts/smoke-test.mjs  # 模拟 NapCat ctx 冒烟测试
```
