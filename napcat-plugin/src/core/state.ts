/**
 * 全局状态管理（单例）
 */

import fs from 'fs';
import path from 'path';
import type { NapCatPluginContext, PluginLogger } from '../napcat-types';
import { DEFAULT_CONFIG } from '../config';
import type { PluginConfig, GroupConfig } from '../types';

function isObject(v: unknown): v is Record<string, unknown> {
    return v !== null && typeof v === 'object' && !Array.isArray(v);
}

function sanitizeConfig(raw: unknown): PluginConfig {
    if (!isObject(raw)) return { ...DEFAULT_CONFIG, groupConfigs: {} };
    const out: PluginConfig = { ...DEFAULT_CONFIG, groupConfigs: {} };
    if (typeof raw.enabled === 'boolean') out.enabled = raw.enabled;
    if (typeof raw.debug === 'boolean') out.debug = raw.debug;
    if (typeof raw.rvcHost === 'string') out.rvcHost = raw.rvcHost;
    if (typeof raw.rvcPort === 'number') out.rvcPort = raw.rvcPort;
    if (typeof raw.f0UpKey === 'number') out.f0UpKey = raw.f0UpKey;
    if (typeof raw.autoConvert === 'boolean') out.autoConvert = raw.autoConvert;
    if (typeof raw.replyPrefix === 'string') out.replyPrefix = raw.replyPrefix;
    if (typeof raw.localVoiceDir === 'string') out.localVoiceDir = raw.localVoiceDir;
    if (typeof raw.sendLatestEnabled === 'boolean') out.sendLatestEnabled = raw.sendLatestEnabled;
    if (isObject(raw.groupConfigs)) {
        for (const [gid, gc] of Object.entries(raw.groupConfigs)) {
            if (isObject(gc)) {
                const cfg: GroupConfig = {};
                if (typeof gc.enabled === 'boolean') cfg.enabled = gc.enabled;
                if (typeof gc.autoConvert === 'boolean') cfg.autoConvert = gc.autoConvert;
                out.groupConfigs[gid] = cfg;
            }
        }
    }
    return out;
}

class PluginState {
    private _ctx: NapCatPluginContext | null = null;
    config: PluginConfig = { ...DEFAULT_CONFIG };
    startTime = 0;
    stats = { processed: 0, todayProcessed: 0, lastUpdateDay: new Date().toDateString() };

    get ctx(): NapCatPluginContext {
        if (!this._ctx) throw new Error('PluginState 尚未初始化');
        return this._ctx;
    }

    get logger(): PluginLogger {
        return this.ctx.logger;
    }

    init(ctx: NapCatPluginContext): void {
        this._ctx = ctx;
        this.startTime = Date.now();
        this.loadConfig();
        this.ensureDataDir();
    }

    cleanup(): void {
        this.saveConfig();
        this._ctx = null;
    }

    private ensureDataDir(): void {
        const dataPath = this.ctx.dataPath;
        if (!fs.existsSync(dataPath)) fs.mkdirSync(dataPath, { recursive: true });
    }

    getDataFilePath(filename: string): string {
        return path.join(this.ctx.dataPath, filename);
    }

    loadConfig(): void {
        const configPath = this.ctx.configPath;
        try {
            if (configPath && fs.existsSync(configPath)) {
                const raw = JSON.parse(fs.readFileSync(configPath, 'utf-8'));
                this.config = sanitizeConfig(raw);
                if (isObject(raw) && isObject(raw.stats)) Object.assign(this.stats, raw.stats);
            } else {
                this.config = { ...DEFAULT_CONFIG, groupConfigs: {} };
                this.saveConfig();
            }
        } catch (e) {
            this.logger.error('加载配置失败:', e);
            this.config = { ...DEFAULT_CONFIG, groupConfigs: {} };
        }
    }

    saveConfig(): void {
        if (!this._ctx) return;
        try {
            const configPath = this._ctx.configPath;
            const configDir = path.dirname(configPath);
            if (!fs.existsSync(configDir)) fs.mkdirSync(configDir, { recursive: true });
            fs.writeFileSync(configPath, JSON.stringify({ ...this.config, stats: this.stats }, null, 2), 'utf-8');
        } catch (e) {
            this._ctx.logger.error('保存配置失败:', e);
        }
    }

    updateConfig(partial: Partial<PluginConfig>): void {
        this.config = { ...this.config, ...partial };
        this.saveConfig();
    }

    replaceConfig(config: PluginConfig): void {
        this.config = sanitizeConfig(config);
        this.saveConfig();
    }

    updateGroupConfig(groupId: string, config: Partial<GroupConfig>): void {
        this.config.groupConfigs[groupId] = { ...this.config.groupConfigs[groupId], ...config };
        this.saveConfig();
    }

    isGroupEnabled(groupId: string): boolean {
        return this.config.groupConfigs[groupId]?.enabled !== false;
    }

    incrementProcessed(): void {
        const today = new Date().toDateString();
        if (this.stats.lastUpdateDay !== today) {
            this.stats.todayProcessed = 0;
            this.stats.lastUpdateDay = today;
        }
        this.stats.todayProcessed++;
        this.stats.processed++;
    }
}

export const pluginState = new PluginState();
