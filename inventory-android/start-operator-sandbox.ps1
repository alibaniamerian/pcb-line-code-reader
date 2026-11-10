param([switch]$Resume)
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
$directory=Join-Path $PSScriptRoot '.sandbox/operator-001'
if(!$Resume -and (Test-Path -LiteralPath $directory)){throw 'Existing operator test data is preserved. Use -Resume.'}
$arguments=@('.sandbox/server-v1.2.0/scripts/run_production_control_sandbox.py','--directory',$directory,'--port','18083')
if($Resume){$arguments+='--resume'}else{$arguments+='--stages'}
& .sandbox/venv/Scripts/python.exe @arguments
if($LASTEXITCODE -ne 0){throw 'Operator sandbox failed'}
