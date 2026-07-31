/**
 * NapCat 插件 API 的最小本地类型声明
 * （避免依赖 napcat-types 包损坏的源码）
 */

/** 事件类型 */
export const EventType = {
    MESSAGE: 'message',
    NOTICE: 'notice',
    REQUEST: 'request',
    META: 'meta_event',
} as const;

export type EventPostType = typeof EventType[keyof typeof EventType];

/** 消息段 */
export interface OB11Segment {
    type: string;
    data?: Record<string, unknown>;
}

/** OneBot 消息事件 */
export interface OB11Message {
    post_type: EventPostType;
    message_type?: 'group' | 'private';
    group_id?: number | string;
    user_id?: number | string;
    message_id?: string;
    raw_message?: string;
    message?: OB11Segment[] | string;
    sender?: Record<string, unknown>;
}

/** 发送消息参数 */
export interface OB11PostSendMsg {
    message: string | OB11Segment[];
    message_type: 'group' | 'private';
    group_id?: string;
    user_id?: string;
}

/** 配置 Schema 项 */
export type PluginConfigSchema = unknown[];

/** 插件日志器 */
export interface PluginLogger {
    info(...args: unknown[]): void;
    warn(...args: unknown[]): void;
    error(...args: unknown[]): void;
    debug(...args: unknown[]): void;
}

/** NapCat 插件上下文 */
export interface NapCatPluginContext {
    logger: PluginLogger;
    adapterName: string;
    dataPath: string;
    configPath: string;
    pluginManager: {
        config: Record<string, unknown>;
        pluginName: string;
        pluginPath: string;
    };
    router: {
        static(prefix: string, dir: string): void;
        page(opts: { path: string; title: string; htmlFile: string; description?: string }): void;
        get(path: string, handler: (...args: unknown[]) => void): void;
        post(path: string, handler: (...args: unknown[]) => void): void;
        getNoAuth(path: string, handler: (...args: unknown[]) => void): void;
        postNoAuth(path: string, handler: (...args: unknown[]) => void): void;
    };
    actions: {
        call(action: string, params: Record<string, unknown>, adapterName?: string, config?: Record<string, unknown>): Promise<unknown>;
    };
    NapCatConfig: {
        boolean(key: string, label: string, defaultValue?: boolean, description?: string, reactive?: boolean): unknown;
        text(key: string, label: string, defaultValue?: string, description?: string, reactive?: boolean): unknown;
        number(key: string, label: string, defaultValue?: number, description?: string, reactive?: boolean): unknown;
        select(key: string, label: string, options: unknown[], defaultValue?: unknown, description?: string): unknown;
        multiSelect(key: string, label: string, options: unknown[], defaultValue?: unknown, description?: string): unknown;
        html(content: string): unknown;
        plainText(content: string): unknown;
        combine(...items: unknown[]): unknown[];
    };
}

/** 插件模块类型 */
export interface PluginModule {
    plugin_init: (ctx: NapCatPluginContext) => Promise<void> | void;
    plugin_onmessage?: (ctx: NapCatPluginContext, event: OB11Message) => Promise<void> | void;
    plugin_onevent?: (ctx: NapCatPluginContext, event: OB11Message) => Promise<void> | void;
    plugin_cleanup?: (ctx: NapCatPluginContext) => Promise<void> | void;
    plugin_config_ui?: PluginConfigSchema;
    plugin_get_config?: (ctx: NapCatPluginContext) => Promise<Record<string, unknown>>;
    plugin_set_config?: (ctx: NapCatPluginContext, config: Record<string, unknown>) => Promise<void> | void;
    plugin_on_config_change?: (
        ctx: NapCatPluginContext,
        ui: unknown,
        key: string,
        value: unknown,
        currentConfig: Record<string, unknown>
    ) => Promise<void> | void;
}
