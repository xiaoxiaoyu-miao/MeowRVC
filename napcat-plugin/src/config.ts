/**
 * 配置定义
 */

import type { NapCatPluginContext, PluginConfigSchema } from './napcat-types';
import type { PluginConfig } from './types';

/** 默认配置 */
export const DEFAULT_CONFIG: PluginConfig = {
    enabled: true,
    debug: false,
    rvcHost: '127.0.0.1',
    rvcPort: 8181,
    f0UpKey: 0,
    autoConvert: true,
    replyPrefix: '✨ 变声完成',
    localVoiceDir: '/sdcard/rvc',
    sendLatestEnabled: true,
    groupConfigs: {},
};

/**
 * 构建 WebUI 配置 Schema
 */
export function buildConfigSchema(ctx: NapCatPluginContext): PluginConfigSchema {
    return ctx.NapCatConfig.combine(
        ctx.NapCatConfig.html(`
            <div style="padding: 16px; background: #6c5ce7; border-radius: 12px; margin-bottom: 20px; color: white;">
                <h3 style="margin: 0 0 6px 0; font-size: 18px; font-weight: 600;">MeowRVC 变声</h3>
                <p style="margin: 0; font-size: 13px; opacity: 0.85;">收到语音自动变声后发回，或 #变声 发送最新本地变声文件</p>
            </div>
        `),
        ctx.NapCatConfig.boolean('enabled', '启用插件', true, '是否启用变声功能'),
        ctx.NapCatConfig.boolean('debug', '调试模式', false, '输出详细日志'),
        ctx.NapCatConfig.text('rvcHost', 'RVC 引擎地址', '127.0.0.1', 'App 内置 TCP 服务地址'),
        ctx.NapCatConfig.number('rvcPort', 'RVC 引擎端口', 8181, 'App 内置 TCP 服务端口'),
        ctx.NapCatConfig.number('f0UpKey', '升调键 (半音)', 0, '变声音高偏移'),
        ctx.NapCatConfig.boolean('autoConvert', '语音自动变声', true, '收到语音消息自动变声并回复'),
        ctx.NapCatConfig.text('replyPrefix', '回复文字前缀', '✨ 变声完成', '变声语音前的文字说明'),
        ctx.NapCatConfig.text('localVoiceDir', '本地变声目录', '/sdcard/rvc', '存放变声后 wav 的目录'),
        ctx.NapCatConfig.boolean('sendLatestEnabled', '启用 #变声 命令', true, '发送最新本地变声文件'),
    );
}
