param(
    [string]$IdePath = "C:\Users\sfclo\AppData\Local\Programs\PyCharm",
    [switch]$Install,
    [switch]$NoClean
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$version = "1.0.0"
$name = "CyberStrike-UI"

$javac = Join-Path $IdePath "jbr\bin\javac.exe"
$java = Join-Path $IdePath "jbr\bin\java.exe"
if (-not (Test-Path $javac)) { throw "javac not found at $javac" }

Write-Host "Using JDK: $javac"
& $javac -version

$out = Join-Path $root "build\classes"
$pluginDir = Join-Path $root "build\plugin\$name"
$libDir = Join-Path $pluginDir "lib"
$jarPath = Join-Path $libDir "$name-$version.jar"

if (-not $NoClean) {
    if (Test-Path (Join-Path $root "build")) { Remove-Item (Join-Path $root "build") -Recurse -Force }
}
New-Item -ItemType Directory -Path $out -Force | Out-Null
New-Item -ItemType Directory -Path $libDir -Force | Out-Null

# Classpath: the IDE itself + the terminal plugin it bundles.
$cp = @()
$cp += (Get-ChildItem (Join-Path $IdePath "lib\*.jar") | ForEach-Object { $_.FullName })
foreach ($p in @("plugins\terminal\lib", "plugins\platform-images\lib")) {
    $dir = Join-Path $IdePath $p
    if (Test-Path $dir) { $cp += (Get-ChildItem "$dir\*.jar" | ForEach-Object { $_.FullName }) }
}
$cpString = $cp -join ";"

$sources = Get-ChildItem (Join-Path $root "src\main\java") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Write-Host "Compiling $($sources.Count) sources against $IdePath"

$argFile = Join-Path $root "build\javac.args"
$lines = @()
$lines += "-d `"$($out.Replace('\','/'))`""
$lines += "-encoding UTF-8"
$lines += "-nowarn"
$lines += "-classpath `"$($cpString.Replace('\','/'))`""
foreach ($s in $sources) { $lines += "`"$($s.Replace('\','/'))`"" }
[System.IO.File]::WriteAllLines($argFile, $lines, (New-Object System.Text.UTF8Encoding($false)))

& $javac "@$argFile"
if ($LASTEXITCODE -ne 0) { throw "Compilation failed ($LASTEXITCODE)" }
Write-Host "Compiled OK" -ForegroundColor Green

# Resources
Copy-Item (Join-Path $root "src\main\resources\*") $out -Recurse -Force

# Package a jar (no jar.exe in JBR: build it with .NET)
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
if (Test-Path $jarPath) { Remove-Item $jarPath -Force }
$zip = [System.IO.Compression.ZipFile]::Open($jarPath, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    Get-ChildItem $out -Recurse -File | ForEach-Object {
        $rel = $_.FullName.Substring($out.Length + 1).Replace('\', '/')
        [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $rel)
    }
} finally {
    $zip.Dispose()
}
Write-Host "Jar: $jarPath" -ForegroundColor Green

# Plugin zip (JetBrains layout: <name>/lib/*.jar)
$dist = Join-Path $root "build\distributions"
New-Item -ItemType Directory -Path $dist -Force | Out-Null
$zipPath = Join-Path $dist "$name-$version.zip"
if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
Compress-Archive -Path $pluginDir -DestinationPath $zipPath -Force
Write-Host "Zip: $zipPath" -ForegroundColor Green

if ($Install) {
    # JetBrains config dir for PyCharm: <APPDATA>\JetBrains\<dataDirectoryName>\plugins
    $productInfo = Get-Content (Join-Path $IdePath "product-info.json") -Raw | ConvertFrom-Json
    $dataDir = $productInfo.dataDirectoryName
    $pluginsRoot = Join-Path $env:APPDATA "JetBrains\$dataDir\plugins"
    New-Item -ItemType Directory -Path $pluginsRoot -Force | Out-Null
    $target = Join-Path $pluginsRoot $name
    if (Test-Path $target) { Remove-Item $target -Recurse -Force }
    Copy-Item $pluginDir $target -Recurse -Force
    Write-Host "Installed to: $target" -ForegroundColor Cyan
    Write-Host "Restart the IDE (or use Help > Restart IDE) to load the plugin." -ForegroundColor Yellow
}
