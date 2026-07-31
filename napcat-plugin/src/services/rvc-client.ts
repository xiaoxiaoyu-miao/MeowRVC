/**
 * RVC 引擎 TCP 客户端
 * 连接 App 内置的 RVC TCP 服务器（端口 8181）
 * 协议：发送 JSON 行 {"audio":[float...],"f0_up_key":0}
 *       接收 JSON 行 {"audio":[float...],"sr":40000}
 */

import net from 'net';
import type { RvcRequest, RvcResponse } from '../types';
import { pluginState } from '../core/state';

const TIMEOUT_MS = 120_000;

export async function callRvcEngine(
    audio: Float32Array,
    f0UpKey: number,
    host: string,
    port: number
): Promise<Float32Array | null> {
    return new Promise((resolve) => {
        const log = pluginState.logger;
        let buffer = '';
        let settled = false;

        const sock = net.createConnection({ host, port }, () => {
            const req: RvcRequest = { audio: Array.from(audio), f0_up_key: f0UpKey };
            sock.write(JSON.stringify(req) + '\n');
            log.debug(`[RVC] 已发送 ${audio.length} 采样 (${(audio.length / 16000).toFixed(1)}s)`);
        });

        const timer = setTimeout(() => {
            if (!settled) { settled = true; sock.destroy(); log.error('[RVC] 连接超时'); resolve(null); }
        }, TIMEOUT_MS);

        sock.on('data', (chunk) => {
            buffer += chunk.toString('utf-8');
            let idx: number;
            while ((idx = buffer.indexOf('\n')) >= 0) {
                const line = buffer.slice(0, idx).trim();
                buffer = buffer.slice(idx + 1);
                if (!line) continue;
                try {
                    const resp = JSON.parse(line) as RvcResponse;
                    if (resp.audio && resp.audio.length > 0) {
                        settled = true; clearTimeout(timer);
                        sock.destroy();
                        resolve(Float32Array.from(resp.audio));
                        return;
                    }
                } catch (e) {
                    log.warn(`[RVC] 解析失败: ${String(e)}`);
                }
            }
        });

        sock.on('error', (e) => {
            if (!settled) { settled = true; clearTimeout(timer); log.error(`[RVC] 连接失败: ${e.message}`); resolve(null); }
        });

        sock.on('close', () => {
            if (!settled) { settled = true; clearTimeout(timer); log.warn('[RVC] 连接关闭'); resolve(null); }
        });
    });
}

/** 心跳探测：连接是否可用 */
export function pingRvcEngine(host: string, port: number, timeoutMs = 3000): Promise<boolean> {
    return new Promise((resolve) => {
        const sock = net.createConnection({ host, port }, () => { sock.destroy(); resolve(true); });
        sock.on('error', () => resolve(false));
        sock.setTimeout(timeoutMs, () => { sock.destroy(); resolve(false); });
    });
}
