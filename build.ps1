param([string]$Sdk=$env:ANDROID_HOME,[string]$Jdk=$env:JAVA_HOME,[string]$Gradle='', [string]$GradleUserHome=$env:GRADLE_USER_HOME)
$ErrorActionPreference='Stop'
$config=Join-Path $PSScriptRoot '.local/build-config.json'
if(Test-Path -LiteralPath $config){$cfg=Get-Content -LiteralPath $config -Raw | ConvertFrom-Json;if(!$Sdk){$Sdk=$cfg.Sdk};if(!$Jdk){$Jdk=$cfg.Jdk}}
if(!$Sdk -or !(Test-Path -LiteralPath $Sdk)){throw 'Set ANDROID_HOME or pass -Sdk.'}
if(!$Jdk -or !(Test-Path -LiteralPath $Jdk)){throw 'Set JAVA_HOME or pass -Jdk (JDK 17).'}
if(!$Gradle){$Gradle=(Get-Command gradle -ErrorAction Stop).Source}
$env:JAVA_HOME=$Jdk
$env:GRADLE_USER_HOME=if($GradleUserHome){$GradleUserHome}else{Join-Path $PSScriptRoot '.local/gradle-user-home'}
$env:ANDROID_USER_HOME=Join-Path $PSScriptRoot '.local/android-user'
('sdk.dir='+$Sdk.Replace('\','/')) | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'local.properties') -Encoding ASCII
& $Gradle -p $PSScriptRoot assembleDebug --console=plain
if($LASTEXITCODE -ne 0){throw 'Line reader build failed'}
Write-Output (Join-Path $PSScriptRoot 'build/outputs/apk/debug/PCBLineReader-debug.apk')
