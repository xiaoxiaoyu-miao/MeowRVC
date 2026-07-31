/**
 * 冒烟测试：模拟 NapCat ctx 验证插件加载
 * 用法: node scripts/smoke-test.mjs
 */
import { createRequire } from 'module';

const req = createRequire(import.meta.url);
const mod = await import('../dist/index.mjs');

let infoLogs = [];
let debugLogs = [];

const mockCtx = {
    logger: {
        info: (...a) => { infoLogs.push(a.join(' ')); console.log('[info]', ...a); },
        warn: (...a) => console.log('[warn]', ...a),
        error: (...a) => console.log('[error]', ...a),
        debug: (...a) => { debugLogs.push(a.join(' ')); },
    },
    adapterName: 'http',
    dataPath: '/data/data/com.termux/files/home/VoiceChanger/napcat-plugin/.smoke/data',
    configPath: '/data/data/com.termux/files/home/VoiceChanger/napcat-plugin/.smoke/config.json',
    pluginManager: { config: {}, pluginName: 'meow-rvc', pluginPath: '/tmp' },
    router: { static: () => {}, page: () => {}, get: () => {}, post: () => {}, getNoAuth: () => {}, postNoAuth: () => {} },
    actions: {
        call: async (action, params) => {
            console.log(`  [actions.call] ${action}`, JSON.stringify(params).slice(0, 100));
            if (action === 'get_login_info') return { user_id: 10001 };
            return {};
        },
    },
    NapCatConfig: {
        boolean: (k, l) => ({ k, l }),
        text: (k, l) => ({ k, l }),
        number: (k, l) => ({ k, l }),
        select: (k, l) => ({ k, l }),
        multiSelect: (k, l) => ({ k, l }),
        html: (c) => ({ c }),
        plainText: (c) => ({ c }),
        combine: (...items) => items,
    },
};

console.log('=== 测试 plugin_init ===');
await mod.plugin_init(mockCtx);
console.log('config_ui 生成条目数:', mod.plugin_config_ui.length);

console.log('\n=== 测试 plugin_get_config ===');
const cfg = await mod.plugin_get_config(mockCtx);
console.log('rvcHost:', cfg.rvcHost, '| rvcPort:', cfg.rvcPort, '| autoConvert:', cfg.autoConvert);

console.log('\n=== 测试 plugin_on_config_change ===');
await mod.plugin_on_config_change(mockCtx, null, 'f0UpKey', 5, cfg);
const cfg2 = await mod.plugin_get_config(mockCtx);
console.log('f0UpKey 更新后:', cfg2.f0UpKey);

console.log('\n=== 测试 plugin_cleanup ===');
await mod.plugin_cleanup(mockCtx);
console.log('清理完成');

console.log('\n=== SMOKE TEST PASSED ===');
