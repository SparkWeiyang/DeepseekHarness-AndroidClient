# DSH 原生客户端 Wire 协议契约（Kotlin / OkHttp 实现参考）

> 本文档从已安装的 `@deepseek-ai/dsh-host-apiproxy`（+ `dsh-session` / `dsh-llm` / `dsh-user-approval` /
> `dsh-user-questions` / `dsh-attachment` / `dsh-client-connection` 等）编译产物中逐字段提取。
> 它是「四象限消息模型」的精确 JSON 契约：逻辑消息与物理载体解耦，HTTP / WebSocket 只是载体。
> 字段名、类型、是否可选均以 TypeScript 声明与 zod 运行时 schema 为准；无法确证的字段标注 `(需进一步确认)`。

---

## 0. 传输层总览（已确认，勿再调研）

| 事项 | 契约 |
|---|---|
| unary 调用 | `HTTP POST /api/<method>`，`Content-Type: application/json`，body = `ClientRequest` JSON |
| unary 响应 | HTTP 200，body = `ServerResponse` JSON（回显同一 `rpcId`） |
| 业务错误 | 永远 `HTTP 200 + result.ok=false`；HTTP 非 2xx 只表达**载体层**错误（404 未知路径 / 415 非 JSON / 400 非法 body / 500 崩溃 / 403 越权） |
| 应答 server-request | `HTTP POST /api/respond`，body = `ClientResponse` JSON，响应 body = `RpcReceipt` JSON |
| 下行流（会话） | 一条「只下行」WebSocket：`/api/events.mux` |
| 下行流（主机） | 一条「只下行」WebSocket：`/api/events.host` |
| 普通 GET 到上面两条路径 | HTTP `426`（响应头 `Connection: Upgrade` / `Upgrade: websocket`），**无 SSE 回退** |
| readiness 握手 | 两条 WebSocket 均打开 **且** `host.describe` HTTP 调用成功 |
| WS 协议 | `ws://`（http 下）/ `wss://`（https 下）；每条文本消息 = 一帧 `ServerRequest` JSON |
| 上行 | 客户端**不得**在这两条 socket 上发任何数据（服务端收到任何消息会 `close(1008, "downlink only")`） |
| 二进制帧 | 客户端视为协议违规，丢弃该帧（`serverRequestSchema.parse` 失败即跳过） |
| 断线 | 任一 socket 结束 → 整个 connection generation 失效 → 重建两条流 + 重拉 history（`since` 断点续传 v1 未实现） |
| 超时 | **wire 上无 timeout 字段**；超时是客户端本地构造（`AbortSignal.timeout` / OkHttp `callTimeout`），与 payload 并列、不上线 |

---

## 1. RPC 信封（envelope）精确结构

### 1.1 四个 full-form 消息（判别字段 `type`）

```ts
// 客户端发起的调用（POST /api/<method> body）
interface ClientRequest {
  type: 'client-request';
  rpcId: RpcId;        // 发起方铸造；实现用 UUID（crypto.randomUUID()）
  method: string;      // 即方法名，如 "session.prompt"（与 URL 路径尾段一致）
  payload: unknown;    // 业务请求体（见 §3）
}

// 对 ClientRequest 的响应（该 POST 的响应 body）
interface ServerResponse {
  type: 'server-response';
  rpcId: RpcId;        // 回显请求的 rpcId，绝不新铸
  result: RpcResult<unknown>;
}

// 服务端发起的消息（下行流帧；POST /api/respond 的应答目标）
interface ServerRequest {
  type: 'server-request';
  rpcId: RpcId;        // 可应答帧用稳定逻辑 id（重放时原样复用）；纯推送帧每次新铸
  method: string;      // 下行时 = 帧类型（如 "session/event" / "approval/requested"）
  payload: unknown;
}

// 对 ServerRequest 的应答（POST /api/respond body）
interface ClientResponse {
  type: 'client-response';
  rpcId: RpcId;        // 回显 server-request 的 rpcId，绝不新铸
  result: RpcResult<unknown>;
}

type RpcMessage = ClientRequest | ServerResponse | ServerRequest | ClientResponse;
```

`RpcId`：**不透明字符串**，无最小长度约束，仅作回显 token（zod 只做一次 brand cast，不校验格式）。

### 1.2 业务成功 / 失败结果 `RpcResult<T>`

```ts
type RpcResult<T> =
  | { ok: true;  value: T }
  | { ok: false; error: RpcError };
```

- 方法**永不抛业务异常**；失败一律走 `ok:false + error`。
- 载体层抛出的异常统一折叠为 `code: 'internal'`。

### 1.3 `RpcError` 结构与错误码枚举

```ts
interface RpcError {
  code: string;       // RpcErrorCode（下表的 key）
  message: string;    // 人类可读文本（错误码自己的消息）
  details: object;    // 必填；RpcErrorDetailsMap[code]；internal 用显式 {}
}
```

**错误码 → details 字段全表（`RpcErrorDetailsMap`，40 个 code）：**

| code | details 字段 |
|---|---|
| `bad-request` | `{ issues: ZodIssue[] }`（Zod 校验问题数组） |
| `cancelled` | `{}` |
| `session-not-found` | `{ sessionId: string }` |
| `model-unavailable` | `{ provider: string, model: string }` |
| `session-conflict` | `{ sessionId: string, requestedCwd: string, existingCwd?: string }` |
| `invalid-time-zone` | `{ value: string }` |
| `workspace-attach-failed` | `{ sessionId: string, workspaceId: string }` |
| `workspace-not-found` | `{ workspaceId: string }` |
| `workspace-invalid-path` | `{ path: string }` |
| `workspace-name-conflict` | `{ name: string }` |
| `workspace-move-invalid` | `{ workspaceId: string, sessionId: string, beforeSessionId?: string }` |
| `directory-unreadable` | `{ path: string }` |
| `directory-exists` | `{ path: string }` |
| `directory-create-failed` | `{ path: string }` |
| `directory-picker-unavailable` | `{ capability: string }` |
| `agent-preset-read-only` | `{ agentPreset: string, reason: string }` |
| `agent-preset-locked` | `{ sessionId: string, agentPreset: string }` |
| `agent-preset-conflict` | `{ sessionId: string, requestedPreset: string, existingPreset?: string }` |
| `agent-preset-not-found` | `{ agentPreset: string, available: string[] }` |
| `agent-preset-invalid` | `{ agentPreset: string, reason: string }` |
| `agent-busy` | `{ reason: string }` |
| `attachment-error` | `{ reason: string }` |
| `queue-item-not-found` | `{ itemId: string }` |
| `steer-unavailable` | `{ itemId: string }` |
| `command-error` | `{}`（message 即斜杠命令自身文本） |
| `unknown-command` | `{}`（message 指名 token） |
| `settings-rejected` | `{ ns: string }` |
| `settings-not-exposed` | `{ ns: string }` |
| `settings-conflict` | `{ ns: string, expected: number, actual: number }` |
| `credential-rejected` | `{ ref: string }` |
| `model-discovery-failed` | `{ settingsNs: string, baseURL?: string }` |
| `title-invalid` | `{ sessionId: string }` |
| `fork-unavailable` | `{ sessionId: string }` |
| `subagent-parent-unavailable` | `{ parentSessionId: string }` |
| `subagent-not-found` | `{ parentSessionId: string, childSessionId: string }` |
| `subagent-catalog-diagnostic` | `{ parentSessionId: string, childSessionId: string, reason: 'corrupt'\|'unsupported'\|'unavailable' }` |
| `subagent-not-resumable` | `{ childSessionId: string }` |
| `subagent-unauthorized` | `{ childSessionId: string }` |
| `subagent-delivery-unavailable` | `{ childSessionId: string }` |
| `internal` | `{}` |

> 注：所有 branded 类型（`SessionId` / `WorkspaceId` / `MessageId` / `CallId` / `AttachmentId` /
> `ApprovalRequestId` / `GoalId`）在 wire 上都是**普通字符串**（brand 只是编译期名义类型）。

### 1.4 载体回执 `RpcReceipt`（`/api/respond` 的 HTTP 响应 body）

```ts
type RpcReceipt =
  | { accepted: true }
  | { accepted: false; reason: 'not-pending' | 'bad-response' };
// 迟到/重复应答 → not-pending
```

### 1.5 信封 JSON 示例

unary 请求（`POST /api/session.prompt` body）：

```json
{
  "type": "client-request",
  "rpcId": "0f8fad5b-d9cb-469f-a165-70867728950e",
  "method": "session.prompt",
  "payload": {
    "sessionId": "sess_01HZX...",
    "mode": "queue",
    "content": [{ "type": "text", "text": "你好" }]
  }
}
```

unary 成功响应（HTTP 200 body）：

```json
{
  "type": "server-response",
  "rpcId": "0f8fad5b-d9cb-469f-a165-70867728950e",
  "result": { "ok": true, "value": { "accepted": true } }
}
```

unary 失败响应（HTTP 200 body）：

```json
{
  "type": "server-response",
  "rpcId": "0f8fad5b-d9cb-469f-a165-70867728950e",
  "result": {
    "ok": false,
    "error": {
      "code": "session-not-found",
      "message": "session not found",
      "details": { "sessionId": "sess_missing" }
    }
  }
}
```

---

## 2. 完整 `RpcMethodMap`（52 个方法，逐字段）

> 方法名 = `POST /api/<method>` 的路径尾段。`payload` 字段名与 `result.value` 字段名如下。
> `?` = 可选（wire 上可缺省）；branded 类型在 wire 上均为 string。
> 除标注外，请求字段均为必填。

### 2.1 session 域

| 方法 | 请求 payload | 响应 value |
|---|---|---|
| `session.list` | `{ cursor?: string }`（v1 预留、未实现） | `{ items: SessionSummary[] }` |
| `session.search` | `{ query: string }` | `{ items: SessionSearchItem[], hasMore: boolean }` |
| `session.create` | `{ workspaceId?: string, cwd?: string, sessionId?: string, agentPreset?: string }` | `{ sessionId: string, agentPreset?: string }` |
| `session.history` | `{ sessionId: string, beforeSeq?: number, maxMessages?: number }` | `{ events: HistoryEntry[], hasMore: boolean, projections?: SessionProjectionsBlock }` |
| `session.models` | `{ sessionId: string }` | `SessionModels`（见下） |
| `session.selectModel` | `{ sessionId: string, provider: string, model: string, reasoningEffort?: string }` | `{ selected: ModelSelection }` |
| `session.rename` | `{ sessionId: string, title: string }` | `{ title: string, seq: number }` |
| `session.fork` | `{ sessionId: string, atSeq?: number }` | `{ sessionId: string }` |
| `session.prompt` | `{ sessionId: string, mode: 'queue'\|'steer', content: PromptContentPart[], clientTimeZone?: string }` | `{ accepted: true, command?: { kind: 'success', text?: string } }` |
| `session.attachment` | `{ sessionId: string, attachmentId: string }` | `{ attachment: ImageAttachmentRef, data: string }` |
| `session.updateQueue` | `{ sessionId: string, itemId: string, action: QueueAction }` | `{ accepted: true }` |
| `session.cancel` | `{ sessionId: string }` | `{ accepted: true }` |

### 2.2 subagent 域

| 方法 | 请求 payload | 响应 value |
|---|---|---|
| `subagent.list` | `{ parentSessionId: string }` | `SubagentCatalog = { entries: SubagentListEntry[], parentAvailable: boolean }` |
| `subagent.history` | `{ mode:'one-shot'\|'continuable', parentSessionId: string, childSessionId: string, beforeSeq?: number, maxMessages?: number }` | `{ events: HistoryEntry[], hasMore: boolean, projections?: SessionProjectionsBlock }` |
| `subagent.prompt` | `{ mode:'continuable', parentSessionId: string, childSessionId: string, content: ContentBlock[], clientTimeZone?: string }` | `{ messageId: string }` |
| `subagent.interrupt` | `{ mode:'continuable', parentSessionId: string, childSessionId: string }` | `{ accepted: true }` |

### 2.3 host 域

| 方法 | 请求 payload | 响应 value |
|---|---|---|
| `host.describe` | `{}`（空对象字面量） | `{ version: string, cwd: string, provider?: string, model?: string, attachedSessions: number, canOpenPath: boolean }` |
| `host.pickDirectory` | `{}` | `{ path: string \| null }`（取消 = null） |
| `host.listDirectory` | `{ path?: string }`（缺省列 home） | `DirectoryListing = { path: string, home: string, crumbs: DirectoryEntry[], entries: DirectoryEntry[], truncated: boolean }` |
| `host.createDirectory` | `{ path: string, name: string }` | `{ path: string }` |
| `host.openPath` | `{ path: string }` | `{ opened: true }` |

`DirectoryEntry = { name: string, path: string, hidden: boolean }`

### 2.4 workspace 域

| 方法 | 请求 payload | 响应 value |
|---|---|---|
| `workspace.list` | `{}` | `{ items: WorkspaceView[], archivedSessionIds: string[] }` |
| `workspace.create` | `{ path: string }` | `{ workspace: WorkspaceView, created: boolean }` |
| `workspace.rename` | `{ workspaceId: string, title: string }` | `{ workspace: WorkspaceView }` |
| `workspace.delete` | `{ workspaceId: string }` | `{ deleted: true }` |
| `workspace.insertBefore` | `{ workspaceId: string, beforeWorkspaceId?: string }` | `{ workspaceIds: string[] }` |
| `workspace.insertSessionBefore` | `{ workspaceId: string, sessionId: string, beforeSessionId?: string }` | `{ workspace: WorkspaceView }` |
| `workspace.archiveSession` | `{ sessionId: string }` | `{ archivedSessionIds: string[] }` |

`WorkspaceView = { workspaceId: string, path: string, title: string, sessionIds: string[], createdAt: string(ISO-8601), updatedAt: string(ISO-8601) }`

### 2.5 skill / agentPreset / goal 域

| 方法 | 请求 payload | 响应 value |
|---|---|---|
| `skill.list` | `{ sessionId: string }` | `{ skills: SkillEntry[] }` |
| `agentPreset.list` | `{}` | `{ presets: AgentPresetEntry[], authorable: boolean, hasDocument: boolean }` |
| `agentPreset.select` | `{ sessionId: string, agentPreset: string }` | `{ agentPreset: string }` |
| `agentPreset.read` | `{ agentPreset: string }` | `{ agentPreset: string, trust: 'system'\|'user', content: string, name?: string, description?: string }` |
| `agentPreset.copy` | `{ from: string, agentPreset: string, name?: string }` | `{ agentPreset: string }` |
| `agentPreset.openDocument` | `{ agentPreset: string }` | `{ opened: true } \| { opened: false, path: string }` |
| `agentPreset.remove` | `{ agentPreset: string }` | `{}` |
| `goal.create` | `{ sessionId: string, objective: string, maxGoalRounds?: number }` | `{ ref: GoalRef }` |
| `goal.edit` | `{ sessionId: string, ref: GoalRef, objective?: string, maxGoalRounds?: number }` | `{ ref: GoalRef }` |
| `goal.pause` | `{ sessionId: string, ref: GoalRef }` | `{ ref: GoalRef }` |
| `goal.resume` | `{ sessionId: string, ref: GoalRef }` | `{ ref: GoalRef }` |
| `goal.complete` | `{ sessionId: string, ref: GoalRef }` | `{ ref: GoalRef }` |
| `goal.clear` | `{ sessionId: string, ref: GoalRef }` | `{ cleared: true }` |

`GoalRef = { id: string, revision: number }`（CAS 身份：id + 精确版本号）
`SkillEntry = { name: string, description: string, whenToUse?: string, modelInvocable: boolean }`

### 2.6 settings / credentials / llm 域

| 方法 | 请求 payload | 响应 value |
|---|---|---|
| `settings.describe` | `{}` | `{ writable: boolean, hasDocument: boolean, namespaces: SettingsNamespaceView[] }` |
| `settings.openDocument` | `{}` | `{ opened: true }` |
| `settings.update` | `{ ns: string, patch: object, expectedRevision?: number }` | `SettingsNamespaceView` |
| `settings.replace` | `{ ns: string, section: object, expectedRevision?: number }` | `SettingsNamespaceView` |
| `settings.mutate` | `{ ns: string, ops: SettingsPathOpView[], expectedRevision?: number }` | `SettingsNamespaceView` |
| `credentials.describe` | `{ refs: string[] }` | `{ credentials: Record<string, CredentialView> }` |
| `credentials.set` | `{ ref: string, value: string }` | `{}` |
| `credentials.unset` | `{ ref: string }` | `{}` |
| `llm.providers` | `{}` | `{ providers: ConfigurableProviderView[] }` |
| `llm.models` | `{}` | `{ groups: ModelProviderGroup[], failures: ModelCatalogFailure[] }` |
| `llm.discoverModels` | `{ settingsNs: string, provider?: string, baseURL?: string, api?: string, apiKey?: string }` | `{ models: DiscoveredModelView[] }` |

`SettingsPathOpView = { op:'set', path: string[], value: unknown } | { op:'unset', path: string[] }`
`CredentialView = { configured: boolean, source?: string, writable: boolean }`
`ConfigurableProviderView = { provider: string, displayName: string, settingsNs: string, settingsPath: string[], active: boolean, declared?: boolean }`
`DiscoveredModelView = { id: string, name?: string, contextWindow?: number, maxTokens?: number }`

### 2.7 共享值类型

**`SessionSummary`（session.list 行）**

```ts
interface SessionSummary {
  sessionId: string;
  updatedAt: number;              // 创建时间与最新人工 prompt 时间取较晚者（epoch ms）
  running: boolean;               // 是否挂载了活 agent；冷会话恒 false
  blank: boolean;                 // 尚无任何 turn 跑过
  parentSessionId?: string;       // fork/subagent 谱系；根会话缺省
  origin?: 'subagent';            // 粗略持久化来源，不证明可续
  cwd?: string;                   // 会话工作目录（header.cwd 透传）
  agentPreset?: string;           // 该会话 agent 由哪个 preset 组成（部署无 preset 时缺省）
  projections?: SessionProjectionsBlock;  // 该行投影基线；无值或缺省时缺省
}
```

**`SessionProjectionsBlock`**

```ts
interface SessionProjectionsBlock {
  asOfSeq: number;                // 这些值反映的最后已提交事件 seq；空日志 = -1
  values: Record<string, unknown>; // 每个已注册投影键的当前整值；键缺失 = 该能力未挂载
}
```

**`HistoryEntry`**：`{ event: SessionEvent, view?: ToolEventView }`

**`ModelSelection`**：`{ provider: string, model: string, reasoningEffort?: string }`

**`SessionModels`**：
```ts
{
  current: ModelSelection;
  routable: boolean;              // adapter 是否仍服务 current.provider（能否开 turn 的关键）
  groups: ModelProviderGroup[];   // [{ id, name, models: ModelCatalogModel[] }]
  failures: ModelCatalogFailure[];// [{ id, name, message }]
}
```
`ModelCatalogModel = { id: string, name: string, description?: string, reasoning?: { efforts: [{id,name,description?}], defaultEffort?: string } }`

**`QueueAction`**：`{ kind:'edit', content: ContentBlock[] } | { kind:'remove' } | { kind:'steer' }`

**`PromptContentPart`**：
```ts
{ type: 'text',  text: string }
| { type: 'image', mediaType: 'image/png'|'image/jpeg'|'image/webp'|'image/gif',
    data: string, name?: string }   // data = base64 编码字节（不含 data: URI 前缀，mediaType 独立给出）(需进一步确认前缀)
```

---

## 3. 六个重点方法（逐字段 JSON 示例）

### 3.1 `host.describe`（readiness 握手必备）

请求 payload 为**空对象 `{}`**。

```json
// 请求
{ "type": "client-request", "rpcId": "rpc-1", "method": "host.describe", "payload": {} }

// 响应 result.value
{
  "version": "0.1.0",          // apps/cli 的 package.json version
  "cwd": "C:\\Users\\spark",   // host 进程工作目录（会话持久化与工具执行根）
  "provider": "deepseek-official",  // 可选：新 agent 默认 provider（无显式默认时缺省）
  "model": "deepseek-chat",        // 可选：默认 model
  "attachedSessions": 3,           // 当前已挂载（有活 agent）会话数
  "canOpenPath": true              // 能否把路径交给用户可见原生桌面
}
```

### 3.2 `session.list`

```json
{ "type": "client-request", "rpcId": "rpc-2", "method": "session.list", "payload": {} }
```
```json
{ "type": "server-response", "rpcId": "rpc-2", "result": { "ok": true, "value": {
  "items": [
    {
      "sessionId": "sess_01",
      "updatedAt": 1730000000000,
      "running": true,
      "blank": false,
      "cwd": "C:\\Users\\spark\\proj",
      "agentPreset": "default",
      "projections": { "asOfSeq": 41, "values": { "title": "测试会话" } }
    }
  ]
}}}
```

### 3.3 `session.create`

```json
{ "type": "client-request", "rpcId": "rpc-3", "method": "session.create", "payload": {
  "cwd": "C:\\Users\\spark\\proj",   // workspaceId 与 cwd 至多其一；都省略用 Host cwd
  "sessionId": "sess_prealloc_01",  // 可选：预分配；同 id+cwd 重试返回同一会话
  "agentPreset": "default"          // 可选：省略用有效默认
}}
```
```json
{ "type": "server-response", "rpcId": "rpc-3", "result": { "ok": true, "value": {
  "sessionId": "sess_prealloc_01",
  "agentPreset": "default"
}}}
```

### 3.4 `session.history`

```json
{ "type": "client-request", "rpcId": "rpc-4", "method": "session.history", "payload": {
  "sessionId": "sess_01",
  "beforeSeq": 100,      // 可选：向前翻页上界（loadOlder）；缺省读尾页
  "maxMessages": 50      // 可选：整消息数分页上界
}}
```
```json
{ "type": "server-response", "rpcId": "rpc-4", "result": { "ok": true, "value": {
  "events": [
    { "event": { "type": "user/message", "seq": 90, "time": 1730000000100,
        "data": { "id": "msg_u1", "role": "user", "content": [{"type":"text","text":"hi"}],
                  "source": { "kind": "user", "rpcId": "rpc-prompt-1" } },
        "surfaceOp": "append" } },
    { "event": { "type": "assistant/message", "seq": 95, "time": 1730000000500,
        "data": { "turn": 1, "step": 1,
                  "message": { "id": "msg_a1", "role": "assistant",
                    "content": [{"type":"text","text":"hello"}],
                    "source": { "kind": "model", "provider": "deepseek-official", "model": "deepseek-chat" } },
                  "usage": { "inputTokens": 12, "outputTokens": 5 } },
        "sourceEventSeqs": [91,92,93,94], "surfaceOp": "append" } }
  ],
  "hasMore": false,
  "projections": { "asOfSeq": 95, "values": { "title": "hi" } }   // 仅尾页携带
}}}
```

> 尾页额外带 in-flight 部分（最后一条未定稿消息已发出的 chunk）。页边界对齐到**整消息**边界。

### 3.5 `session.prompt`

```json
{ "type": "client-request", "rpcId": "rpc-prompt-1", "method": "session.prompt", "payload": {
  "sessionId": "sess_01",
  "mode": "queue",                 // queue = 入队发送；steer = 严格转向
  "content": [
    { "type": "text", "text": "请总结这个文件" },
    { "type": "image", "mediaType": "image/png", "data": "iVBORw0KGgo...", "name": "shot.png" }
  ],
  "clientTimeZone": "Asia/Shanghai"  // 可选：浏览器 IANA zone；非浏览器可省略
}}
```
```json
{ "type": "server-response", "rpcId": "rpc-prompt-1", "result": { "ok": true, "value": {
  "accepted": true,
  "command": { "kind": "success", "text": "已执行 /help" }   // 仅当 dispatch 了斜杠命令才出现
}}}
```

> 斜杠命令：`content` 恰好是**单个** `text` 块且以 `/` 开头时走命令注册表（mode 无关），不发给模型；
> 用法/状态错误 → `command-error`，未识别名 → `unknown-command`。
> 该 prompt 的 `rpcId` 会作为 `MessageSource` 透传到 `user/message` 事件（`source.rpcId`），
> 供客户端把乐观回显的临时消息与事件流对账。

### 3.6 `session.cancel`

```json
{ "type": "client-request", "rpcId": "rpc-cancel-1", "method": "session.cancel", "payload": { "sessionId": "sess_01" } }
```
```json
{ "type": "server-response", "rpcId": "rpc-cancel-1", "result": { "ok": true, "value": { "accepted": true } } }
```

> 取消活动 turn，保留待处理 inbox 工作（取消落定后按 FIFO 恢复）。

---

## 4. 下行 mux 帧（`/api/events.mux`）精确结构

### 4.1 WebSocket 帧 = `ServerRequest` JSON 文本，每条一帧

Host 侧序列化（`dsh-client-connection` 源码确认）：

```js
function serverRequest(frame) {
  return {
    type: "server-request",
    rpcId: frame.rpcId,
    method: frame.payload.type,     // method 与 payload.type 重复
    payload: frame.payload          // payload = MuxFrame / HostFrame
  };
}
socket.send(JSON.stringify(serverRequest(frame)));   // 每条文本消息一帧
```

即线上每个文本帧形如：

```json
{
  "type": "server-request",
  "rpcId": "9d4f2a...",
  "method": "session/event",         // = payload.type
  "payload": {
    "type": "session/event",
    "sessionId": "sess_01",          // ← sessionId 在 payload 层，不在信封层
    "event": { ... },                // SessionEvent
    "view": { ... }                  // 可选 ToolEventView
  }
}
```

客户端解析：`serverRequestSchema.parse(JSON.parse(text))` → `full`，再 `frameSchema.parse(full.payload)` → 帧；
失败则跳过该帧（单帧损坏不杀流）。二进制帧 = 协议违规，丢弃。

### 4.2 `MuxFrame` 判别联合（`type` 字段）

| `payload.type` | 字段 | 说明 |
|---|---|---|
| `session/event` | `sessionId`, `event: SessionEvent`, `view?: ToolEventView` | 原始会话事件透传 |
| `session/subscribed` | `sessionId`, `lastSeq: number` | 打开时对每个已挂载会话发订阅基线（lastSeq = 最后已提交事件 seq，空日志 -1） |
| `approval/requested` | `sessionId`, `approvalId`, `toolName`, `callId?`, `reason?` | 可应答 server-request（稳定 rpcId，重放复用） |
| `approval/resolved` | `sessionId`, `approvalId`, `outcome` | outcome ∈ `'allowed-once'\|'rejected'\|'cancelled'\|'unavailable'` |
| `question/requested` | `sessionId`, `questions: AskUserQuestionItem[]`（≥1） | 可应答 server-request |
| `question/resolved` | `sessionId`, `questionRpcId`, `outcome: 'answered'\|'cancelled'` | |
| `session/queue` | `sessionId`, `items: QueuedInboxItem[]` | 每次入队/变更/认领/丢弃后的完整瞬时 inbox 快照 |
| `session/jobs` | `sessionId`, `jobs: JobView[]` | 后台任务完整集；空集发 `[]`；无该键 = 空 |
| `session/projection` | `sessionId`, `key`, `value: unknown`, `seq: number(≥0)` | 单投影单元值变更；客户端按 higher-seq-wins 覆盖 |
| `stream/error` | `error: RpcError`（**无 sessionId**） | 流级错误 |

`QueuedInboxItem = { id: string, placement: 'queued'|'steering'|'context', message: Message }`
`JobView = { id: string, kind: string, label: string, status: 'running'|'stopping'|'completed'|'killed'|'failed', detail?: string, startedAt: number, finishedAt?: number }`

`ToolEventView = { for:'call', view: ToolCallView } | { for:'result', view: ToolResultView }`
（view 为 host 计算的渲染意图，**不持久化**；缺省 = 客户端默认通用 JSON 卡片。view 内部结构属 `dsh-tools/presentation`，客户端只读不回显。）

### 4.3 `SessionEvent` 信封（`session/event` 帧内）

```ts
// 判别字段 type；严格信封 type/seq/time + 宽 data
{
  type: string;          // 事件类型（见 §4.4 / known-event-types）
  seq: number;           // 会话内单调递增序号
  time: number;          // Unix epoch 毫秒
  data: object;          // SessionEventMap[type]
  ignorable?: true;      // 可选；true = 读者不认识该 type 时可安全跳过；缺省 = required
  // 仅 surface 事件（user/message | assistant/message | tool/result）额外：
  sourceEventSeqs?: number[];  // 引用产生该事件的早期事件 seq
  surfaceOp?: 'append' | { op:'replace', start:number, end:number };
}
```

### 4.4 核心会话事件 data 形状（`SessionEventMap` 核心 + 插件合并）

#### `user/message`（data = 完整 `UserMessage`）

```json
{
  "type": "user/message", "seq": 90, "time": 1730000000100,
  "data": {
    "id": "msg_u1",                 // 稳定身份，跨 inbox/log/模型请求一致
    "role": "user",
    "content": [ { "type": "text", "text": "hi" } ],
    "source": { "kind": "user", "rpcId": "rpc-prompt-1", "clientTimeZone": "Asia/Shanghai" }
  },
  "surfaceOp": "append"
}
```

`source.kind` 可能取值：
- `'user'`（直接人类 prompt；含 `rpcId` 与可选 `clientTimeZone` 时即 browser 提交的 `user-rpc` 来源）
- `'plugin'`（合成注入：文件变更通知 / AGENTS.md / skill 内容 / cron 等，带 `plugin` 名 + 可选 `form` 语义）
- `'model'`（assistant 用，见下）
- `'tool'`（tool 结果消息用，见下）

#### `assistant/chunk`（流式增量；data 含 `chunk`）

```json
{
  "type": "assistant/chunk", "seq": 91, "time": 1730000000200,
  "data": { "turn": 1, "step": 1, "chunk": { "type": "text-delta", "index": 0, "text": "你" } }
}
```

`chunk` = `StreamChunk` 联合（判别字段 `type`）：

| chunk.type | 字段 | 说明 |
|---|---|---|
| `block-start` | `index: number`, `blockType: string` | 开启第 index 块，声明块类型 |
| `text-delta` | `index: number`, `text: string` | **可见文本增量**（拼接目标） |
| `reasoning-delta` | `index: number`, `text: string` | 推理/思考增量（区别于可见文本） |
| `tool-call-delta` | `index: number`, `id: string`, `name?: string`, `argumentsDelta: string` | 工具调用增量（arguments 累积） |
| `block-end` | `index: number`, `block: ContentBlock` | 携带**组装好的整块** |
| `usage` | `usage: TokenUsage` | 在 terminal finish 之前发出 |
| `finish` | `reason: FinishReason`, `replayState?: unknown` | 终止（adapter 不再发出后续内容） |

> **关键：增量在 `data.chunk.text` / `data.chunk.argumentsDelta` / `data.chunk.reasoning-delta`（即 `text`），
> 而非顶层 `delta` 字段。** `index` 关联交错的多块增量；`block-end` 携带组装块；`finish` 携带结束原因。

#### `assistant/message`（定稿消息；data 含 `message` + 可选 `usage`）

```json
{
  "type": "assistant/message", "seq": 95, "time": 1730000000500,
  "data": {
    "turn": 1, "step": 1,
    "message": {
      "id": "msg_a1", "role": "assistant",
      "content": [ { "type": "text", "text": "你好！" } ],
      "source": { "kind": "model", "provider": "deepseek-official", "model": "deepseek-chat" }
    },
    "usage": { "inputTokens": 12, "outputTokens": 5, "cacheReadTokens": 3, "reasoningTokens": 0 }
  },
  "sourceEventSeqs": [91, 92, 93, 94],   // 引用构建本消息的 assistant/chunk seq
  "surfaceOp": "append"
}
```

`usage`（`TokenUsage`，adapter 未报账时缺省）：
`{ inputTokens: number, outputTokens: number, cacheReadTokens?: number, cacheWriteTokens?: number, reasoningTokens?: number }`
（input 为未缓存输入；计费输入 = input + cacheRead + cacheWrite 三者之和。）

#### `tool/call`

```json
{
  "type": "tool/call", "seq": 96, "time": 1730000000600,
  "data": { "turn": 1, "step": 1, "callId": "call_01", "name": "read", "arguments": "{\"file_path\":\"a.txt\"}" }
}
```
（`arguments` 是模型产出的**原始 JSON 字符串，未解析**；`callId` 与 `tool/result` 配对。）

#### `tool/result`

```json
{
  "type": "tool/result", "seq": 97, "time": 1730000000700,
  "data": {
    "turn": 1, "step": 1,
    "message": {
      "id": "msg_t1", "role": "user",
      "content": [ { "type": "tool-result", "toolCallId": "call_01", "content": [ {"type":"text","text":"..."} ], "isError": false } ],
      "source": { "kind": "tool", "callId": "call_01" }
    },
    "error": { "name": "EACCES", "code": "EPERM" },   // 可选内部失败身份
    "meta": { "diff": "..." }                          // 可选工具私有 JSON 表示载荷
  },
  "surfaceOp": "append"
}
```

#### `turn/start` / `turn/end`

```json
{ "type": "turn/start", "seq": 88, "time": 1730000000000, "data": { "turn": 1 } }
```
```json
{ "type": "turn/end", "seq": 99, "time": 1730000000900,
  "data": { "turn": 1, "reason": { "kind": "completed" } } }
```

`reason`（`TurnEndReason`，判别字段 `kind`）：

| kind | 附加字段 |
|---|---|
| `completed` | — |
| `aborted` | `reason: TurnEndCancelCause`（见下） |
| `blocked` | — |
| `error` | `error: LlmFailure`（`{ message, code, status?, providerRetryAfterMs?, requestId? }`） |
| `max-tokens` | — |
| `interrupted` | —（持久化后端关闭崩溃孤儿 turn；循环本身不发） |

`TurnEndCancelCause = { kind:'user' } | { kind:'parent' } | { kind:'hook', reason } | { kind:'disposed' } | { kind:'legacy' }`

#### `step/start` / `step/end`

```json
{ "type": "step/start", "seq": 89, "time": 1730000000050, "data": { "turn": 1, "step": 1 } }
{ "type": "step/end",   "seq": 98, "time": 1730000000850, "data": { "turn": 1, "step": 1 } }
```

#### `session/title`（log-only，插件合并，判别 `source.kind`）

```json
{
  "type": "session/title", "seq": 100, "time": 1730000001000,
  "data": {
    "title": "测试会话",              // 规范化后非空标题
    "messageSeqs": [90],              // 用于推导该标题的 user/message seq；显式改名 = []
    "source": { "kind": "provider", "provider": "first-prompt-llm", "model": { "provider": "deepseek-official", "model": "deepseek-chat" } }
  }
}
```

`source`：`{ kind:'fallback' } | { kind:'provider', provider, model? } | { kind:'user' }`（user = 显式改名，钉住标题，停自动生成）。

#### `approval/asked` / `approval/decided` / `approval/policy`（log-only 审计，插件合并）

```json
{ "type": "approval/asked", "seq": 50, "time": 1730000003000,
  "data": { "id": "appr_01", "toolName": "bash", "callId": "call_09", "reason": "需要执行写操作" } }
{ "type": "approval/decided", "seq": 51, "time": 1730000003100,
  "data": { "id": "appr_01", "outcome": "allowed-once" } }
{ "type": "approval/policy", "seq": 3, "time": 1730000000010,
  "data": { "policy": "never", "source": "delegation" } }
```

> `approval/asked`/`decided` 是**审计事件**（非 surface 事件、无 surfaceOp）。它们与 mux 帧
> `approval/requested`（展示/应答入口）和 `approval/resolved`（最终结果）是两条并行的信号：UI 应
> 消费 `approval/requested` mux 帧来展示确认，经 `/api/respond` 回答；`approval/asked`/`decided`
> 仅作为日志审计在 `session/event` 里顺带出现。

### 4.5 权限确认（approval / question）的展示与应答

**approval 应答**（`POST /api/respond`）：

```json
{
  "type": "client-response",
  "rpcId": "<approval/requested 帧的 rpcId>",   // 回显，不新铸
  "result": {
    "ok": true,
    "value": {                                  // ApprovalResponsePayload
      "sessionId": "sess_01",
      "approvalId": "appr_01",
      "outcome": "allowed-once"                  // 客户端只能给 'allowed-once' | 'rejected'
    }
  }
}
```
HTTP 响应 body（`RpcReceipt`）：`{ "accepted": true }` 或 `{ "accepted": false, "reason": "not-pending" }`。

**question 展示**（`question/requested` 帧内 `questions` 数组，元素 = `AskUserQuestionItem`）：

```json
{
  "id": "q1",
  "question": "是否继续？",
  "header": "确认",            // 可选
  "detail": "将执行 X",        // 可选
  "options": [ { "label": "继续", "description": "执行 X" } ],   // 可选
  "multiSelect": false,        // 可选，默认单选
  "intent": { "kind": "plan-review", "approve": "继续" }          // 可选呈现意图
}
```

**question 应答**（`POST /api/respond`，一次答整批）：

```json
{
  "type": "client-response",
  "rpcId": "<question/requested 帧的 rpcId>",
  "result": {
    "ok": true,
    "value": {                                    // QuestionResponsePayload
      "sessionId": "sess_01",
      "answer": {
        "answers": [
          { "id": "q1", "selected": ["继续"], "custom": "其他文本" }   // custom 可选
        ]
      }
    }
  }
}
```

---

## 5. 下行 host 帧（`/api/events.host`）

与 mux 同构（每条 = `ServerRequest` JSON 文本帧），`payload` 为 `HostFrame`：

| `payload.type` | 字段 |
|---|---|
| `host/session-added` | `sessionId`, `blank: boolean`, `parentSessionId?`, `origin?: 'subagent'`, `cwd?`, `agentPreset?` |
| `host/session-removed` | `sessionId` |
| `host/session-status` | `sessionId`, `running: boolean` |
| `host/agent-error` | `sessionId`, `message: string` |
| `host/workspace-changed` | `workspace: WorkspaceView` |
| `host/workspace-removed` | `workspaceId` |
| `host/workspace-order-changed` | `workspaceIds: string[]` |
| `host/archived-sessions-changed` | `archivedSessionIds: string[]` |
| `host/remote-event` | `event: string`, `args: JsonValue[]`（allowlist 转发，见下） |
| `stream/error` | `error: RpcError`（**无 sessionId**） |

> `host/remote-event` 仅转发 `API_REMOTE_FORWARDED_EVENTS` 白名单中的 cordis 事件，载荷契约属其 owner 包，
> 不做投影/脱敏/改名。原生客户端通常可忽略。

---

## 6. 响应流如何结束 / 「正在运行」状态表示

- **turn 结束**：以 `turn/end` 事件（`session/event` 帧）标记；`reason.kind` 说明结束原因
  （`completed` / `aborted` / `blocked` / `error` / `max-tokens` / `interrupted`）。
  一个 turn 可能含多个 `step`（每步一对 `step/start`/`step/end`）。
- **「正在运行」状态**：
  1. `host/session-status` 帧（`running: boolean`）是运行态翻转的权威信号；
  2. `turn/start`（running=true 语义）与 `turn/end`（该 turn 收束）作为事件级边界；
  3. `session.list` 行的 `running` / `blank` 字段是重连基线；
  4. `session/subscribed` 的 `lastSeq` 是订阅时的事件水位。
- **mux 流本身不随 turn 结束而关闭**：它是长期连接，直至 socket 关闭或服务端 teardown。
  重连 = 重开两条 WS + 重拉 `session.history`（`events.mux` 的 `since` 参数 v1 未实现、被忽略）。
- **agent 崩溃**（无 turn 位置）：走 `host/agent-error` 帧。

---

## 7. 增量文本流 / 消息定稿 / seq 排序 关键字段速查

| 概念 | 字段 | 位置 |
|---|---|---|
| 事件自身序号 | `seq`（会话内单调递增 int） | `SessionEvent.seq` |
| 事件时间 | `time`（epoch ms） | `SessionEvent.time` |
| 消息稳定 id | `id`（`MessageId`） | `message.id`（user/assistant/tool-result 消息体） |
| 会话 id | `sessionId` | mux/host 帧 `payload.sessionId` 层；**不在信封层** |
| 调用关联 id | `rpcId` | 信封层（回显）；另 `user/message` 的 `source.rpcId` 关联 session.prompt |
| 工具调用关联 | `callId`（`CallId`） | `tool/call.data.callId` ↔ `tool/result` 的 `message.content[].toolCallId` / `source.callId` |
| 审批关联 | `approvalId`（`ApprovalRequestId`） | `approval/requested` 帧 ↔ `approval/asked`/`decided` 事件 ↔ 应答 payload |
| 问题关联 | `questionRpcId` / 帧 rpcId | `question/resolved` 帧 / 应答 rpcId |
| 来源 | `source`（判别 `kind`） | user=`user`/`plugin`；assistant=`model`；tool result=`tool` |
| 角色 | `role`（`system`/`user`/`assistant`） | `message.role` |
| 内容块 | `content: ContentBlock[]` | `message.content`；块判别字段 `type`（text/reasoning/image/tool-call/tool-result） |
| 增量文本 | `data.chunk.text`（text-delta/reasoning-delta）；`data.chunk.argumentsDelta`（tool-call-delta） | `assistant/chunk.data.chunk` |
| 定稿引用 | `sourceEventSeqs`（引用构建该 surface 事件的前序 chunk seq） | `assistant/message.sourceEventSeqs` |
| 表面操作 | `surfaceOp`（append / replace{start,end}） | surface 事件（user/message、assistant/message、tool/result） |
| 可忽略标记 | `ignorable?: true` | 不认识的 type 且无此标记 = 必须拒绝重建 |

### 增量拼接规则（`assistant/chunk` → `assistant/message`）

1. `block-start(index, blockType)` 声明某索引的块类型；`index` 关联交错的多块。
2. `text-delta`/`reasoning-delta` 的 `text` 按 `index` 累加拼接成对应 text/reasoning 块；
   `tool-call-delta` 的 `argumentsDelta` 按 `index` 累加成 tool-call 的 `arguments` 原始 JSON。
3. `block-end(index, block)` 给出该索引的**定稿整块**（`ContentBlock`）。
4. `usage` 在 terminal `finish` 前发出（token 账目）；`finish(reason, replayState?)` 后不再有内容。
5. `assistant/message` 是某 step 的**定稿 assistant 消息**：完整 `content` 块、稳定 `id`、`source{kind:'model',provider,model}`、可选 `usage`；并用 `sourceEventSeqs` 引用产生它的 chunk seq。
6. 客户端可按 seq 有序消费；若发现 seq 空洞（漏帧），应重拉 `session.history` 补全（载体层不保证重传）。

---

## 8. 实现要点（Kotlin / OkHttp）

### 8.1 依赖与客户端骨架

- 用 OkHttp `WebSocketListener` 接两条下行 socket；`ws://`/`wss://` 由 base URL 协议决定。
- 每条 `onMessage` 文本 → `JSON.parse` 出 `ServerRequest`，按 `payload.type` 分发；**绝不上行**。
- unary 用 OkHttp `POST /api/<method>`，`Content-Type: application/json`，body = `ClientRequest` JSON，
  校验响应 `rpcId` 回显一致，然后按 `result.ok` 分支解析。
- 应答 `approval`/`question` 用 `POST /api/respond`，body = `ClientResponse`，读取 `RpcReceipt`。

### 8.2 建议的 Kotlin 数据模型（moshi/kotlinx-serialization）

- 信封：`sealed class RpcMessage { ClientRequest(rpcId, method, payload); ServerResponse(rpcId, result); ServerRequest(rpcId, method, payload); ClientResponse(rpcId, result) }`。
- `RpcResult<T>`：`{ ok: Boolean; value: T?; error: RpcError? }`（运行时按 ok 判）。
- 帧：`sealed class MuxFrame { SessionEvent; Subscribed; ApprovalRequested; ... }`，`payload.type` 作判别。
- `SessionEvent`：`type/seq/time/data + ignorable?/sourceEventSeqs?/surfaceOp?`；`data` 用 `JsonObject` 或按 type 细分。

### 8.3 三个最需要当心的坑

1. **信封两层 `type`/两层判别**：`payload.type`（帧类型）与外层 `method` 重复；`sessionId` 在
   `payload` 内、不在信封层。解析时先 `ServerRequest` 再按 `payload.type` 二次解析；`stream/error`
   帧**没有** `sessionId`。
2. **下行 socket 只读 + 426 无回退**：对 `/api/events.mux`/`events.host` 必须走 WebSocket upgrade，
   普通 GET 得到 426；客户端发任何数据会被服务端 `1008` 关闭。二进制帧/坏帧要静默丢弃并靠
   `seq` 空洞检测触发重拉 history，不能让单帧错误杀死整条流。
3. **增量在 `chunk` 内、定稿靠 `assistant/message`**：文本不是顶层 `delta`，而是
   `data.chunk.text`（`text-delta`/`reasoning-delta`）与 `data.chunk.argumentsDelta`（`tool-call-delta`），
   且靠 `index` 交错多块；UI 应优先渲染增量、最终以 `assistant/message` 的定稿 `content` 收敛，
   并用其 `sourceEventSeqs` 关联前序 chunk。工具参数是**未解析的原始 JSON 字符串**。
