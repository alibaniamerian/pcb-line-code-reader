param(
    [string]$Sdk = "$env:LOCALAPPDATA/Android/Sdk",
    [string]$Jdk = 'C:/Program Files/Android/Android Studio/jbr',
    [string]$Platform = 'android-37.0',
    [switch]$Sandbox,
    [switch]$Advanced,
    [switch]$Stages
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if($Stages){$Sandbox=$true;if($Advanced){throw 'Stages uses its own isolated scenario.'}; & .sandbox/venv/Scripts/python.exe tests/prepare_stage_harness.py; if($LASTEXITCODE -ne 0){throw 'Stage harness generation failed'}}
if($Advanced -and !$Sandbox){throw 'Advanced requires -Sandbox.'}
$bt = Join-Path $Sdk 'build-tools/36.0.0'
$androidJar = Join-Path $Sdk "platforms/$Platform/android.jar"
$out = Join-Path $PSScriptRoot 'app/build/pilot'
if($Sandbox){$out=Join-Path $PSScriptRoot 'app/build/sandbox'}
if($Advanced){$out=Join-Path $PSScriptRoot 'app/build/sandbox-next'}
if($Stages){$out=Join-Path $PSScriptRoot 'app/build/sandbox-stages'}
$env:JAVA_HOME = $Jdk
function Run([string]$exe, [string[]]$arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "Build tool failed: $exe ($LASTEXITCODE)" }
}
New-Item -ItemType Directory -Force "$out/classes", "$out/dex", "$out/generated", '.android' | Out-Null
# Copy platform stubs into the workspace to avoid JDK ZIP close errors in the sandbox.
Copy-Item $androidJar "$out/android.jar" -Force
$androidJar = "$out/android.jar"
Copy-Item "$bt/core-lambda-stubs.jar" "$out/core-lambda-stubs.jar" -Force
& "$PSScriptRoot/prepare-bridge-deps.ps1"
$bridgeJars = @('webkit','core','annotation-experimental','annotation') | ForEach-Object { Join-Path $PSScriptRoot ".tools/bridge/$_.jar" }
if($Sandbox){$bridgeJars+=Join-Path $PSScriptRoot '.tools/bridge/zxing.jar'}
# Only the WebMessageListener path is used; Gradle remains the full dependency build.
Run "$bt/aapt2.exe" @('compile','--dir','app/src/main/res','-o',"$out/resources.zip")
$manifest = (Get-Content 'app/src/main/AndroidManifest.xml' -Raw).Replace('<manifest ', '<manifest package="com.ozak.inventory" ')
if($Sandbox){
    $manifest=Get-Content 'app/src/sandbox/AndroidManifest.xml' -Raw
    if($Advanced){$manifest=$manifest.Replace('com.ozak.inventory.sandbox','com.ozak.inventory.sandbox.next').Replace('OZAK Sandbox ONLY','OZAK Sandbox NEXT')}
    if($Stages){$manifest=$manifest.Replace('com.ozak.inventory.sandbox','com.ozak.inventory.sandbox.stages').Replace('OZAK Sandbox ONLY','OZAK Stages TEST').Replace('com.ozak.inventory.SandboxActivity','com.ozak.inventory.StageTestActivity')}
    Run "$bt/aapt2.exe" @('compile','--dir','app/src/sandbox/res','-o',"$out/sandbox-resources.zip")
}
[IO.File]::WriteAllText("$out/AndroidManifest.xml", $manifest)
$resourceInputs=@("$out/resources.zip")
if($Sandbox){$resourceInputs+="$out/sandbox-resources.zip"}
Run "$bt/aapt2.exe" (@('link','-o',"$out/resources.apk",'--manifest',"$out/AndroidManifest.xml",'-I',$androidJar,'--java',"$out/generated",'--min-sdk-version','26','--target-sdk-version','36','--version-code','1','--version-name','0.1.0-login-pilot')+$resourceInputs)
$sources = @(Get-ChildItem app/src/main/java -Recurse -Filter '*.java' | ForEach-Object FullName)
if($Sandbox){$sources=@(Get-ChildItem app/src/sandbox/java -Recurse -Filter '*.java' | ForEach-Object FullName)+@((Join-Path $PSScriptRoot 'app/src/main/java/com/ozak/inventory/BridgePolicy.java'))}
if($Stages){$sources+=@(Get-ChildItem app/src/stageTest/java -Recurse -Filter '*.java' | ForEach-Object FullName)}
if($Sandbox){
    $port=if($Advanced){18081}else{18080}
    $appId=if($Advanced){'com.ozak.inventory.sandbox.next'}else{'com.ozak.inventory.sandbox'}
    $flag=if($Advanced){'true'}else{'false'}
    if($Stages){$port=18082;$appId='com.ozak.inventory.sandbox.stages'}
    [IO.File]::WriteAllText("$out/generated/SandboxBuild.java", "package com.ozak.inventory; final class SandboxBuild { static final int PORT=$port; static final String APP_ID=`"$appId`"; static final boolean ADVANCED=$flag; }")
}
$sources += @(Get-ChildItem "$out/generated" -Recurse -Filter '*.java' | ForEach-Object FullName)
Run "$Jdk/bin/javac.exe" (@('-encoding','UTF-8','-source','8','-target','8','-bootclasspath',"$out/core-lambda-stubs.jar;$androidJar",'-classpath',($bridgeJars -join ';'),'-d',"$out/classes") + $sources)
Run "$Jdk/bin/jar.exe" @('cf',"$out/classes.jar",'-C',"$out/classes",'.')
Run "$Jdk/bin/java.exe" (@('-cp',"$bt/lib/d8.jar",'com.android.tools.r8.D8','--lib',$androidJar,'--min-api','26','--output',"$out/dex","$out/classes.jar") + $bridgeJars)
Copy-Item "$out/resources.apk" "$out/unsigned.apk" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open("$out/unsigned.apk", 'Update')
try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$out/dex/classes.dex",'classes.dex') | Out-Null } finally { $zip.Dispose() }
Run "$bt/zipalign.exe" @('-f','4',"$out/unsigned.apk","$out/aligned.apk")
$key = Join-Path $PSScriptRoot '.android/pilot-debug.keystore'
if (!(Test-Path $key)) {
    Run "$Jdk/bin/keytool.exe" @('-genkeypair','-keystore',$key,'-storepass','android','-keypass','android','-alias','androiddebugkey','-dname','CN=Android Debug,O=Android,C=US','-keyalg','RSA','-validity','10000')
}
Run "$Jdk/bin/java.exe" @('-jar',"$bt/lib/apksigner.jar",'sign','--ks',$key,'--ks-pass','pass:android','--key-pass','pass:android','--out',"$out/OzakInventory-login-pilot.apk","$out/aligned.apk")
Run "$Jdk/bin/java.exe" @('-jar',"$bt/lib/apksigner.jar",'verify',"$out/OzakInventory-login-pilot.apk")
Write-Output "APK: $out/OzakInventory-login-pilot.apk"
