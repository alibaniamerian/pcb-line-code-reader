$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$listeners=@(Get-NetTCPConnection -LocalPort 18082 -State Listen)
if($listeners.Count -ne 1 -or $listeners[0].LocalAddress -ne '127.0.0.1'){throw 'Unexpected stages listener'}
$process=Get-CimInstance Win32_Process -Filter ('ProcessId='+$listeners[0].OwningProcess)
if(!$process.CommandLine.Contains('C:\INVENTORY MOBILE\.sandbox\server-v1.2.0\scripts\run_production_control_sandbox.py') -or !$process.CommandLine.Contains('C:\INVENTORY MOBILE\.sandbox\stages-001')){throw 'Refusing to stop an unverified process'}
Stop-Process -Id $process.ProcessId
Start-Process powershell.exe -ArgumentList '-NoProfile -ExecutionPolicy Bypass -File "C:\INVENTORY MOBILE\start-stages-sandbox.ps1" -Resume' -WindowStyle Hidden
Write-Output 'Verified stage sandbox restarted with --resume; existing data preserved.'
