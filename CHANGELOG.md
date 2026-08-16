# Changelog

本项目的所有显著变更记录于此。

## [Unreleased]

### 新增
- 「本地 Harness」重新设计为 **Linux VM 隔离实例**（与局域网完全隔离）：
  - 端口约定：局域网 3080 / 本地（VM）3090
  - `vm/harness-vm-setup.sh`：VM 内一键部署完整原版 Harness 生态（独立 `~/.dsh`，可选 dsh-lan 插件 + systemd 自启）
  - `vm/README.md`：WSL2 / VirtualBox / VMware 三种虚拟机的端口转发配置
  - App 服务器列表常驻「🐧 本地 Harness（Linux VM）」入口；连接失败时给出 VM→转发→隧道三步引导

## [1.1.0] - 2026-08-16

### 新增
- 聊天页对标网页端交互：
  - 会话标题实时显示（`session/title` 事件 + title projection）
  - 思考（reasoning）默认折叠行，显示一行摘要，流式时跟随最新行
  - 上下文注入折叠披露行
  - 工具调用卡片执行状态（执行中 spinner / ✓ 完成 / ✗ 错误）
  - 输入区统计条粘底（输入/输出/缓存命中/首 token/速度 + 上下文压力条）
  - 运行状态行（当前执行工具名）、排队消息数
  - composer 座位：➕ 命令启动器、模型 chip、安全程度 chip
  - 消息长按复制
- `scripts/dsh-adb-tunnel.ps1`：本地 Harness 隧道保持器（设备重连自动恢复 adb reverse）

## [1.0.0] - 2026-08-16

### 新增
- 三 Tab 结构：会话 / 详情 / 设置
- 会话页：工作区分组 + 搜索 + 新增工作区 + 当前工作区新建会话
- 详情页：DeepSeek 官方消耗卡（`deepseek/balance` RPC 经 PC 端凭证代查）、全站累计、运行中项目统计卡、图表、进行中任务
- 设置页：主题 / 安全程度 / 工作环境（局域网网页端 / 本机 Harness）
- 聊天页：模型切换（`session.models`/`session.selectModel`）、安全程度、命令菜单、usage 显示
- 配套插件 `dsh-lan`：局域网开放 + `deepseek/balance` RPC（发布至 GitHub）

## [0.2.0] - 2026-08-15

### 变更
- **原生 UI 替代 WebView 套壳**：会话列表 + 流式聊天（Typert/legacy RPC + WebSocket mux 下行流）
- 逆向产出 `docs/wire-protocol.md`（RPC 信封/方法表/下行帧契约）
- 局域网扫描（`/24` 网段 3080 端口并发探测）

## [0.1.0] - 2026-08-14

### 新增
- 初版：WebView 套壳加载 harness Web UI
- 服务器连接管理（手动录入 + 局域网扫描）
- 明文 HTTP / 自签名 HTTPS 支持

## 语义化版本

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。
