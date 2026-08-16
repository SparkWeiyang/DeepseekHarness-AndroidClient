# DeepSeek Harness Android Client（安卓端）

[DeepSeek Harness（DSH）](https://github.com/deepseek-ai/deepseek-harness) 的**原生安卓客户端**，包名 `com.spark.harness`。通过 DSH Web 的 RPC 协议（`POST /api/<method>` + WebSocket `/api/events.mux` 下行流）与 PC 端 harness 通信，**完全原生渲染（无 WebView 套壳）**，支持手机与大屏（平板/折叠屏）设备。

> 配套插件：[dsh-lan](https://github.com/SparkWeiyang/dsh-lan) —— 让 PC 端 harness 局域网开放（绑定 `0.0.0.0`）并提供 `deepseek/balance` 余额查询端点。

## 功能

### 三页结构（连接服务器后）

- **会话**：与网页端一致的工作区分组（`workspace.list`）+ 会话列表（`session.list`）；工作区切换 chips、会话全文搜索（`session.search`）、新增工作区（`workspace.create`）、在当前工作区新建会话（`session.create`）。
- **详情**：DeepSeek 官方消耗卡（经 PC 端 `deepseek/balance` 端点代查余额/赠金/充值，**密钥在 PC 端、永不下发手机**）+ 全站累计（输入/输出 tokens、缓存命中率）+ **每个运行中项目**的统计卡（缓存命中率、首 token、生成速度、上下文压力条）+ 图表 + 进行中任务（goal 目标 + todos 清单）。
- **设置**：与网页端同源的核心设置——主题（`ui-theme`）、安全程度（`permission.defaultPreset`）；「工作环境」：当前服务器 + 切换（局域网网页端 / 本机 Harness）。

### 聊天页（对标网页端）

- 流式输出（`assistant/chunk` 增量 + `assistant/message` 定稿）、Markdown/代码块渲染
- **思考（reasoning）折叠行**：默认折叠显示一行摘要（流式时跟随最新行），点击展开
- **上下文注入折叠披露行**（AGENTS.md、技能内容等非用户消息）
- **工具调用卡片**：执行中 spinner → ✓ 完成 / ✗ 错误，参数/结果可展开
- **输入区统计条（粘底）**：输入/输出 tok · 缓存命中% · 首 token 平均 · tok/s + 上下文压力条（实时来自 projections）
- **运行状态行**：「⚙ 正在执行: `<工具名>`」实时显示；排队消息数（QueueDock）
- **composer 座位**：➕ 命令启动器（内置 `/goal` `/compact` `/feedback` + `skill.list` 技能）、模型 chip（`session.models`/`session.selectModel`）、安全程度 chip、发送/停止
- 每条回复显示 **usage**（输入/缓存读/缓存写/输出 tokens）；用户/助手消息**长按复制**

### 连接方式

- 手动录入（名称/主机/端口/HTTPS）
- **局域网扫描**：并发探测当前 `/24` 网段 3080 端口，一键发现 PC harness
- **本机 Harness**：`adb reverse tcp:3080 tcp:3080` 直连 PC（不经局域网），配套 PC 端隧道保持脚本 `scripts/dsh-adb-tunnel.ps1`
- 明文 HTTP 与自签名 HTTPS（信任用户证书）均可

## 技术栈

| 项 | 选择 |
|---|---|
| 语言 | Kotlin 2.2.10 / Jetpack Compose（BOM 2025.06.01）+ Material 3 |
| 构建 | Gradle 8.13 / AGP 8.13.2，`minSdk 26` / `targetSdk 35` / `compileSdk 36` |
| 网络 | OkHttp 4.12（HTTP RPC + WebSocket）+ org.json |
| 持久化 | SharedPreferences + org.json（零额外依赖） |
| 协议文档 | [`docs/wire-protocol.md`](docs/wire-protocol.md)（DSH RPC 信封/方法表/下行帧契约，744 行逆向笔记） |

## 目录结构

```
app/src/main/java/com/spark/harness/
├── MainActivity.kt          # 入口
├── data/                    # ServerConfig / ServerRepository / ChatModels
├── net/
│   ├── HarnessApiClient.kt  # RPC 信封 + WebSocket mux 传输层
│   ├── DeepSeekApi.kt       # deepseek/balance 端点（PC 端凭证代查余额）
│   └── LanScanner.kt        # 局域网扫描（无 WifiManager，零额外权限）
└── ui/
    ├── App.kt               # 服务器列表 ↔ 主壳导航
    ├── MainShell.kt         # 会话 / 详情 / 设置 三 Tab
    ├── SessionsTab.kt       # 工作区 + 会话 + 搜索
    ├── DetailsTab.kt        # 消耗卡 + 统计卡 + 图表 + 进行中任务
    ├── SettingsTab.kt       # 主题 / 安全程度 / 工作环境
    ├── ChatScreen.kt        # 流式聊天（对标网页端 composer）
    ├── ChatController.kt    # 事件折叠状态机 + 会话操作
    ├── Markdown.kt          # 极简 Markdown 渲染
    └── Stats.kt             # projections → 统计卡数据
```

## 构建

```powershell
# 需要 JDK 17+（推荐 Android Studio 自带 JBR 21）与 Android SDK
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = 'C:\Users\spark\AppData\Local\Android\Sdk'
.\gradlew.bat assembleDebug      # 产物 app/build/outputs/apk/debug/app-debug.apk
```

也可直接用 Android Studio 打开本目录构建。

## 安装

```powershell
adb install app\build\outputs\apk\debug\app-debug.apk
```

## 使用

### 前置：PC 端 harness

```sh
# 方式 A：安装了 dsh-lan 插件（推荐，局域网开放 + 余额端点）
dsh web

# 方式 B：未装插件，手动绑定局域网
dsh web --host 0.0.0.0 --port 3080
```

### 手机端连接

1. **局域网**：App 内点 🔍 扫描（自动发现 `192.168.x.x:3080`），或手动添加主机/端口。
2. **本地（USB/无线调试直连）**：PC 端运行 [`scripts/dsh-adb-tunnel.ps1`](scripts/dsh-adb-tunnel.ps1) 保持 `adb reverse`，App 内选择「本机 Harness (127.0.0.1:3080)」。
3. 连接后：会话 Tab 选工作区/搜索/新建会话 → 进入聊天页发消息。

## 安全说明

DSH 的 Web carrier **没有鉴权层**（信任栅栏只是可达性策略）。请仅在可信网络暴露 harness；客户端本地只保存服务器地址，不保存任何密钥。详见 [SECURITY.md](SECURITY.md)。

## 文档

- [CHANGELOG.md](CHANGELOG.md) — 版本历史
- [SECURITY.md](SECURITY.md) — 安全说明
- [CONTRIBUTING.md](CONTRIBUTING.md) — 贡献指南
- [docs/wire-protocol.md](docs/wire-protocol.md) — DSH RPC 协议逆向笔记

## License

[MIT](LICENSE)
