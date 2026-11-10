param([switch]$Resume, [ValidateSet('test-001','test-002')][string]$Test='test-001', [switch]$ShortSession)
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
$python=Join-Path $PSScriptRoot '.sandbox/venv/Scripts/python.exe'
$runner=Join-Path $PSScriptRoot '.sandbox/server/scripts/run_production_control_sandbox.py'
$data=Join-Path $PSScriptRoot ('.sandbox/'+$Test)
$port=if($Test -eq 'test-002'){'18081'}else{'18080'}
if($ShortSession){
    if($Test -ne 'test-002'){throw 'Short sessions are restricted to test-002.'}
    $original=[IO.File]::ReadAllText($runner)
    $needle='    from app.core.security import hash_password'
    if(!$original.Contains($needle)){throw 'Unexpected delivered runner version.'}
    $derived=$original.Replace($needle,$needle+"`n    import app.core.security as test_security`n    test_security.ACCESS_TOKEN_EXPIRE_MINUTES = 1  # isolated expiry test only")
    $runner=Join-Path $PSScriptRoot '.sandbox/server/scripts/run_short_session_test.py'
    [IO.File]::WriteAllText($runner,$derived)
}
if (!(Test-Path $python) -or !(Test-Path $runner)) { throw 'Prepare the isolated virtual environment and delivered bundle first.' }
$arguments=@($runner,'--directory',$data,'--port',$port)
if($Resume){$arguments+='--resume'}
& $python @arguments
if($LASTEXITCODE -ne 0){throw 'Sandbox runner failed'}
