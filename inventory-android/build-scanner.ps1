param([switch]$OperatorSandbox,[string]$Sdk="$env:LOCALAPPDATA/Android/Sdk",[string]$Jdk="$PSScriptRoot/.tools/jdk17/jdk-17.0.21+9",[string]$Python="$PSScriptRoot/.tools/python310/tools/python.exe",[string]$Gradle="$PSScriptRoot/.tools/gradle-8.13/bin/gradle.bat")
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
$env:JAVA_HOME=$Jdk
$env:PYTHON310=$Python
$env:GRADLE_USER_HOME="$PSScriptRoot/.tools/gradle-user-home"
$env:ANDROID_USER_HOME="$PSScriptRoot/.android"
$env:ANDROID_HOME=$Sdk
& $Python tests/prepare_inventory_scanner.py
if($LASTEXITCODE -ne 0){throw 'Scanner source preparation failed'}
('sdk.dir='+$Sdk.Replace('\','/')) | Set-Content local.properties -Encoding ASCII
$extra=@()
if($OperatorSandbox){
    & $Python tests/prepare_operator_sandbox.py
    if($LASTEXITCODE -ne 0){throw 'Operator sandbox preparation failed'}
    $extra+='-PoperatorSandbox'
}
& $Gradle :app:assembleDebug --console=plain --no-daemon @extra
if($LASTEXITCODE -ne 0){throw 'Integrated scanner build failed'}
if($OperatorSandbox){
    New-Item -ItemType Directory -Force app/build/operator-sandbox | Out-Null
    Copy-Item -LiteralPath app/build/outputs/apk/debug/app-debug.apk -Destination app/build/operator-sandbox/OzakOperatorTest.apk
    Write-Output 'APK: app/build/operator-sandbox/OzakOperatorTest.apk (synthetic sandbox only)'
}else{Write-Output 'APK: app/build/outputs/apk/debug/app-debug.apk'}
