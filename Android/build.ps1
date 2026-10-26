# Builds FireDrop.apk with the Android SDK command line tools only (no Gradle, no AndroidX).
# Usage:
#   .\build.ps1 -Sdk "C:\Android\sdk" -Jdk "C:\Java\jdk17" -Version 1.2.1
# Defaults point at the toolchain prepared on this machine.

param(
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$Jdk = $env:JAVA_HOME,
    [string]$BuildTools = "34.0.0",
    [string]$Platform = "android-34",
    [string]$Version = "1.2.3",
    [int]$VersionCode = 6,
    [string]$OutDir = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$app = Join-Path $root "app"

if ([string]::IsNullOrWhiteSpace($Sdk)) {
    $Sdk = "C:\Users\MECHREVO\AppData\Local\Temp\opencode\android-tools\sdk"
}
if ([string]::IsNullOrWhiteSpace($Jdk)) {
    $Jdk = "C:\Users\MECHREVO\AppData\Local\Temp\opencode\android-tools\jdk17\jdk-17.0.20.1+1"
}
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path $root "build"
}

$bt = Join-Path $Sdk "build-tools\$BuildTools"
$aapt2 = Join-Path $bt "aapt2.exe"
$zipalign = Join-Path $bt "zipalign.exe"
$apksigner = Join-Path $bt "apksigner.bat"
$androidJar = Join-Path $Sdk "platforms\$Platform\android.jar"
$javac = Join-Path $Jdk "bin\javac.exe"
$jar = Join-Path $Jdk "bin\jar.exe"
$keytool = Join-Path $Jdk "bin\keytool.exe"

foreach ($tool in @($aapt2, $zipalign, $apksigner, $androidJar, $javac, $jar, $keytool)) {
    if (-not (Test-Path $tool)) { throw "Missing tool: $tool" }
}

# d8.bat and apksigner.bat start their own JVM through JAVA_HOME.
$env:JAVA_HOME = $Jdk
$env:PATH = (Join-Path $Jdk "bin") + ";" + $env:PATH

$work = Join-Path $OutDir "work"
if (Test-Path $work) { Remove-Item $work -Recurse -Force }
New-Item -ItemType Directory -Force -Path $work | Out-Null
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$resZip = Join-Path $work "resources.zip"
$genDir = Join-Path $work "gen"
$unsignApk = Join-Path $work "app.unsigned.apk"
$alignedApk = Join-Path $work "app.aligned.apk"
$classesDir = Join-Path $work "classes"
$classesJar = Join-Path $work "classes.jar"
$dexDir = Join-Path $work "dex"

Write-Host "[1/9] aapt2 compile"
& $aapt2 compile --dir (Join-Path $app "res") -o $resZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "[2/9] aapt2 link"
& $aapt2 link -o $unsignApk -I $androidJar `
    --manifest (Join-Path $app "AndroidManifest.xml") `
    -R $resZip --java $genDir `
    --min-sdk-version 26 --target-sdk-version 34 `
    --version-code $VersionCode --version-name $Version `
    --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[3/9] javac"
New-Item -ItemType Directory -Force -Path $classesDir | Out-Null
$sources = @()
$sources += Get-ChildItem (Join-Path $app "java") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$sources += Get-ChildItem $genDir -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$sourcesFile = Join-Path $work "sources.txt"
$sources | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' } | Set-Content -Path $sourcesFile -Encoding ASCII

# Third-party dependency com.google.zxing:core 3.5.3 (Apache-2.0) is not stored
# in the repository: the jar is downloaded from Maven Central and verified by
# SHA-256, as required for a free-software release (F-Droid).
$zxing = Join-Path $root "libs\zxing-core-3.5.3.jar"
$zxingSha256 = "8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82"
if (-not (Test-Path $zxing)) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $zxing) | Out-Null
    Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar" `
        -OutFile $zxing -UseBasicParsing
    $actual = (Get-FileHash $zxing -Algorithm SHA256).Hash.ToLower()
    if ($actual -ne $zxingSha256) {
        Remove-Item $zxing -Force
        throw "SHA-256 mismatch for zxing-core-3.5.3.jar: $actual"
    }
}

& $javac -source 8 -target 8 -encoding UTF-8 -nowarn -Xlint:none `
    -classpath "$androidJar;$zxing" -d $classesDir "@$sourcesFile"
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[4/9] jar"
& $jar cf $classesJar -C $classesDir .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

Write-Host "[5/9] d8"
New-Item -ItemType Directory -Force -Path $dexDir | Out-Null
& (Join-Path $bt "d8.bat") --release --min-api 26 --lib $androidJar --output $dexDir $classesJar $zxing
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[6/9] package classes.dex"
$dex = Join-Path $dexDir "classes.dex"
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($unsignApk, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $existing = $zip.GetEntry("classes.dex")
    if ($existing) { $existing.Delete() }
    $entry = $zip.CreateEntry("classes.dex", [System.IO.Compression.CompressionLevel]::Optimal)
    $stream = $entry.Open()
    $bytes = [System.IO.File]::ReadAllBytes($dex)
    $stream.Write($bytes, 0, $bytes.Length)
    $stream.Dispose()
} finally {
    $zip.Dispose()
}

Write-Host "[7/9] resources.arsc uncompressed"
# Android 11+ refuses APKs whose resources.arsc is compressed or not 4-byte aligned.
$zip = [System.IO.Compression.ZipFile]::Open($unsignApk, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $arsc = $zip.GetEntry("resources.arsc")
    if ($null -ne $arsc -and $arsc.CompressedLength -ne $arsc.Length) {
        $length = [int]$arsc.Length
        $bytes = New-Object byte[] $length
        $source = $arsc.Open()
        try {
            $offset = 0
            while ($offset -lt $length) {
                $read = $source.Read($bytes, $offset, $length - $offset)
                if ($read -le 0) { break }
                $offset += $read
            }
        } finally {
            $source.Dispose()
        }
        $arsc.Delete()
        $entry = $zip.CreateEntry("resources.arsc", [System.IO.Compression.CompressionLevel]::NoCompression)
        $target = $entry.Open()
        try {
            $target.Write($bytes, 0, $bytes.Length)
        } finally {
            $target.Dispose()
        }
    }
} finally {
    $zip.Dispose()
}

Write-Host "[8/9] zipalign"
& $zipalign -p -f 4 $unsignApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[9/9] apksigner"
$keystore = Join-Path $root "keystore\firedrop.jks"
$storePass = "firedrop"
if (-not (Test-Path $keystore)) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $keystore) | Out-Null
    & $keytool -genkeypair -keystore $keystore -alias firedrop -keyalg RSA -keysize 2048 `
        -validity 12000 -storepass $storePass -keypass $storePass `
        -dname "CN=FireDrop, OU=FireDrop, O=FireDrop, L=Local, C=RU"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

$finalApk = Join-Path $OutDir "FireDrop-$Version.apk"
& $apksigner sign --ks $keystore --ks-pass "pass:$storePass" --key-pass "pass:$storePass" `
    --out $finalApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

& $apksigner verify --print-certs $finalApk
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed" }

$size = (Get-Item $finalApk).Length
Write-Host ""
Write-Host "APK: $finalApk"
Write-Host ("Size: {0:N0} bytes" -f $size)
Write-Host ("SHA256: " + (Get-FileHash $finalApk -Algorithm SHA256).Hash)


