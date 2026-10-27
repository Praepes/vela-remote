param([switch]$ShowWindows)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskSdk=Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$taskAdb=Join-Path $taskSdk 'platform-tools\adb.exe'
$taskEmu=Join-Path $taskSdk 'emulator\emulator.exe'
$taskLog=Join-Path $taskRoot '.local\logs'
$taskExpected=@{ 'emulator-5554'='dual_sms_api34'; 'emulator-5556'='control_pad_receiver_api26_root' }
foreach($taskSerial in $taskExpected.Keys) {
    $taskNameLines=@(& $taskAdb -s $taskSerial emu avd name 2>$null)
    if($LASTEXITCODE -eq 0) {
        if($taskNameLines[0].Trim() -ne $taskExpected[$taskSerial]) { throw "Unexpected AVD at $taskSerial; refusing to stop it." }
        & $taskAdb -s $taskSerial emu kill
    }
}
Start-Sleep -Seconds 4
$taskReceiverArgs=@('-avd','control_pad_receiver_api26_root','-port','5556','-wifi-server-port','27500','-no-snapshot','-no-audio','-no-boot-anim')
$taskControllerArgs=@('-avd','dual_sms_api34','-port','5554','-wifi-client-port','27500','-no-snapshot','-no-audio','-no-boot-anim','-cores','2')
if(-not $ShowWindows) { $taskReceiverArgs+='-no-window'; $taskControllerArgs+='-no-window' }
Start-Process -FilePath $taskEmu -ArgumentList $taskReceiverArgs -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskLog 'receiver-wifi.log') -RedirectStandardError (Join-Path $taskLog 'receiver-wifi.err')
Start-Sleep -Seconds 2
Start-Process -FilePath $taskEmu -ArgumentList $taskControllerArgs -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskLog 'controller-wifi.log') -RedirectStandardError (Join-Path $taskLog 'controller-wifi.err')
$taskDeadline=(Get-Date).AddMinutes(5)
foreach($taskSerial in @('emulator-5556','emulator-5554')) {
    do {
        $taskReady=& $taskAdb -s $taskSerial shell getprop sys.boot_completed 2>$null
        if($taskReady -eq '1') { break }
        Start-Sleep -Seconds 2
    } while((Get-Date) -lt $taskDeadline)
    if($taskReady -ne '1') { throw "Boot timeout: $taskSerial" }
}
foreach($taskPort in @(27183,27184)) { & $taskAdb -s emulator-5556 forward "tcp:$taskPort" "tcp:$taskPort" }
Write-Host 'Shared Wi-Fi boot complete; wait for Android BOOT_COMPLETED services before discovery tests.'
