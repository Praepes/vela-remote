param([switch]$ShowWindows)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$taskAdb = Join-Path $taskSdk 'platform-tools\adb.exe'
$taskEmulator = Join-Path $taskSdk 'emulator\emulator.exe'
$taskSdkManager = Join-Path $taskSdk 'cmdline-tools\latest\bin\sdkmanager.bat'
$taskAvdManager = Join-Path $taskSdk 'cmdline-tools\latest\bin\avdmanager.bat'
$taskImage = Join-Path $taskRoot '.local\images\api26-root'
$taskAvd = Join-Path $taskRoot '.local\avd\control_pad_receiver_api26_root.avd'
$taskLog = Join-Path $taskRoot '.local\logs'
$taskTool = Join-Path $taskRoot '.local\rootAVD'
New-Item -ItemType Directory -Force -Path $taskImage,$taskLog,(Split-Path $taskAvd -Parent) | Out-Null

function Assert-Exit { if ($LASTEXITCODE -ne 0) { throw "Command failed with exit code $LASTEXITCODE" } }
foreach($taskEntry in @(@('emulator-5554','dual_sms_api34'),@('emulator-5556','control_pad_receiver_api26_root'))) {
    $taskNames=@(& $taskAdb -s $taskEntry[0] emu avd name 2>$null)
    if($LASTEXITCODE -eq 0 -and $taskNames[0].Trim() -ne $taskEntry[1]) { throw "端口被其他 AVD 占用：$($taskEntry[0])；请先释放端口。" }
}
function Wait-Boot([string]$Serial) {
    $taskDeadline = (Get-Date).AddMinutes(5)
    while ((Get-Date) -lt $taskDeadline) {
        $taskState = & $taskAdb -s $Serial shell getprop sys.boot_completed 2>$null
        if ($taskState -eq '1') { return }
        Start-Sleep -Seconds 2
    }
    throw "Boot timeout: $Serial. Inspect .local/logs."
}
function Launch-Receiver {
    $taskArgs = @('-avd','control_pad_receiver_api26_root','-port','5556','-no-snapshot','-no-audio','-no-boot-anim')
    if (-not $ShowWindows) { $taskArgs += '-no-window' }
    Start-Process -FilePath $taskEmulator -ArgumentList $taskArgs -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskLog 'receiver-start.log') -RedirectStandardError (Join-Path $taskLog 'receiver-start.err')
    Wait-Boot 'emulator-5556'
}

& $taskSdkManager 'system-images;android-26;google_apis;x86_64' 'platforms;android-36' "--sdk_root=$taskSdk"
Assert-Exit
if (-not (Test-Path (Join-Path $taskImage 'system.img'))) {
    Copy-Item -Path (Join-Path $taskSdk 'system-images\android-26\google_apis\x86_64\*') -Destination $taskImage -Recurse
}
if (-not (Test-Path (Join-Path $taskAvd 'config.ini'))) {
    'no' | & $taskAvdManager create avd -n control_pad_receiver_api26_root -k 'system-images;android-26;google_apis;x86_64' -p $taskAvd --device 'Nexus 7'
    Assert-Exit
}
$taskConfigPath = Join-Path $taskAvd 'config.ini'
$taskConfig = @{}
Get-Content -LiteralPath $taskConfigPath | ForEach-Object { if ($_ -match '^([^=]+)=(.*)$') { $taskConfig[$Matches[1].Trim()] = $Matches[2].Trim() } }
$taskConfig['image.sysdir.1'] = $taskImage.Replace('\','/') + '/'
$taskConfig['hw.lcd.width']='1280'; $taskConfig['hw.lcd.height']='720'; $taskConfig['hw.lcd.density']='160'
$taskConfig['hw.initialOrientation']='landscape'; $taskConfig['hw.ramSize']='2048'; $taskConfig['hw.cpu.ncore']='2'
$taskConfig['disk.dataPartition.size']='4G'; $taskConfig['hw.gpu.enabled']='yes'; $taskConfig['hw.gpu.mode']='auto'
$taskConfig['hw.keyboard']='yes'; $taskConfig['showDeviceFrame']='no'; $taskConfig['fastboot.forceColdBoot']='yes'
$taskConfig.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Value)" } | Set-Content -LiteralPath $taskConfigPath -Encoding ascii

$taskDevices = & $taskAdb devices
if (($taskDevices -join "`n") -notmatch 'emulator-5556\s+device') { Launch-Receiver }
if (-not (Test-Path (Join-Path $taskImage 'ramdisk-original.img'))) {
    if (-not (Test-Path (Join-Path $taskTool '.git'))) { & git clone https://gitlab.com/newbit/rootAVD.git $taskTool; Assert-Exit }
    & git -C $taskTool checkout 613caa44371f85e1a461bc030e07ddc2d71afe32
    Assert-Exit
    $taskMagiskHash = (Get-FileHash (Join-Path $taskTool 'Magisk.zip') -Algorithm SHA256).Hash
    if ($taskMagiskHash -ne '543A96FE26C012D99BAF3A3AA5A97B80508D67CC641AF7C12CE9F7B226B2B889') { throw 'Unexpected Magisk archive hash' }
    & $taskAdb -s emulator-5556 shell mkdir -p /data/data/com.android.shell/controlpad-root
    foreach ($taskName in @('Magisk.zip','rootAVD.sh')) { & $taskAdb -s emulator-5556 push (Join-Path $taskTool $taskName) "/data/data/com.android.shell/controlpad-root/$taskName"; Assert-Exit }
    & $taskAdb -s emulator-5556 push (Join-Path $taskImage 'ramdisk.img') /data/data/com.android.shell/controlpad-root/ramdisk.img
    & $taskAdb -s emulator-5556 shell 'AVDIsOnline=false sh /data/data/com.android.shell/controlpad-root/rootAVD.sh ramdisk.img'
    Assert-Exit
    & $taskAdb -s emulator-5556 pull /data/data/com.android.shell/controlpad-root/ramdiskpatched4AVD.img (Join-Path $taskImage 'ramdisk-magisk.img')
    Assert-Exit
    Copy-Item -LiteralPath (Join-Path $taskImage 'ramdisk.img') -Destination (Join-Path $taskImage 'ramdisk-original.img')
    Copy-Item -LiteralPath (Join-Path $taskImage 'ramdisk-magisk.img') -Destination (Join-Path $taskImage 'ramdisk.img') -Force
    Copy-Item -LiteralPath (Join-Path $taskTool 'Magisk.zip') -Destination (Join-Path $taskRoot '.local\Magisk-v26.4.apk')
    & $taskAdb -s emulator-5556 emu kill
    Start-Sleep -Seconds 4
    Launch-Receiver
}
& $taskAdb -s emulator-5556 install -r (Join-Path $taskRoot '.local\Magisk-v26.4.apk')
Assert-Exit
& $taskAdb -s emulator-5556 root
& $taskAdb -s emulator-5556 wait-for-device
& $taskAdb -s emulator-5556 shell '/sbin/magisk --sqlite "INSERT OR REPLACE INTO policies (uid,policy,until,logging,notification) VALUES (2000,2,0,1,0)"'
& $taskAdb -s emulator-5556 unroot
& $taskAdb -s emulator-5556 wait-for-device
& $taskAdb -s emulator-5556 shell 'su -c id'
Assert-Exit
foreach ($taskPort in @(27183,27184)) { & $taskAdb -s emulator-5556 forward "tcp:$taskPort" "tcp:$taskPort" }
if (((& $taskAdb devices) -join "`n") -notmatch 'emulator-5554\s+device') {
    $taskControllerArgs = @('-avd','dual_sms_api34','-port','5554','-no-audio')
    if (-not $ShowWindows) { $taskControllerArgs += '-no-window' }
    Start-Process -FilePath $taskEmulator -ArgumentList $taskControllerArgs -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskLog 'controller-start.log') -RedirectStandardError (Join-Path $taskLog 'controller-start.err')
    Wait-Boot 'emulator-5554'
}
Write-Host 'Ready: emulator-5554 (Android 14), emulator-5556 (Android 8.0 + Magisk).'
