# Release smoke test for FireDrop 1.2.3.
# Starts the published EXE, reads the PIN from the UI automation tree, then exercises
# the real HTTP API and UDP discovery. ASCII-only source: Cyrillic test names are
# built from code points so Windows PowerShell 5.1 cannot mangle them.

param(
    [string]$Exe = "C:\Users\MECHREVO\Desktop\se\dist\FireDrop.exe"
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

function Post-Json([string]$Uri, $Payload) {
    $json = $Payload | ConvertTo-Json -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    Invoke-RestMethod -Method Post -Uri $Uri -Body $bytes `
        -ContentType "application/json; charset=utf-8" -WebSession $script:session -TimeoutSec 30
}

function Put-Bytes([string]$Uri, [byte[]]$Bytes) {
    $request = [System.Net.HttpWebRequest]::Create($Uri)
    $request.Method = "PUT"
    $request.ContentType = "application/octet-stream"
    $request.ContentLength = $Bytes.Length
    $request.Timeout = 180000
    $request.ReadWriteTimeout = 180000
    $request.ServicePoint.Expect100Continue = $false
    $request.Headers.Add("Cookie", $script:session.Cookies.GetCookieHeader([Uri]$script:base))

    $stream = $request.GetRequestStream()
    try {
        $stream.Write($Bytes, 0, $Bytes.Length)
    } finally {
        $stream.Close()
    }

    $response = $request.GetResponse()
    try {
        $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
        try {
            return $reader.ReadToEnd()
        } finally {
            $reader.Close()
        }
    } finally {
        $response.Close()
    }
}

$proc = $null
$save = $null
$root = $null
try {
    Get-Process FireDrop -ErrorAction SilentlyContinue | Stop-Process -Force
    Start-Sleep -Milliseconds 500

    $settings = Get-Content (Join-Path $env:APPDATA "FireDrop\settings.json") -Raw | ConvertFrom-Json
    $save = $settings.saveFolder
    $port = [int]$settings.port
    $base = "http://127.0.0.1:$port"

    $proc = Start-Process -FilePath $Exe -PassThru
    Check "Release EXE starts" ($null -ne $proc) "pid $($proc.Id)"

    $healthy = $false
    $health = $null
    for ($i = 0; $i -lt 40; $i++) {
        try {
            $health = Invoke-RestMethod -Uri "$base/api/health" -TimeoutSec 3
            $healthy = $true
            break
        } catch {
            Start-Sleep -Milliseconds 300
        }
    }
    Check "HTTP server responds" $healthy
    Check "Reported version is 1.2.3" ($healthy -and $health.version -eq "1.2.3") "version=$($health.version)"

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
    Check "Main window appears" ($null -ne $win) $win.Current.Name

    $pin = ""
    if ($null -ne $win) {
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
    }
    Check "PIN read from UI" ($pin -match '^\d{6}$') "digits=$($pin.Length)"

    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $join = Post-Json "$base/api/join" @{ pin = $pin }
    Check "PIN join accepted" ($join.ok -eq $true)

    $cyFolder = -join ([char[]](0x041F, 0x0430, 0x043F, 0x043A, 0x0430))
    $cyFile = (-join ([char[]](0x0434, 0x0430, 0x043D, 0x043D, 0x044B, 0x0435))) + ".bin"
    $rootName = "FireDrop-smoke-$stamp"
    $relative = "$rootName/$cyFolder"

    $folderResult = Post-Json "$base/api/folders" @{ path = $relative }
    Check "Folder creation API" ($folderResult.ok -eq $true)

    $size = 9 * 1024 * 1024
    $chunkSize = 8 * 1024 * 1024
    $data = New-Object byte[] $size
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $random.GetBytes($data)
    $random.Dispose()
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $hashBefore = ([BitConverter]::ToString($sha.ComputeHash($data))).Replace("-", "")
    $sha.Dispose()

    $init = Post-Json "$base/api/uploads" @{
        name         = $cyFile
        size         = $size
        lastModified = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        relativePath = $relative
    }
    Check "Upload initialized" ($null -ne $init.id) "id=$($init.id)"

    $first = New-Object byte[] $chunkSize
    [Array]::Copy($data, 0, $first, 0, $chunkSize)
    $second = New-Object byte[] ($size - $chunkSize)
    [Array]::Copy($data, $chunkSize, $second, 0, $second.Length)

    Put-Bytes "$base/api/uploads/$($init.id)/chunk?offset=0" $first | Out-Null
    Put-Bytes "$base/api/uploads/$($init.id)/chunk?offset=$chunkSize" $second | Out-Null

    $done = Invoke-RestMethod -Method Post -Uri "$base/api/uploads/$($init.id)/complete" `
        -WebSession $session -TimeoutSec 60
    Check "Upload completed" ($done.ok -eq $true)

    $target = Join-Path (Join-Path (Join-Path $save $rootName) $cyFolder) $cyFile
    Check "File exists at nested path" (Test-Path -LiteralPath $target) $target
    if (Test-Path -LiteralPath $target) {
        $hashAfter = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
        $length = (Get-Item -LiteralPath $target).Length
        Check "Size preserved (9 MiB)" ($length -eq $size) "bytes=$length"
        Check "SHA-256 matches" ($hashAfter -eq $hashBefore) $hashAfter.Substring(0, 16)
    }

    $escapeName = "FireDrop-escape-$stamp"
    try {
        Post-Json "$base/api/folders" @{ path = "../$escapeName" } | Out-Null
    } catch {
    }
    $outside = Join-Path (Join-Path $save "..") $escapeName
    Check "Traversal stays inside save folder" (-not (Test-Path -LiteralPath $outside))

    $udp = New-Object System.Net.Sockets.UdpClient
    try {
        $udp.Client.ReceiveTimeout = 2500
        $probe = [System.Text.Encoding]::ASCII.GetBytes("FIREDROP_DISCOVER_V1")
        $udp.Send($probe, $probe.Length, "127.0.0.1", 47822) | Out-Null
        $remote = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
        $reply = $udp.Receive([ref]$remote)
        $payload = [System.Text.Encoding]::UTF8.GetString($reply) | ConvertFrom-Json
        Check "UDP discovery replies" ($payload.app -eq "FireDrop" -and [int]$payload.port -eq $port) `
            "kind=$($payload.kind) name=$($payload.name) version=$($payload.version)"
    } catch {
        Check "UDP discovery replies" $false $_.Exception.Message
    } finally {
        $udp.Close()
    }

    $root = Join-Path $save $rootName
} finally {
    if ($null -ne $proc -and -not $proc.HasExited) {
        $proc.CloseMainWindow() | Out-Null
        if (-not $proc.WaitForExit(5000)) {
            Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        }
    }
    Start-Sleep -Milliseconds 500

    if ($root -and (Test-Path -LiteralPath $root)) {
        Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
    }
    if ($stamp -and $save) {
        foreach ($leftover in @(
            (Join-Path $save "FireDrop-escape-$stamp"),
            (Join-Path (Join-Path $save "..") "FireDrop-escape-$stamp"))) {
            if (Test-Path -LiteralPath $leftover) {
                Remove-Item -LiteralPath $leftover -Recurse -Force -ErrorAction SilentlyContinue
            }
        }
    }
}

$failed = @($results | Where-Object { -not $_.Ok }).Count
Write-Host ""
Write-Host ("RESULT: {0}/{1} passed" -f ($results.Count - $failed), $results.Count)
if ($failed -gt 0) { exit 1 }
