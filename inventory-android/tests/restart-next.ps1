param([switch]$ShortSession)
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$listeners=@(Get-NetTCPConnection -LocalPort 18081 -State Listen)
if($listeners.Count -ne 1 -or $listeners[0].LocalAddress -ne '127.0.0.1'){throw 'Unexpected test-002 listener'}
$process=Get-CimInstance Win32_Process -Filter ('ProcessId='+$listeners[0].OwningProcess)
if(!$process.CommandLine.Contains('C:\INVENTORY MOBILE\.sandbox\server\scripts\run_') -or !$process.CommandLine.Contains('C:\INVENTORY MOBILE\.sandbox\test-002')){throw 'Refusing to stop an unverified process'}
Stop-Process -Id $process.ProcessId
$argsLine='-NoProfile -ExecutionPolicy Bypass -File "C:\INVENTORY MOBILE\start-sandbox.ps1" -Test test-002 -Resume'
if($ShortSession){$argsLine+=' -ShortSession'}
Start-Process powershell.exe -ArgumentList $argsLine -WindowStyle Hidden
Write-Output 'Verified test-002 server restarted with --resume.'
