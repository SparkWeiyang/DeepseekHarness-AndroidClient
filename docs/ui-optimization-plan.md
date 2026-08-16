# HarnessClient UI 优化方案

> 基于对全部 19 个 Kotlin 文件（约 3300 行）与 res 资源的分组评审，2026-08 定稿。
> 已核实项：`SessionStats.inputTokens` 为计算属性（`data/ChatModels.kt:73`），全站累计统计无 bug；`MainActivity` 已启用 edge-to-edge；`network_security_config.xml` 已信任用户证书。

## 现状量化

| 指标 | 数值 | 含义 |
|---|---|---|
| Kotlin 总量 | ~3300 行 / 19 文件 | 规模适中，单文件最大 686 行 |
| 硬编码 `.dp` 字面量 | 145 处 | 无间距/尺寸体系 |
| `stringResource` 调用 | 0 处 | 全部中文硬编码，不可本地化 |
| `LazyColumn` | 8 处 | 多数无稳定 key |
| `isSystemInDarkTheme` | 0 处 | 无深色模式支持 |
| 直接 `Color()` 调用 | 2 处 | 颜色基本靠 Material 默认值，无品牌色体系 |
| 导航库 | 无 | 手写 `sealed interface Screen` 状态机 |

---

## P0 — 体验明显受损，优先修复

### P0-1 流式输出卡顿（聊天页性能核心）

**根因**（`ui/Markdown.kt` + `ui/ChatController.kt`）：
- `remember(markdown)` 按全文缓存：每个 text-delta 都换新全文 → 整段 Markdown 全量重解析重渲染，长输出下累计 O(n²)。
- `parseBlocks` 内 3 个 `Regex(...)` 在循环里**每行每次**重新编译（~`Markdown.kt:111/116/126`）。
- `ChatController` 用 `text + text` / `reasoning +=` 逐 chunk 字符串拼接，每 chunk 分配整串。

**修复**：
1. 正则提为顶层 `private val BULLET_RE = Regex(...)`（3 处，零风险）。
2. `ChatController.onFrame` 做 50ms 合并节流（Channel + conflate 或时间窗批量 flush，turn/end 前强制 flush）。
3. 助手文本改 `StringBuilder` 尾部追加（或直接持有 `StringBuilder` 状态）。
4. `MarkdownText` 只对「最后一个不稳定段落」重解析：解析结果按 chunk 号缓存，历史段落只随 UI 读取，不参与重解析。

**改动量**：~60 行 · **风险**：中（需保证帧序与 flush 正确）

### P0-2 自动滚动双重 bug

**根因**（`ui/ChatScreen.kt:116-120`）：滚动 key 用 `items.size + currentTool`：
- 流式文本增长不改变 `items.size` → **流式输出根本不跟随滚动**；
- 工具开始/结束使 `currentTool` 变化 → **强制跳底**，用户上翻阅读被强拉。

**修复**：删除该 key；改为 `LaunchedEffect(listState)` 监听，仅当用户已接近底部（`isScrolledToEnd`）时才 `animateScrollToItem`。

**改动量**：~15 行 · **风险**：低

### P0-3 聊天列表无稳定 key

**根因**（`ChatScreen.kt:192`）：`itemsIndexed(controller.items)` 无 key；历史重载 `clear()+addAll` 整体重建闪烁，`remember(item.callId)` 工具卡可能错位。

**修复**：给 `ChatItem` 增加稳定 id（`Tool.callId`、`User/Assistant` 用类型+序号），`key = { it.stableId() }`，并关闭 LazyColumn 默认 item 动画（`animateItem` 禁用或 `key` 稳定后自然收敛）。

**改动量**：~20 行 · **风险**：低

### P0-4 Tab 切换销毁重建

**根因**（`ui/MainShell.kt:63-67`）：`when(tab)` 切换即销毁子 Tab；滚动位置、搜索词、已加载数据全丢；每次切换重新触发 `SessionsTab/DetailsTab` 的 `LaunchedEffect(Unit)` 网络请求。

**修复**：`tab` 改 `rememberSaveable`；子 Tab 内容包 `SaveableStateHolder.SaveableStateProvider(tab)` 保持状态；或将三 Tab 改为一并组合、用可见性切换（数据请求移到 MainShell 层持有）。

**改动量**：~25 行 · **风险**：低

### P0-5 详情页「实时统计」名不副实

**根因**（`ui/DetailsTab.kt:94/97`）：`LaunchedEffect(Unit)` 仅进页加载一次，刷新全靠手点。

**修复**：加 3~5s 轮询（`LaunchedEffect` + `delay` 循环，仅前台时轮询、`onOpen` 离开时取消）；余额查询并入轮询（可降频到 30s）。顺带把 `fetchBalance` 包 `withContext(Dispatchers.IO)`。

**改动量**：~20 行 · **风险**：低

### P0-6 导航返回栈与状态丢失

**根因**（`ui/App.kt:21-27`）：手写 `Screen` 状态机，`remember` 非 `rememberSaveable`（进程重建丢状态）；无 `BackHandler` —— 系统返回键在 Edit/Shell 直接退出应用而非回列表。

**修复**：
- 短期（不引依赖）：`BackHandler` 按当前 Screen 回退；`rememberSaveable` 保存 screen 标识（ServerConfig 存仓库可重取，只存 id）。
- 中期：迁移 Navigation Compose（`navigation-compose`，URL 路由 `list/edit/{id}/shell/{id}`），约半天工作量。

**改动量**：短期 ~30 行 · **风险**：低

### P0-7 局域网扫描线程泄漏 + 网卡误判

**根因**（`net/LanScanner.kt` + `ui/LanScanDialog.kt`）：
- 64 线程池并发探测 254 个 IP，阻塞 `get()` 等全部完成；离开页面后线程继续跑，不可取消。
- `localIpv4()` 用 `firstOrNull` 取首个 IPv4，可能选中 Docker/VMware 虚拟网卡扫错网段。

**修复**：
1. 改 `CoroutineScope` + `withTimeout` 或按批次并发（如 32 并发 + 可取消），结果按 IP 排序后逐条 emit 到 UI（边扫边显示）。
2. `localIpv4()` 加网卡过滤：跳过 `172.17.*`（Docker）、`192.168.56.*`/`192.168.99.*`（VirtualBox）、`169.254.*`，优先取 `192.168./10./172.16-31.` 的物理网卡。
3. 扫描中「取消」真正中止协程。

**改动量**：~30 行 · **风险**：中（改并发模型）

---

## P1 — 值得做，投入可控

### P1-1 删除服务器无二次确认（`App.kt:54`）
误触即丢配置。加 `AlertDialog` 确认（显示名称+主机）。~15 行，低风险。

### P1-2 表单校验与键盘体验（`ui/ServerEditScreen.kt`）
- 校验失败仅禁用保存键、无任何说明（~`:82`）：加 `isError` + `supportingText`。
- 主机校验太弱（`~:55` 仅非空）：加 IP/域名正则校验。
- 无 `imePadding()`、无 `imeAction`：补上；Manifest 加 `windowSoftInputMode="adjustResize"`。
- 表单状态 `remember` → `rememberSaveable`，旋转不丢输入。
~30 行，低风险。

### P1-3 Markdown 链接不可点、代码块无复制（`ui/Markdown.kt:234-236`）
- 链接改 `LinkAnnotation.Url` + `ClickableText`（点击调起浏览器，需确认局域网链接策略）。
- 代码块加复制按钮（顶部小按钮 + Clipboard）。
- 修内联代码背景色用错 token（`inlineAnnotated` 把 `onSurfaceVariant` 当背景色，对比度异常）。
~30 行，低风险。

### P1-4 大屏适配（平板/折叠屏）
现状仅 `widthIn(max=560/720/760.dp)` 硬编码。方案：引入 `WindowSizeClass`（`material3-window-size-class` 依赖，或手算 `BoxWithConstraints`）；Expanded 宽度下聊天列放宽、三 Tab 改 NavigationRail（侧栏）释放纵向空间。

~40 行，低-中风险。

### P1-5 主题系统：深色模式 + Design Token
- `themes.xml` 父主题改 Material3 DayNight；建 `ui/theme/`（Color.kt / Theme.kt / Type.kt）。
- 支持「浅色/深色/跟随系统」三选（现 `SettingsTab` 的 `ui-theme` 偏好是服务端的，本地主题可加独立偏好）。
- 145 处硬编码 dp 逐步收敛为 spacing token（8.dp 栅格）；硬编码红绿色（如 `ChatScreen.kt:646`、`DetailsTab.kt:313` 状态色）改语义色。
分 3 步落地：先建 theme 包 → 换全局色 → 分批替换间距。~150 行（分批），低风险。

### P1-6 无障碍
- `combinedClickable(onClick={})` 空回调丢 ripple：补 `indication` 或改用可点容器。
- 展开箭头 `contentDescription=null`：补语义。
- 长按复制无提示：改为显式复制按钮或复制后 Toast/Snackbar。
- emoji 状态指示（⚙/✓/✗）补 `contentDescription` 或改用图标+语义。
~20 行，低风险。

### P1-7 错误/空/重试态补齐
- 聊天仅 `error` 单行顶栏：`loadHistory` 失败加重试按钮。
- 会话搜索失败静默（`SessionsTab:118`）：给错误提示。
- 空态文案错乱（`SessionsTab:299-301`）：按「无会话/搜索无结果/加载失败」三种状态区分文案。
~25 行，低风险。

### P1-8 设置页健壮性（`ui/SettingsTab.kt`）
- 服务端返回 null 时主题/权限整卡消失（`:158/:180`）：null 时显示「未配置」而非整卡消失。
- `RadioButton(onClick=null)` 丢无障碍语义（`:171/:193`）：禁用态用 `enabled=false` 正确表达。
- `updateSetting` 忽略失败无回滚（`:86-95`）：失败回滚本地乐观值 + Snackbar。
~25 行，低风险。

### P1-9 会话页小修（`ui/SessionsTab.kt`）
- `SimpleDateFormat` 每行 new（`:401`）：提为常量或 `ThreadLocal`。
- 列表加稳定 key；错误态仅 `sessions` 为空时显示（`:204`）：有数据但刷新失败时也提示。
~10 行，低风险。

### P1-10 详情页计算与语义（`ui/DetailsTab.kt`）
- totals 每次重组重算（`:107-118`）：`remember(rows)` 派生。
- `balanceState` 魔法数字（`:73`）：改 enum。
- 压力条/状态条无 `semantics`：加 progress semantics。
~15 行，低风险。

---

## P2 — 锦上添花

| # | 事项 | 说明 | 改动量 |
|---|---|---|---|
| P2-1 | 字符串本地化 | 全部文案抽 `strings.xml`（0 → ~150 条）；当前仅 `app_name` | 机械替换，~1 天 |
| P2-2 | 状态机收敛 | `ChatController` 10+ 散落字段 → `reduce(event)` 单一入口 + 不可变快照，便于乱序防护与测试 | ~120 行，中风险 |
| P2-3 | 自签名证书开关 | 服务器编辑加「信任自签名证书」显式开关（现依赖全局用户证书信任） | ~20 行 |
| P2-4 | cleartext 收窄 | Manifest `usesCleartextTraffic=true` 与 networkSecurityConfig 重复，清理冗余 | ~5 行 |
| P2-5 | 代码块语法高亮 | 接入轻量高亮（正则级即可） | ~40 行 |
| P2-6 | Android 12+ Splash | `core-splashscreen` 或 `windowSplashScreen*` 主题，品牌化启动页 | ~15 行 |
| P2-7 | 图表升级 | 统计趋势从 Box/Bar 升级为 Canvas 折线（当前无 Canvas 用法） | ~80 行 |
| P2-8 | 动态取色 | Material You 动态配色（Android 12+），浅/深/跟随系统之外的「动态」选项 | ~15 行 |
| P2-9 | 会话长按菜单 | 会话项长按：重命名/删除/置顶（视 RPC 能力） | 视协议 |

---

## 实施路线图

### 阶段 1：性能与正确性（先让流式聊天不卡、滚动正确）
P0-1 流式节流 + 正则提取 → P0-2 自动滚动 → P0-3 列表 key → P0-6 返回栈
**验收**：长文流式输出流畅；上翻阅读不被强拉；旋转不丢状态；返回键行为正确。
**预估**：~130 行，1 天。

### 阶段 2：状态保持与关键交互
P0-4 Tab 保持 → P0-5 详情轮询 → P0-7 扫描治理 → P1-1/P1-2 连接流程
**验收**：Tab 切换零请求零闪烁；详情页数字自行跳动；扫描可取消且不误扫网卡。
**预估**：~140 行，1 天。

### 阶段 3：设计系统与打磨
P1-5 主题/Token（含 P2-8 动态色）→ P1-4 大屏 → P1-3/P1-6/P1-7/P1-8/P1-9/P1-10 → P2-1 本地化 → P2-2 状态机 → 其余 P2 按需
**验收**：深色模式三态可用；平板双栏/侧栏布局；无障碍扫描（TalkBack）通过基础检查；文案可本地化。
**预估**：3~4 天，可穿插发布。

## 验证清单（每阶段）
- [ ] `.\gradlew.bat assembleDebug` 构建通过
- [ ] 真机/模拟器流式长对话 20 分钟不卡顿（Profiler：重组频率 < 2 次/chunk 窗口）
- [ ] 旋转/进程重建后 Tab、滚动、表单状态保持
- [ ] 平板（或模拟器 800dp 宽）布局正常
- [ ] TalkBack 播报关键控件
