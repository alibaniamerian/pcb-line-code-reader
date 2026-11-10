param([switch]$Resume)
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
$runner=Join-Path $PSScriptRoot '.sandbox/server-v1.2.0/scripts/run_production_control_sandbox.py'
$directory=Join-Path $PSScriptRoot '.sandbox/stages-001'
if(!$Resume -and (Test-Path -LiteralPath $directory)){throw 'Existing stage test data is preserved. Use -Resume.'}
$arguments=@($runner,'--directory',$directory,'--port','18082')
if($Resume){$arguments+='--resume'}else{$arguments+='--stages'}
& .sandbox/venv/Scripts/python.exe @arguments
if($LASTEXITCODE -ne 0){throw 'Stage sandbox runner failed'}
