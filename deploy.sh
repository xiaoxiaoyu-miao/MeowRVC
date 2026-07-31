#!/data/data/com.termux/files/usr/bin/bash

# --- 配置区域 ---
GIT_REPO="https://github.com/xiaoxiaoyu-miao/MeowRVC"
BRANCH="napcat"
WORK_DIR="$HOME/rvcqq"
EXTRACT_DIR="$WORK_DIR/extracted_files"
CONFIG_DIR="$WORK_DIR/111111111111"

echo "==> 1. 克隆仓库并切换分支..."
git clone -b "$BRANCH" "$GIT_REPO" "$WORK_DIR"
cd "$WORK_DIR" || exit 1

# 假设 Lagrange 可执行文件在仓库根目录
LAGRANGE_BIN="$WORK_DIR/Lagrange"
if [ ! -f "$LAGRANGE_BIN" ]; then
    echo "❌ 错误: 在 $WORK_DIR 中找不到 Lagrange 可执行文件！"
    exit 1
fi

echo "==> 2. 使用 sfextract 解包 Lagrange..."
sfextract -o "$EXTRACT_DIR" "$LAGRANGE_BIN"

echo "==> 3. 创建配置目录并移动配置文件..."
mkdir -p "$CONFIG_DIR"
mv "$WORK_DIR"/appsettings.json "$WORK_DIR"/device.json "$WORK_DIR"/keystore.json "$CONFIG_DIR"/ 2>/dev/null || true
mv "$WORK_DIR"/lagrange-*-db "$CONFIG_DIR"/ 2>/dev/null || true

echo "==> 4. 下载并替换 Realm 原生库 (20.1.0)..."
REALM_TGZ="io.realm.unity-20.1.0.tgz"
wget -O "$REALM_TGZ" "https://sourceforge.net/projects/realm-dotnet.mirror/files/20.1.0/$REALM_TGZ/download"

if [ -f "$REALM_TGZ" ]; then
    tar -xzf "$REALM_TGZ"
    SOURCE_SO="package/Runtime/Android/arm64-v8a/librealm-wrappers.so"
    if [ -f "$SOURCE_SO" ]; then
        cp "$SOURCE_SO" "$EXTRACT_DIR/"
        echo "✅ Realm 原生库替换成功！"
    else
        echo "⚠️ 警告: 未找到 $SOURCE_SO"
    fi
    rm -rf package "$REALM_TGZ"
else
    echo "⚠️ 警告: 下载 Realm 包失败，请检查网络。"
fi

echo "==> 5. 从仓库下载 libSilkCodec.so ..."
SILK_URL="https://raw.githubusercontent.com/xiaoxiaoyu-miao/MeowRVC/napcat/libSilkCodec.so"
wget -O "$EXTRACT_DIR/libSilkCodec.so" "$SILK_URL"
if [ -f "$EXTRACT_DIR/libSilkCodec.so" ]; then
    echo "✅ libSilkCodec.so 下载成功！"
else
    echo "⚠️ 警告: 下载 libSilkCodec.so 失败，请手动放置。"
fi

echo "==> 6. 生成 runtimeconfig.json..."
cat > "$EXTRACT_DIR/Lagrange.OneBot.runtimeconfig.json" << 'INNEREOF'
{
  "runtimeOptions": {
    "tfm": "net10.0",
    "framework": {
      "name": "Microsoft.NETCore.App",
      "version": "10.0.0"
    }
  }
}
INNEREOF

echo "==> 7. 创建启动脚本 (start.sh)..."
cat > "$WORK_DIR/start.sh" << 'INNEREOF'
#!/data/data/com.termux/files/usr/bin/bash
cd "$HOME/rvcqq/111111111111"
dotnet ../extracted_files/Lagrange.OneBot.dll
INNEREOF
chmod +x "$WORK_DIR/start.sh"

echo "==> 8. 创建全局命令 'rvc'..."
cat > "/data/data/com.termux/files/usr/bin/rvc" << 'INNEREOF'
#!/data/data/com.termux/files/usr/bin/bash
cd "$HOME/rvcqq/111111111111" && dotnet ../extracted_files/Lagrange.OneBot.dll
INNEREOF
chmod +x "/data/data/com.termux/files/usr/bin/rvc"

echo "============================================"
echo "✅ 部署完成！"
echo "📁 工作目录: $WORK_DIR"
echo "🚀 现在你可以直接输入 'rvc' 来启动机器人。"
echo "============================================"
