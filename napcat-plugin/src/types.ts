/**
 * 类型定义文件
 */

/** 插件配置 */
export interface PluginConfig {
    /** 全局开关 */
    enabled: boolean;
    /** 调试模式 */
    debug: boolean;
    /** RVC 引擎 TCP 地址（App 内置服务器） */
    rvcHost: string;
    /** RVC 引擎 TCP 端口 */
    rvcPort: number;
    /** RVC 升调键 */
    f0UpKey: number;
    /** 收到语音是否自动变声回复 */
    autoConvert: boolean;
    /** 回复文字前缀 */
    replyPrefix: string;
    /** 本地变声文件目录 */
    localVoiceDir: string;
    /** 是否启用 #变声 命令（发送最新本地变声文件） */
    sendLatestEnabled: boolean;
    /** 按群配置 */
    groupConfigs: Record<string, GroupConfig>;
}

/** 群配置 */
export interface GroupConfig {
    enabled?: boolean;
    autoConvert?: boolean;
}

/** RVC 引擎请求 */
export interface RvcRequest {
    audio: number[];
    f0_up_key?: number;
}

/** RVC 引擎响应 */
export interface RvcResponse {
    audio: number[];
    sr?: number;
}
