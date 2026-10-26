# Sender smoke test for FireDrop 1.2.0: exercises PhoneSender (the PC's
# "send to phone" engine) against a real running FireDrop receiver.
# ASCII-only source: Cyrillic file names are built from code points so
# Windows PowerShell 5.1 cannot mangle them.

param(
    [string]$Exe = "C:\Users\MECHREVO\Desktop\se\dist\FireDrop.exe",
    [string]$Harness = "C:\Users\MECHREVO\Desktop\se\verification\sender-harness\bin\Release\net10.0-windows\SenderHarness.exe"
)

$ErrorActionPreference = "Stop"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$results = New-Object System.Collections.Generic.List[object]

function Check([string]$Name, [bool]$Ok, [string]$Detail = "") {
    $tag = if ($Ok) { "PASS" } else { "FAIL" }
    $suffix = if ($Detail) { " - $Detail" } else { "" }
    Write-Host ("[{0}] {1}{2}" -f $tag, $Name, $suffix)
    $results.Add([pscustomobject]@{ Name = $Name; Ok = $Ok; Detail = $Detail })
}

$proc = $null
$save = $null
$sourceRoot = $null
$sentRoot = $null
try {
    Get-Process FireDrop -ErrorAction SilentlyContinue | Stop-Process -Force
    Start-Sleep -Milliseconds 500

    $settings = Get-Content (Join-Path $env:APPDATA "FireDrop\settings.json") -Raw | ConvertFrom-Json
    $save = $settings.saveFolder
    $port = [int]$settings.port

    $proc = Start-Process -FilePath $Exe -PassThru
    Check "Receiver EXE starts" ($null -ne $proc) "pid $($proc.Id)"

    $base = "http://127.0.0.1:$port"
    $healthy = $false
    for ($i = 0; $i -lt 40; $i++) {
        try {
            Invoke-RestMethod -Uri "$base/api/health" -TimeoutSec 3 | Out-Null
            $healthy = $true
            break
        } catch {
            Start-Sleep -Milliseconds 300
        }
    }
    Check "Receiver server responds" $healthy

    # ---- PIN from the UI automation tree (same as release-smoke.ps1).
    Add-Type -AssemblyName UIAutomationClient
    Add-Type -AssemblyName UIAutomationTypes

    $win = $null
    for ($i = 0; $i -lt 30; $i++) {
        $cond = New-Object System.Windows.Automation.PropertyCondition(
            [System.Windows.Automation.AutomationElement]::ProcessIdProperty, $proc.Id)
        $win = [System.Windows.Automation.AutomationElement]::RootElement.FindFirst(
            [System.Windows.Automation.TreeScope]::Children, $cond)
        if ($null -ne $win) { break }
        Start-Sleep -Milliseconds 300
    }

    $pin = ""
    for ($attempt = 0; $attempt -lt 20 -and $pin -eq ""; $attempt++) {
        $all = $win.FindAll([System.Windows.Automation.TreeScope]::Descendants,
            [System.Windows.Automation.Condition]::TrueCondition)
        foreach ($element in $all) {
            if ($element.Current.ControlType.ProgrammaticName -ne "ControlType.List") { continue }
            $digits = $element.Current.Name -replace '\D', ''
            if ($digits.Length -eq 6) {
                $pin = $digits
                break
            }
        }
        if ($pin -eq "") { Start-Sleep -Milliseconds 300 }
    }
    Check "PIN read from UI" ($pin -match '^\d{6}$') "digits=$($pin.Length)"

    # ---- Prepare a folder tree to send: flat file, 9 MiB nested file, deep Cyrillic file.
    $sourceRoot = Join-Path $env:TEMP "FD-source-$stamp"
    $rootName = "FD-source-$stamp"
    $plain = Join-Path $sourceRoot "plain.txt"
    $nested = Join-Path (Join-Path $sourceRoot "sub") "big.bin"
    $deepFolder = -join ([char[]](0x0433, 0x043B, 0x0443, 0x0431, 0x043E))   # "gluboko"
    $cyName = -join ([char[]](0x0444, 0x0430, 0x0439, 0x043B)) + ".txt"      # "fail.txt"
    $deepFile = Join-Path (Join-Path $sourceRoot $deepFolder) $cyName

    New-Item -ItemType Directory -Path (Split-Path $nested) -Force | Out-Null
    New-Item -ItemType Directory -Path (Split-Path $deepFile) -Force | Out-Null

    $plainText = "FireDrop sender smoke $stamp"
    [IO.File]::WriteAllText($plain, $plainText, [System.Text.Encoding]::UTF8)

    $bigBytes = New-Object byte[] (9 * 1024 * 1024)
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $random.GetBytes($bigBytes)
    [IO.File]::WriteAllBytes($nested, $bigBytes)

    $deepText = "shallow out of a deep folder"
    [IO.File]::WriteAllText($deepFile, $deepText, [System.Text.Encoding]::UTF8)

    $hashPlain = (Get-FileHash -LiteralPath $plain -Algorithm SHA256).Hash
    $hashBig = (Get-FileHash -LiteralPath $nested -Algorithm SHA256).Hash
    $hashDeep = (Get-FileHash -LiteralPath $deepFile -Algorithm SHA256).Hash

    # ---- Drive the real PhoneSender class.
    $harnessOut = & $Harness "127.0.0.1:$port" $pin $sourceRoot
    $harnessExit = $LASTEXITCODE
    $harnessOut | ForEach-Object { Write-Host ("    harness> " + $_) }

    Check "Harness ran the sender" ($harnessExit -eq 0) "exit=$harnessExit"
    if ($harnessOut -match 'probe ok=True') {
        Check "Health probe recognized FireDrop" $true ($harnessOut | Where-Object { $_ -like 'probe*' } | Select-Object -First 1)
    } else {
        Check "Health probe recognized FireDrop" $false (($harnessOut | Where-Object { $_ -like 'probe*' } | Select-Object -First 1))
    }
    if ($harnessOut -match 'completed=3/3') {
        Check "All 3 files completed" $true
    } else {
        Check "All 3 files completed" $false ($harnessOut | Where-Object { $_ -like 'device=*completed*' } | Select-Object -First 1)
    }

    # ---- Verify files landed in the save folder with the same structure.
    $sentRoot = Join-Path $save $rootName
    $gotPlain = Join-Path $sentRoot "plain.txt"
    $gotNested = Join-Path (Join-Path (Join-Path $save $rootName) "sub") "big.bin"
    $gotDeep = Join-Path (Join-Path (Join-Path $save $rootName) $deepFolder) $cyName

    Check "Flat file exists" (Test-Path -LiteralPath $gotPlain) $gotPlain
    Check "Nested 9 MiB file exists" (Test-Path -LiteralPath $gotNested) $gotNested
    Check "Deep Cyrillic file exists" (Test-Path -LiteralPath $gotDeep) $gotDeep

    if ((Test-Path -LiteralPath $gotPlain) -and (Test-Path -LiteralPath $gotNested) -and (Test-Path -LiteralPath $gotDeep)) {
        Check "Plain content matches" ((Get-Content -LiteralPath $gotPlain -Raw).Trim() -eq $plainText)
        Check "Big file SHA-256 matches" ((Get-FileHash -LiteralPath $gotNested -Algorithm SHA256).Hash -eq $hashBig) $hashBig.Substring(0, 16)
        Check "Deep file SHA-256 matches" ((Get-FileHash -LiteralPath $gotDeep -Algorithm SHA256).Hash -eq $hashDeep)
    }
} finally {
    if ($null -ne $proc -and -not $proc.HasExited) {
        $proc.CloseMainWindow() | Out-Null
        if (-not $proc.WaitForExit(5000)) {
            Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        }
    }
    Start-Sleep -Milliseconds 500

    foreach ($dir in @($sourceRoot, $sentRoot, (Join-Path (Join-Path $save "..") $rootName))) {
        if ($dir -and (Test-Path -LiteralPath $dir)) {
            Remove-Item -LiteralPath $dir -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

$failed = @($results | Where-Object { -not $_.Ok }).Count
Write-Host ""
Write-Host ("RESULT: {0}/{1} passed" -f ($results.Count - $failed), $results.Count)
if ($failed -gt 0) { exit 1 }
