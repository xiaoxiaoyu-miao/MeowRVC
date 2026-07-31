import { defineConfig } from 'vite';
import { resolve } from 'path';
import { copyFileSync, mkdirSync, rmSync, existsSync } from 'fs';

const nodeBuiltins = ['fs', 'path', 'net', 'os', 'http', 'https', 'crypto', 'stream', 'util', 'url', 'buffer'];

export default defineConfig({
    build: {
        lib: {
            entry: resolve(__dirname, 'src/index.ts'),
            formats: ['es'],
            fileName: () => 'index.mjs',
        },
        outDir: 'dist',
        rollupOptions: {
            external: nodeBuiltins,
        },
        minify: false,
    },
    ssr: {
        noExternal: true,
    },
    plugins: [copyAssetsPlugin()],
});

/** 构建完成后复制 package.json 到 dist */
function copyAssetsPlugin() {
    return {
        name: 'copy-assets',
        closeBundle() {
            const dist = resolve(__dirname, 'dist');
            if (!existsSync(dist)) mkdirSync(dist, { recursive: true });
            copyFileSync(resolve(__dirname, 'package.json'), resolve(dist, 'package.json'));
        },
    };
}
