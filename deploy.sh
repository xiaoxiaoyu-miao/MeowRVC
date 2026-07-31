#!/data/data/com.termux/files/usr/bin/bash

# --- 配置区域 ---
WORK_DIR="$HOME/rvcqq"
EXTRACT_DIR="$WORK_DIR/extracted_files"
RAW_BASE="https://raw.githubusercontent.com/xiaoxiaoyu-miao/MeowRVC/napcat"

# --- 询问 QQ 号 ---
while true; do
    read -p "请输入你的 QQ 号: " QQ
    if [[ "$QQ" =~ ^[0-9]{5,12}$ ]]; then
        break
    else
        echo "❌ QQ 号格式不正确，请输入 5~12 位数字"
    fi
done
CONFIG_DIR="$WORK_DIR/$QQ"

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

echo "==> 7. 创建配置目录并生成 appsettings.json..."
mkdir -p "$CONFIG_DIR"
cat > "$CONFIG_DIR/appsettings.json" << EOF
{
  "\$schema": "https://raw.githubusercontent.com/LagrangeDev/Lagrange.Core/master/Lagrange.OneBot/Resources/appsettings_schema.json",
  "Logging": {
    "LogLevel": {
      "Default": "Information",
      "Microsoft": "Warning",
      "Microsoft.Hosting.Lifetime": "Information"
    }
  },
  "SignServerUrl": "https://39038.928100.xyz",
  "SignProxyUrl": "",
  "MusicSignServerUrl": "",
  "Account": {
    "Uin": $QQ,
    "Password": "",
    "Protocol": "Linux",
    "AutoReconnect": true,
    "GetOptimumServer": true
  },
  "Message": {
    "IgnoreSelf": true,
    "StringPost": false
  },
  "QrCode": {
    "ConsoleCompatibilityMode": false
  },
  "Implementations": [
    {
      "Type": "ReverseWebSocket",
      "Host": "localhost",
      "Port": 2536,
      "Suffix": "/OneBotv11",
      "ReconnectInterval": 5000,
      "HeartBeatInterval": 5000,
      "AccessToken": ""
    }
  ]
}
EOF
echo "✅ 已生成: $CONFIG_DIR/appsettings.json (Uin=$QQ, ReverseWebSocket:2536)"

echo "==> 8. 创建全局命令 'rvc'..."
cat > "/data/data/com.termux/files/usr/bin/rvc" << EOF
#!/data/data/com.termux/files/usr/bin/bash
cd "$HOME/rvcqq/$QQ" && dotnet ../extracted_files/Lagrange.OneBot.dll
EOF
chmod +x "/data/data/com.termux/files/usr/bin/rvc"

echo "============================================"
echo "✅ 部署完成！"
echo ""
echo "📌 配置目录: $CONFIG_DIR"
echo "   - appsettings.json (已自动生成)"
echo "   - device.json / keystore.json (首次启动自动生成)"
echo "   - lagrange-$QQ-db (数据库目录，首次启动自动生成)"
echo ""
echo "🚀 启动机器人: rvc"
echo "   首次运行按提示扫码登录即可"
echo "============================================"
