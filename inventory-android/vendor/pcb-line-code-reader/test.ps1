param([string]$Jdk=$env:JAVA_HOME,[string]$ZxingJar='', [string]$GradleUserHome=$env:GRADLE_USER_HOME)
$ErrorActionPreference='Stop'
if(!$Jdk){throw 'Set JAVA_HOME or pass -Jdk (JDK 17).'}
if(!$GradleUserHome){$GradleUserHome=Join-Path $PSScriptRoot '.local/gradle-user-home'}
if(!$ZxingJar){$pattern=Join-Path $GradleUserHome 'caches/modules-2/files-2.1/com.google.zxing/core/3.5.3/*/core-3.5.3.jar';$ZxingJar=(Get-ChildItem -Path $pattern -ErrorAction SilentlyContinue | Select-Object -First 1).FullName}
if(!$ZxingJar){throw 'Build first or pass -ZxingJar with the ZXing 3.5.3 core JAR.'}
$classes=Join-Path $PSScriptRoot '.local/test-classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
& (Join-Path $Jdk 'bin/javac.exe') -cp $ZxingJar -d $classes (Join-Path $PSScriptRoot 'src/org/datamatrix/linereader/LineDecoder.java') (Join-Path $PSScriptRoot 'tests/LineDecoderTest.java')
if($LASTEXITCODE -ne 0){throw 'Test compilation failed'}
& (Join-Path $Jdk 'bin/java.exe') -cp "$classes;$ZxingJar" org.datamatrix.linereader.LineDecoderTest
if($LASTEXITCODE -ne 0){throw 'Decoder tests failed'}

