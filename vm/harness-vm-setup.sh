#!/usr/bin/env bash
#
# harness-vm-setup.sh — 在 Linux 虚拟机里部署一套完整、隔离的 DeepSeek Harness
#
# 与主机上的 Harness 完全隔离：
#   - 独立的 DSH_HOME（~/.dsh）→ 会话/凭证/设置/插件互不相通
#   - 独立端口（默认 3090，主机局域网用 3080）
# 本脚本只做「原版生态」部署：npx 原版 dsh + 可选 dsh-lan 插件 + 可选 systemd 服务。
#
# 用法:
#   curl -fsSL https://raw.githubusercontent.com/SparkWeiyang/DeepseekHarness-AndroidClient/main/vm/harness-vm-setup.sh | bash
#   或下载后: bash harness-vm-setup.sh
#
# 环境变量:
#   PORT=3090          VM 内监听端口（默认 3090）
#   BIND_HOST=0.0.0.0  绑定地址（默认 0.0.0.0，供 VM 端口转发）
#   INSTALL_LAN=1      是否安装 dsh-lan 插件（1=装，0=不装，默认 1）
#   INSTALL_SERVICE=1  是否安装 systemd 自启服务（1=装，0=不装，默认 1）
set -euo pipefail

PORT="${PORT:-3090}"
BIND_HOST="${BIND_HOST:-0.0.0.0}"
INSTALL_LAN="${INSTALL_LAN:-1}"
INSTALL_SERVICE="${INSTALL_SERVICE:-1}"

echo "==> [1/4] 检查 Node.js"
if ! command -v node >/dev/null 2>&1; then
  echo "    未找到 Node.js。请先安装 Node 18+："
  echo "      Debian/Ubuntu: curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash - && sudo apt-get install -y nodejs"
  echo "      Arch:          sudo pacman -S nodejs npm"
  exit 1
fi
echo "    node $(node --version)"

echo "==> [2/4] 拉取原版 DeepSeek Harness（npx 按需安装，DSH_HOME=$HOME/.dsh）"
npx --yes @deepseek-ai/dsh --version

echo "==> [3/4] dsh-lan 插件（局域网开放 + deepseek/balance 端点）"
if [ "$INSTALL_LAN" = "1" ]; then
  PLUGIN_DIR="$HOME/dsh-plugins/dsh-lan"
  mkdir -p "$(dirname "$PLUGIN_DIR")"
  if [ -d "$PLUGIN_DIR/.git" ]; then
    (cd "$PLUGIN_DIR" && git pull --ff-only)
  else
    git clone --depth 1 https://github.com/SparkWeiyang/dsh-lan "$PLUGIN_DIR"
  fi
  # 首次需初始化 web profile，然后安装插件并加入 bundles
  npx --yes @deepseek-ai/dsh --profile web --help >/dev/null 2>&1 || true
  if npx --yes @deepseek-ai/dsh plugin --profile web add "file:$PLUGIN_DIR" >/dev/null 2>&1; then
    echo "    插件已加入 profile 依赖"
  else
    echo "    [警告] pnpm 安装失败，请手动执行: npx @deepseek-ai/dsh plugin --profile web add file:$PLUGIN_DIR"
  fi
  # 确保 bundle 列表包含 dsh-lan（幂等）
  PROFILE_PKG="$HOME/.dsh/profiles/web/package.json"
  if [ -f "$PROFILE_PKG" ]; then
    node -e '
      const fs=require("fs");const p=process.argv[1];const j=JSON.parse(fs.readFileSync(p,"utf8"));
      const b=j.dsh?.profile?.bundles??[];
      if(!b.includes("dsh-lan")){b.push("dsh-lan");fs.writeFileSync(p,JSON.stringify(j,null,2)+"\n");}
    ' "$PROFILE_PKG"
  fi
else
  echo "    跳过（INSTALL_LAN=0）"
fi

echo "==> [4/4] systemd 自启服务"
if [ "$INSTALL_SERVICE" = "1" ] && command -v systemctl >/dev/null 2>&1; then
  NPX="$(command -v npx)"
  sudo tee /etc/systemd/system/dsh-web.service >/dev/null <<EOF
[Unit]
Description=DeepSeek Harness Web (VM local instance, port ${PORT})
After=network.target

[Service]
User=${USER}
Environment=HOME=${HOME}
ExecStart=${NPX} --yes @deepseek-ai/dsh web --host ${BIND_HOST} --port ${PORT}
Restart=on-failure
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF
  sudo systemctl daemon-reload
  sudo systemctl enable --now dsh-web
  echo "    服务已启动（sudo systemctl status dsh-web 查看）"
else
  echo "    跳过（无 systemd 或 INSTALL_SERVICE=0）"
fi

echo
echo "✅ 完成。VM 内 Harness 监听 ${BIND_HOST}:${PORT}（独立 HOME，与主机隔离）"
echo "   下一步：按 vm/README.md 配置 VM 端口转发（${PORT} → 主机 127.0.0.1:${PORT}），"
echo "   然后手机 App 用「本地 Harness（Linux VM）」连接。"
