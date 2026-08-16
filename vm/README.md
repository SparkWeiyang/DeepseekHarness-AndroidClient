# 本地 Harness（Linux VM 隔离实例）

「本地 Harness」是一套运行在 **Linux 虚拟机**里的完整 DeepSeek Harness 生态，与主机上的局域网 Harness **完全隔离**：

| | 局域网 Harness（主机） | 本地 Harness（Linux VM） |
|---|---|---|
| 运行位置 | Windows 主机 | Linux 虚拟机（WSL2 / VirtualBox / VMware…） |
| 端口约定 | `3080` | VM 内 `3090` → 主机 `127.0.0.1:3090` |
| 数据 | `C:\Users\<user>\.dsh` | VM 内 `~/.dsh`（独立会话/凭证/设置/插件） |
| 手机访问 | 局域网 IP:3080 | `adb reverse` → `127.0.0.1:3090` |
| 用途 | 日常主力 | 实验/沙箱/隔离环境 |

## 1. 在 VM 内部署 Harness

```bash
curl -fsSL https://raw.githubusercontent.com/SparkWeiyang/DeepseekHarness-AndroidClient/main/vm/harness-vm-setup.sh | bash
```

脚本会：检查 Node → `npx` 拉取**原版** DSH（独立 `~/.dsh`）→ 可选安装 `dsh-lan` 插件 → 可选注册 systemd 自启服务（`dsh web --host 0.0.0.0 --port 3090`）。

手动运行：

```bash
npx --yes @deepseek-ai/dsh web --host 0.0.0.0 --port 3090
```

## 2. 配置 VM 端口转发（3090 → 主机 127.0.0.1:3090）

### WSL2（推荐，零配置）

WSL2 默认开启 localhost 转发：VM 内监听 `3090` 即可在 Windows 上直接访问 `http://127.0.0.1:3090`。若不生效，检查 `%UserProfile%\.wslconfig`：

```ini
[wsl2]
localhostForwarding=true
```

> 注意：WSL2 内 `~/.dsh` 与 Windows 的 `C:\Users\<user>\.dsh` 天然隔离。

### VirtualBox

NAT 网络下添加端口转发：

```powershell
VBoxManage modifyvm "<虚拟机名>" --natpf1 "dsh3090,tcp,127.0.0.1,3090,,3090"
```

（删除规则：`VBoxManage modifyvm "<虚拟机名>" --natpf1 delete "dsh3090"`）

### VMware Workstation

NAT 设置（编辑 → 虚拟网络编辑器 → NAT → 端口转发）中添加：
主机端口 `3090` TCP → 虚拟机 IP 端口 `3090`。

## 3. 手机连接

1. PC 端运行隧道保持器（自动 `adb reverse tcp:3090 tcp:3090`）：

   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\dsh-adb-tunnel.ps1
   ```

   （或直接运行 `scripts\dsh-web.ps1`，它会自动拉起隧道）
2. 手机 App 服务器列表点顶部「🐧 本地 Harness（Linux VM）」→ 连接。
3. 失败时 App 会逐步提示：VM 是否运行 → 端口转发是否配置 → 隧道是否建立。

## 与主机实例共存

两套实例可同时运行、互不影响：手机在 App 里分别连接「局域网服务器（3080）」与「本地 Harness（3090）」，会话、凭证（API Key）、设置、插件各自独立。
