/**
 * WAV 读写与重采样工具（纯 TS，无依赖）
 */

import { readFileSync, writeFileSync, readdirSync, statSync } from 'fs';
import { join } from 'path';
import { pluginState } from '../core/state';

/** 读取 WAV → Float32 PCM */
export function readWavFloat(filePath: string): { samples: Float32Array; sampleRate: number } | null {
    try {
        const buf = readFileSync(filePath);
        if (buf.length < 44) return null;
        const sampleRate = buf.readUInt32LE(24);
        const channels = buf.readUInt16LE(22);
        const bits = buf.readUInt16LE(34);
        const dataSize = buf.readUInt32LE(40);
        const dataStart = 44;
        const byteLen = Math.min(dataSize, buf.length - dataStart);
        if (bits !== 16) {
            // 8/24/32 bit 转成 16bit 简化处理（仅支持 8bit 与 16bit）
            if (bits === 8) {
                const n = Math.floor(byteLen);
                const out = new Float32Array(n);
                for (let i = 0; i < n; i++) out[i] = (buf[dataStart + i] - 128) / 128;
                return { samples: out, sampleRate };
            }
            return null;
        }
        const n = Math.floor(byteLen / 2);
        const samples = new Float32Array(n);
        for (let i = 0; i < n; i++) {
            samples[i] = buf.readInt16LE(dataStart + i * 2) / 32768;
        }
        // 多声道取平均
        if (channels > 1) {
            const monoLen = Math.floor(n / channels);
            const mono = new Float32Array(monoLen);
            for (let i = 0; i < monoLen; i++) {
                let s = 0;
                for (let c = 0; c < channels; c++) s += samples[i * channels + c];
                mono[i] = s / channels;
            }
            return { samples: mono, sampleRate };
        }
        return { samples, sampleRate };
    } catch (e) {
        pluginState.logger.error(`[WAV] 读取失败: ${filePath}`, e);
        return null;
    }
}

/** 重采样到目标采样率 */
export function resample(samples: Float32Array, from: number, to: number): Float32Array {
    if (from === to || samples.length === 0) return samples;
    const ratio = from / to;
    const outLen = Math.max(1, Math.floor(samples.length / ratio));
    const out = new Float32Array(outLen);
    for (let i = 0; i < outLen; i++) {
        const pos = i * ratio;
        const idx = Math.floor(pos);
        const frac = pos - idx;
        const a = samples[Math.min(idx, samples.length - 1)];
        const b = samples[Math.min(idx + 1, samples.length - 1)];
        out[i] = a + (b - a) * frac;
    }
    return out;
}

/** 写入 WAV (PCM16) */
export function writeWavPcm16(filePath: string, samples: Float32Array, sampleRate: number): void {
    const numSamples = samples.length;
    const byteRate = sampleRate * 2;
    const dataSize = numSamples * 2;
    const buf = Buffer.alloc(44 + dataSize);
    buf.write('RIFF', 0);
    buf.writeUInt32LE(36 + dataSize, 4);
    buf.write('WAVE', 8);
    buf.write('fmt ', 12);
    buf.writeUInt32LE(16, 16);
    buf.writeUInt16LE(1, 20);
    buf.writeUInt16LE(1, 22);
    buf.writeUInt32LE(sampleRate, 24);
    buf.writeUInt32LE(byteRate, 28);
    buf.writeUInt16LE(2, 32);
    buf.writeUInt16LE(16, 34);
    buf.write('data', 36);
    buf.writeUInt32LE(dataSize, 40);
    for (let i = 0; i < numSamples; i++) {
        const v = Math.max(-1, Math.min(1, samples[i]));
        buf.writeInt16LE(Math.round(v * 32767), 44 + i * 2);
    }
    writeFileSync(filePath, buf);
}

/** 读取最新变声文件路径（目录下最新 wav） */
export function latestWavInDir(dir: string): string | null {
    try {
        const files = readdirSync(dir).filter((f: string) => f.endsWith('.wav'));
        if (files.length === 0) return null;
        files.sort((a: string, b: string) => statSync(join(dir, b)).mtimeMs - statSync(join(dir, a)).mtimeMs);
        return join(dir, files[0]);
    } catch (e) {
        return null;
    }
}
