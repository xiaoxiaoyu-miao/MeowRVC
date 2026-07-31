/**
 * 消息处理器
 * - 收到语音消息 → 自动变声回复
 * - #变声 命令 → 发送最新本地变声文件
 */

import fs from 'fs';
import path from 'path';
import type { OB11Message, OB11PostSendMsg, NapCatPluginContext } from '../napcat-types';
import { pluginState } from '../core/state';
import { callRvcEngine, pingRvcEngine } from '../services/rvc-client';
import { readWavFloat, resample, writeWavPcm16, latestWavInDir } from '../services/audio-utils';

// ==================== 发送工具 ====================

export async function sendReply(
    ctx: NapCatPluginContext,
    event: OB11Message,
    message: OB11PostSendMsg['message']
): Promise<boolean> {
    try {
        const params: Record<string, unknown> = {
            message,
            message_type: event.message_type,
            ...(event.message_type === 'group' && event.group_id ? { group_id: String(event.group_id) } : {}),
            ...(event.message_type === 'private' && event.user_id ? { user_id: String(event.user_id) } : {}),
        };
        await ctx.actions.call('send_msg', params, ctx.adapterName, ctx.pluginManager.config);
        return true;
    } catch (error) {
        pluginState.logger.error('发送消息失败:', error);
        return false;
    }
}

// ==================== 核心逻辑 ====================

/** 从消息段中提取语音 record 段 */
function extractRecordSegment(event: OB11Message) {
    const segs = event.message as Array<{ type: string; data?: Record<string, unknown> }>;
    if (!Array.isArray(segs)) return null;
    return segs.find(s => s.type === 'record');
}

/** 主处理入口 */
export async function handleMessage(ctx: NapCatPluginContext, event: OB11Message): Promise<void> {
    try {
        if (!pluginState.config.enabled) return;
        if (event.message_type === 'group' && event.group_id && !pluginState.isGroupEnabled(String(event.group_id))) return;

        const raw = event.raw_message || '';

        // 1. #变声 命令：发送最新本地变声文件
        if (pluginState.config.sendLatestEnabled && raw.trim().startsWith('#变声')) {
            await handleSendLatest(ctx, event);
            return;
        }

        // 2. 收到语音消息 → 自动变声
        if (!pluginState.config.autoConvert) return;
        const groupAuto = event.message_type === 'group' && event.group_id
            ? pluginState.config.groupConfigs[String(event.group_id)]?.autoConvert
            : undefined;
        if (groupAuto === false) return;

        const recSeg = extractRecordSegment(event);
        if (recSeg) {
            await handleVoiceConvert(ctx, event, recSeg);
        }
    } catch (error) {
        pluginState.logger.error('处理消息出错:', error);
    }
}

/** 处理语音变声 */
async function handleVoiceConvert(ctx: NapCatPluginContext, event: OB11Message, seg: { type: string; data?: Record<string, unknown> }): Promise<void> {
    const cfg = pluginState.config;
    const log = pluginState.logger;
    const data = seg.data || {};

    // 先发提示，避免等待时间过长
    await sendReply(ctx, event, '🎵 变声中...');

    try {
        // 获取语音文件（file 可能是路径或 URL）
        const fileVal = String(data.file || '');
        let localPath: string | null = null;

        if (fileVal.startsWith('http')) {
            // 通过 OneBot get_record 下载转换
            localPath = await fetchRecord(ctx, data, fileVal);
        } else {
            // 本地文件：尝试直接读取，否则用 get_record
            localPath = fileVal || null;
        }

        if (!localPath || !fs.existsSync(localPath)) {
            const fetched = await fetchRecord(ctx, data, fileVal);
            localPath = fetched || null;
        }
        if (!localPath || !fs.existsSync(localPath)) {
            await sendReply(ctx, event, '无法获取语音文件');
            return;
        }

        // 读取 WAV → float
        const wav = readWavFloat(localPath);
        if (!wav || wav.samples.length < 320) {
            await sendReply(ctx, event, '语音解析失败，仅支持 wav 格式');
            return;
        }

        // 重采样到 16kHz（RVC 引擎输入）
        const samples16k = resample(wav.samples, wav.sampleRate, 16000);

        // 调用 RVC 引擎
        const out = await callRvcEngine(samples16k, cfg.f0UpKey, cfg.rvcHost, cfg.rvcPort);
        if (!out || out.length === 0) {
            await sendReply(ctx, event, '变声失败：RVC 引擎无响应，请确认 App 已加载模型');
            return;
        }

        // 写 WAV 到本地目录
        const outDir = cfg.localVoiceDir;
        if (!fs.existsSync(outDir)) fs.mkdirSync(outDir, { recursive: true });
        const outFile = path.join(outDir, `qq_${Date.now()}.wav`);
        writeWavPcm16(outFile, out, 40000);

        // 发送语音消息（附文字）
        const prefix = cfg.replyPrefix || '✨ 变声完成';
        const msgSegs: OB11PostSendMsg['message'] = [
            { type: 'text', data: { text: prefix } },
            { type: 'record', data: { file: outFile } },
        ];
        const ok = await sendReply(ctx, event, msgSegs);
        if (ok) pluginState.incrementProcessed();
    } catch (error) {
        log.error('变声失败:', error);
        await sendReply(ctx, event, '变声失败，请稍后再试');
    }
}

/** 通过 OneBot get_record 获取本地语音文件 */
async function fetchRecord(ctx: NapCatPluginContext, data: Record<string, unknown>, fileVal: string): Promise<string | null> {
    try {
        const res = await ctx.actions.call('get_record', { file: fileVal, out_format: 'wav' }, ctx.adapterName, ctx.pluginManager.config) as { file?: string };
        if (res?.file) return res.file;
    } catch (e) {
        pluginState.logger.warn(`[fetchRecord] get_record 失败: ${String(e)}`);
    }
    return null;
}

/** #变声：发送最新本地变声文件 */
async function handleSendLatest(ctx: NapCatPluginContext, event: OB11Message): Promise<void> {
    const dir = pluginState.config.localVoiceDir;
    const latest = latestWavInDir(dir);
    if (!latest) {
        await sendReply(ctx, event, '本地没有变声文件');
        return;
    }
    const msgSegs: OB11PostSendMsg['message'] = [
        { type: 'text', data: { text: `✨ 最新变声文件: ${path.basename(latest)}` } },
        { type: 'record', data: { file: latest } },
    ];
    await sendReply(ctx, event, msgSegs);
}
