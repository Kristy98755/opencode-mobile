$ErrorActionPreference = "Stop"
$dir = "C:\Users\XAKEP\AndroidStudioProjects\OpenCode Mobile\agent"
$user = "$env:USERDOMAIN\$env:USERNAME"

# stop the old console-window incarnation (task action = run_agent.cmd)
Stop-ScheduledTask -TaskName "OpenCodeConnectAgent" -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1
# orphan cleanup: node agent + any leftover cmd wrapper of run_agent.cmd
Get-CimInstance Win32_Process -Filter "Name='node.exe' OR Name='cmd.exe'" |
    Where-Object { $_.CommandLine -match "agent\.mjs|run_agent\.cmd" } |
    ForEach-Object { "killing $($_.ProcessId): $($_.Name)"; Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 1

$action = New-ScheduledTaskAction -Execute "$dir\ocmtray.exe" -WorkingDirectory $dir
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable `
    -RestartCount 99 -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -MultipleInstances IgnoreNew
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName "OpenCodeConnectAgent" -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null
Start-ScheduledTask -TaskName "OpenCodeConnectAgent"
Start-Sleep -Seconds 6
"--- task ---"
Get-ScheduledTask -TaskName "OpenCodeConnectAgent" | ForEach-Object { "state=$($_.State)" }
"--- processes ---"
Get-CimInstance Win32_Process -Filter "Name='node.exe' OR Name='ocmtray.exe' OR Name='cmd.exe'" |
    Where-Object { $_.CommandLine -match "agent|ocmtray" } |
    ForEach-Object { "$($_.ProcessId) $($_.Name)" }
