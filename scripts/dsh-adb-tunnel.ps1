<#
  dsh-adb-tunnel.ps1 — 本地 Harness（Linux VM）隧道保持器

  持续监控 adb 设备，自动建立并保持 `adb reverse tcp:3090 tcp:3090`：
  手机端「本地 Harness (127.0.0.1:3090)」经 主机 → VM 端口转发 直连
  Linux 虚拟机里的隔离 Harness 实例，与局域网（3080）完全独立；
  设备断开重连后自动恢复，无需手动重跑。

  用法:
    powershell -ExecutionPolicy Bypass -File dsh-adb-tunnel.ps1         前台常驻（Ctrl+C 退出）
    powershell -ExecutionPolicy Bypass -File dsh-adb-tunnel.ps1 -Once   只执行一次并报告
    powershell -ExecutionPolicy Bypass -File dsh-adb-tunnel.ps1 -Port 3080   换端口
  建议: 需要时运行即可；或放入启动项/计划任务常驻。
#>
param(
    [int]$Port = 3090,
    [switch]$Once
)

$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { $adb = 'adb' }

function Ensure-Reverse {
    $devices = & $adb devices 2>$null | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' }
    if (-not $devices) { return $false }
    $list = & $adb reverse --list 2>$null
    if ($list -notmatch "tcp:$Port tcp:$Port") {
        & $adb reverse "tcp:$Port" "tcp:$Port" | Out-Null
        Write-Host "[dsh-adb-tunnel] $(Get-Date -Format 'HH:mm:ss') 已建立 reverse tcp:$Port -> 主机"
    }
    return $true
}

if ($Once) {
    if (Ensure-Reverse) { Write-Host "[dsh-adb-tunnel] OK：手机 127.0.0.1:$Port 现在指向主机（再经 VM 端口转发到本地 Harness）" }
    else { Write-Host '[dsh-adb-tunnel] 未检测到 adb 设备（请连接 USB/无线调试）' }
    exit 0
}

Write-Host "[dsh-adb-tunnel] 常驻运行中，监控设备并保持 reverse tcp:$Port ...（Ctrl+C 退出）"
while ($true) {
    Ensure-Reverse | Out-Null
    Start-Sleep -Seconds 5
}
