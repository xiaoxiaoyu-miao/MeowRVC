#!/data/data/com.termux/files/usr/bin/bash

# --- 配置区域 ---
WORK_DIR="$HOME/rvcqq"
EXTRACT_DIR="$WORK_DIR/extracted_files"
RAW_BASE="https://raw.githubusercontent.com/xiaoxiaoyu-miao/MeowRVC/napcat"

echo "==> 1. 创建工作目录..."
mkdir -p "$WORK_DIR"
cd "$WORK_DIR" || exit 1

echo "==> 2. 下载并解压 Lagrange.OneBot 官方发布包..."
RELEASE_URL="https://github.com/LagrangeDev/Lagrange.Core/releases/download/nightly/Lagrange.OneBot_linux-arm64_net9.0_SelfContained.tar.gz"
TARBALL="Lagrange.OneBot_linux-arm64_net9.0_SelfContained.tar.gz"
wget -O "$TARBALL" "$RELEASE_URL"
tar -xzf "$TARBALL" -C "$WORK_DIR"
mv "$WORK_DIR/Lagrange.OneBot/bin/Release/net9.0/linux-arm64/publish/Lagrange.OneBot" "$WORK_DIR/Lagrange"
chmod +x "$WORK_DIR/Lagrange"
rm "$TARBALL"

echo "==> 3. 使用 sfextract 解包 Lagrange（启用 roll forward）..."
export DOTNET_ROLL_FORWARD=Major
sfextract -o "$EXTRACT_DIR" "$WORK_DIR/Lagrange"

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

echo "==> 5. 下载 libSilkCodec.so..."
wget -O "$EXTRACT_DIR/libSilkCodec.so" "$RAW_BASE/libSilkCodec.so"
if [ -f "$EXTRACT_DIR/libSilkCodec.so" ]; then
    echo "✅ libSilkCodec.so 下载成功！"
else
    echo "⚠️ 警告: 下载 libSilkCodec.so 失败"
fi

echo "==> 6. 生成 runtimeconfig.json..."
cat > "$EXTRACT_DIR/Lagrange.OneBot.runtimeconfig.json" << 'EOF'
{
  "runtimeOptions": {
    "tfm": "net10.0",
    "framework": {
      "name": "Microsoft.NETCore.App",
      "version": "10.0.0"
    }
  }
}
EOF

echo "==> 7. 创建全局命令 'rvc'..."
cat > "/data/data/com.termux/files/usr/bin/rvc" << 'EOF'
#!/data/data/com.termux/files/usr/bin/bash
cd "$HOME/rvcqq/111111111111" && dotnet ../extracted_files/Lagrange.OneBot.dll
EOF
chmod +x "/data/data/com.termux/files/usr/bin/rvc"

echo "============================================"
echo "✅ 部署完成！"
echo "📁 工作目录: $WORK_DIR"
echo "📌 请将你的配置文件（appsettings.json, device.json, keystore.json）放入 $HOME/rvcqq/111111111111/"
echo "📌 数据库目录也请放到 $HOME/rvcqq/111111111111/"
echo "🚀 配置完成后，直接输入 'rvc' 启动机器人。"
echo "============================================"
