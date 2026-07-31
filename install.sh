#!/data/data/com.termux/files/usr/bin/bash

echo "==> 正在更新软件源并安装 wget..."
pkg update -y && pkg upgrade -y
pkg install wget -y

echo "==> 正在安装 .NET SDK 10.0..."
pkg install dotnet-sdk-10.0 -y

echo "==> 正在安装 sfextract 工具..."
dotnet tool install --global sfextract

echo "============================================"
echo "✅ 环境初始化完成！"
echo "⚠️  请重启 Termux 或执行 'source ~/.bashrc' 后再运行第二个脚本。"
echo "============================================"
