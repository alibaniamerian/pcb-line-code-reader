param([string]$Jdk = 'C:/Program Files/Android/Android Studio/jbr')
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$outputDir = Join-Path $PSScriptRoot 'app/build/mock-tests'
New-Item -ItemType Directory -Force $outputDir | Out-Null
& "$Jdk/bin/javac.exe" -encoding UTF-8 -d $outputDir app/src/main/java/com/ozak/inventory/DemoStore.java app/src/main/java/com/ozak/inventory/BridgePolicy.java tests/com/ozak/inventory/DemoStoreTest.java tests/com/ozak/inventory/BridgePolicyTest.java
if ($LASTEXITCODE -ne 0) { throw 'Mock test compilation failed' }
& "$Jdk/bin/java.exe" -cp $outputDir com.ozak.inventory.DemoStoreTest
if ($LASTEXITCODE -ne 0) { throw 'Mock tests failed' }
& "$Jdk/bin/java.exe" -cp $outputDir com.ozak.inventory.BridgePolicyTest
if ($LASTEXITCODE -ne 0) { throw 'Bridge policy tests failed' }
