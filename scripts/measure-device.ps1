param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [int]$DurationSeconds=1800,
    [int]$IntervalSeconds=10
)
$ErrorActionPreference='Stop'
if($DurationSeconds -lt 10 -or $IntervalSeconds -lt 2) { throw '采样时长至少 10 秒，间隔至少 2 秒。' }
$taskAdb=Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskOutput=Join-Path $taskRoot ('.local\performance\'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $taskOutput | Out-Null
$taskEnd=(Get-Date).AddSeconds($DurationSeconds)
& $taskAdb -s $Serial shell getprop | Set-Content (Join-Path $taskOutput '设备信息.txt') -Encoding utf8
Write-Host "开始采样。请在真实设备上持续操作，分别记录预览开／关阶段。输出：$taskOutput"
while((Get-Date) -lt $taskEnd) {
    $taskName=Get-Date -Format 'yyyyMMdd-HHmmss'
    $taskFile=Join-Path $taskOutput ($taskName+'.txt')
    "时间：$(Get-Date -Format o)" | Set-Content $taskFile -Encoding utf8
    & $taskAdb -s $Serial shell dumpsys meminfo dev.controlpad.receiver | Add-Content $taskFile -Encoding utf8
    & $taskAdb -s $Serial shell top -b -n 1 | Add-Content $taskFile -Encoding utf8
    & $taskAdb -s $Serial shell 'for zone in /sys/class/thermal/thermal_zone*; do echo "$zone"; cat "$zone/type" "$zone/temp" 2>/dev/null; done' | Add-Content $taskFile -Encoding utf8
    Start-Sleep -Seconds $IntervalSeconds
}
Write-Host '采样结束。温度数值的单位及传感器含义需结合厂商固件确认；操作与预览延迟需另用录像测量。'
