# Security Policy

## 重要提示：DSH Web carrier 无鉴权

本客户端连接的 DeepSeek Harness Web 后端**没有身份认证层**——其浏览器信任栅栏只是「可达性策略」，不是认证。任何能触达 harness 端口的同网段主机都可以驱动会话并执行命令。

### 使用建议

1. **仅在可信网络暴露 harness**（家庭局域网、个人热点），不要在公共网络直接绑定 `0.0.0.0`。
2. 需要跨网访问时，务必在 harness 前加 **反向代理 + 认证**（如 nginx + Basic Auth / Tailscale / WireGuard VPN），并让客户端通过该代理连接。
3. 客户端本身**不保存任何密钥**：DeepSeek API Key 等凭证全部由 PC 端 harness 管理，余额查询经 `deepseek/balance` 端点由 PC 代查。
4. 客户端本地仅持久化：服务器地址列表（SharedPreferences）。明文存储，不含敏感信息。

### 报告漏洞

请通过 GitHub 仓库的 [Security Advisories](https://github.com/SparkWeiyang/DeepseekHarness-AndroidClient/security/advisories/new) 私下报告安全漏洞，或联系仓库维护者。请勿在公开 issue 中披露未修复的漏洞细节。
