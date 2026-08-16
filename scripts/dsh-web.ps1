<#
  dsh-web.ps1 — DeepSeek Harness Web GUI 启动器（主机·局域网版）

  无需任何补丁、无需启动参数：
    - 局域网绑定（0.0.0.0 + 自动信任白名单）由 dsh-lan 插件提供
    - --host 0.0.0.0 在当前版本已原生支持（仅警告）
    - randomUUID polyfill 在当前版本已内置
  旧的 dsh-web-lan.ps1（打补丁版）已可废弃删除。

  本脚本启动时会自动：
    1) 若 adb 设备在线，建立 adb reverse tcp:3090 tcp:3090
       （手机「本地 Harness」→ 主机 3090 → Linux VM 隔离实例的端口转发）
    2) 后台启动 dsh-adb-tunnel.ps1 保持器（设备重连后自动恢复隧道）

  端口分工（隔离设计）：
    3080 = 本机·局域网 Harness（本脚本启动）
    3090 = 本地 Harness（Linux VM 隔离实例，见 vm/README.md）

  用法:
    powershell -ExecutionPolicy Bypass -File dsh-web.ps1             启动（0.0.0.0:3080，局域网可访问）
    powershell -ExecutionPolicy Bypass -File dsh-web.ps1 -Port 8080  换端口
  安全警告: 该 GUI 无鉴权，同网段任何人都可驱动会话执行命令，请仅在可信网络使用。
#>
param(
    [int]$Port = 3080,
    [int]$LocalPort = 3090
)

# ---- 本地 Harness（Linux VM）隧道（adb reverse） ----
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { $adb = 'adb' }
$tunnelScript = Join-Path $PSScriptRoot 'dsh-adb-tunnel.ps1'

$devices = & $adb devices 2>$null | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' }
if ($devices) {
    & $adb reverse "tcp:$LocalPort" "tcp:$LocalPort" 2>$null | Out-Null
    Write-Host "[dsh-web] adb reverse tcp:$LocalPort 已建立（手机「本地 Harness（Linux VM）」可用）"
    if (-not (Get-CimInstance Win32_Process -Filter "Name like 'powershell.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like '*dsh-adb-tunnel*' })) {
        Start-Process powershell -WindowStyle Hidden -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File',"`"$tunnelScript`"" -ErrorAction SilentlyContinue
        Write-Host '[dsh-web] 已后台启动隧道保持器 dsh-adb-tunnel.ps1'
    }
} else {
    Write-Host '[dsh-web] 未检测到 adb 设备（本地 Harness 需要 USB/无线调试；局域网不受影响）'
}

# ---- 启动主机·局域网 harness ----
$extra = if ($PSBoundParameters.ContainsKey('Port')) { "--port $Port" } else { '' }
Write-Host "[dsh-web] 启动 dsh web $extra ..."
& cmd /c "npx --yes @deepseek-ai/dsh web $extra"
