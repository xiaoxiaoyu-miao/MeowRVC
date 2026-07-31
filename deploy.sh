#!/data/data/com.termux/files/usr/bin/bash

# --- 配置区域 ---
WORK_DIR="$HOME/rvcqq"
EXTRACT_DIR="$WORK_DIR/extracted_files"
CONFIG_DIR="$WORK_DIR/111111111111"
RAW_BASE="https://raw.githubusercontent.com/xiaoxiaoyu-miao/MeowRVC/napcat"

echo "==> 1. 创建工作目录..."
mkdir -p "$WORK_DIR"
cd "$WORK_DIR" || exit 1

echo "==> 2. 下载 Lagrange 可执行文件..."
wget -O "$WORK_DIR/Lagrange" "$RAW_BASE/Lagrange"
chmod +x "$WORK_DIR/Lagrange"

echo "==> 3. 使用 sfextract 解包 Lagrange..."
sfextract -o "$EXTRACT_DIR" "$WORK_DIR/Lagrange"

echo "==> 4. 下载配置文件..."
wget -O "$WORK_DIR/appsettings.json" "$RAW_BASE/appsettings.json"
wget -O "$WORK_DIR/device.json" "$RAW_BASE/device.json"
wget -O "$WORK_DIR/keystore.json" "$RAW_BASE/keystore.json"

echo "==> 5. 创建配置目录并移动配置文件..."
mkdir -p "$CONFIG_DIR"
mv "$WORK_DIR"/appsettings.json "$WORK_DIR"/device.json "$WORK_DIR"/keystore.json "$CONFIG_DIR"/ 2>/dev/null || true

echo "==> 6. 下载并替换 Realm 原生库 (20.1.0)..."
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

echo "==> 7. 下载 libSilkCodec.so..."
wget -O "$EXTRACT_DIR/libSilkCodec.so" "$RAW_BASE/libSilkCodec.so"
if [ -f "$EXTRACT_DIR/libSilkCodec.so" ]; then
    echo "✅ libSilkCodec.so 下载成功！"
else
    echo "⚠️ 警告: 下载 libSilkCodec.so 失败"
fi

echo "==> 8. 生成 runtimeconfig.json..."
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

echo "==> 9. 创建启动脚本 (start.sh)..."
cat > "$WORK_DIR/start.sh" << 'EOF'
#!/data/data/com.termux/files/usr/bin/bash
cd "$HOME/rvcqq/111111111111"
dotnet ../extracted_files/Lagrange.OneBot.dll
EOF
chmod +x "$WORK_DIR/start.sh"

echo "==> 10. 创建全局命令 'rvc'..."
cat > "/data/data/com.termux/files/usr/bin/rvc" << 'EOF'
#!/data/data/com.termux/files/usr/bin/bash
cd "$HOME/rvcqq/111111111111" && dotnet ../extracted_files/Lagrange.OneBot.dll
EOF
chmod +x "/data/data/com.termux/files/usr/bin/rvc"

echo "============================================"
echo "✅ 部署完成！"
echo "📁 工作目录: $WORK_DIR"
echo "🚀 现在你可以直接输入 'rvc' 来启动机器人。"
echo "============================================"
