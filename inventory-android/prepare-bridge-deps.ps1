$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
Add-Type -AssemblyName System.IO.Compression.FileSystem
$dir = Join-Path $PSScriptRoot '.tools/bridge'
New-Item -ItemType Directory -Force $dir | Out-Null
$artifacts = @{
    'webkit' = 'androidx/webkit/webkit/1.12.1/webkit-1.12.1.aar'
    'core' = 'androidx/core/core/1.1.0/core-1.1.0.aar'
    'annotation-experimental' = 'androidx/annotation/annotation-experimental/1.4.1/annotation-experimental-1.4.1.aar'
}
foreach ($name in $artifacts.Keys) {
    $archivePath = Join-Path $dir "$name.aar"
    if (!(Test-Path $archivePath)) { Invoke-WebRequest -UseBasicParsing ("https://dl.google.com/dl/android/maven2/" + $artifacts[$name]) -OutFile $archivePath }
    $archive = [IO.Compression.ZipFile]::OpenRead($archivePath)
    try { [IO.Compression.ZipFileExtensions]::ExtractToFile($archive.GetEntry('classes.jar'), (Join-Path $dir "$name.jar"), $true) } finally { $archive.Dispose() }
}
$annotation = Join-Path $dir 'annotation.jar'
if (!(Test-Path $annotation)) { Invoke-WebRequest -UseBasicParsing 'https://dl.google.com/dl/android/maven2/androidx/annotation/annotation-jvm/1.8.1/annotation-jvm-1.8.1.jar' -OutFile $annotation }
Write-Output 'AndroidX WebKit bridge libraries prepared.'
$zxing = Join-Path $dir 'zxing.jar'
if (!(Test-Path $zxing)) { Invoke-WebRequest -UseBasicParsing 'https://repo.maven.apache.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar' -OutFile $zxing }
