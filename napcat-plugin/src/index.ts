/**
 * MeowRVC 变声插件 - 主入口
 *
 * 收到 QQ 语音消息 → 调用本机 RVC 引擎变声 → 以语音消息发回
 * 支持 #变声 命令发送最新本地变声文件
 */

import type {
    PluginModule,
    PluginConfigSchema,
    NapCatPluginContext,
} from './napcat-types';
import { EventType } from './napcat-types';

import { buildConfigSchema } from './config';
import { pluginState } from './core/state';
import { handleMessage } from './handlers/message-handler';
import { pingRvcEngine } from './services/rvc-client';
import type { PluginConfig } from './types';

export let plugin_config_ui: PluginConfigSchema = [];

/** 插件初始化 */
export const plugin_init: PluginModule['plugin_init'] = async (ctx) => {
    try {
        pluginState.init(ctx);
        plugin_config_ui = buildConfigSchema(ctx);
        ctx.logger.info('MeowRVC 变声插件初始化完成');
        // 启动时探测 RVC 引擎
        pingRvcEngine(pluginState.config.rvcHost, pluginState.config.rvcPort).then(ok => {
            ctx.logger.info(ok
                ? `RVC 引擎在线: ${pluginState.config.rvcHost}:${pluginState.config.rvcPort}`
                : `RVC 引擎未连接: ${pluginState.config.rvcHost}:${pluginState.config.rvcPort}（请先打开 App 并加载模型）`);
        });
    } catch (error) {
        ctx.logger.error('插件初始化失败:', error);
    }
};

/** 消息事件处理 */
export const plugin_onmessage: PluginModule['plugin_onmessage'] = async (ctx, event) => {
    if (event.post_type !== EventType.MESSAGE) return;
    if (!pluginState.config.enabled) return;
    await handleMessage(ctx, event);
};

/** 其他事件 */
export const plugin_onevent: PluginModule['plugin_onevent'] = async () => {};

/** 插件卸载 */
export const plugin_cleanup: PluginModule['plugin_cleanup'] = async (ctx) => {
    try {
        pluginState.cleanup();
        ctx.logger.info('MeowRVC 变声插件已卸载');
    } catch (e) {
        ctx.logger.warn('插件卸载时出错:', e);
    }
};

// ==================== 配置管理 ====================

export const plugin_get_config: PluginModule['plugin_get_config'] = async () => ({ ...pluginState.config });

export const plugin_set_config: PluginModule['plugin_set_config'] = async (ctx, config) => {
    pluginState.replaceConfig(config as unknown as PluginConfig);
    ctx.logger.info('配置已通过 WebUI 更新');
};

export const plugin_on_config_change: PluginModule['plugin_on_config_change'] = async (ctx, ui, key, value, currentConfig) => {
    try {
        pluginState.updateConfig({ [key]: value });
        ctx.logger.debug(`配置项 ${key} 已更新`);
    } catch (err) {
        ctx.logger.error(`更新配置项 ${key} 失败:`, err);
    }
};
