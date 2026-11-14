<#
Keeps the MoSes free-tier Render services awake by requesting each one on an interval.
Render spins a free service down after ~15 minutes without inbound requests, so the
default interval is 10 minutes. Any HTTP response (even 404) counts as "awake"; only a
timeout or connection failure is reported as a problem.

Run in the foreground:   powershell -ExecutionPolicy Bypass -File scripts\keepalive.ps1
Run hidden in background: see scripts\README-keepalive.md
Edit $Targets below if a service URL changes (e.g. after recreating the Blueprint).
#>
param(
    [int]$IntervalMinutes = 10,
    [int]$TimeoutSeconds = 120,
    [string]$LogFile = (Join-Path $PSScriptRoot "keepalive.log")
)

$Gateway = "https://api-gateway-x17f.onrender.com"

# Through the gateway: wakes the gateway plus the service behind each route.
$Targets = [ordered]@{
    "api-gateway + process-monitor" = "$Gateway/api/process/state"
    # {FROM}/{TO} become "the last hour" on every round (see Resolve-Url).
    "api-gateway + search-service"  = "$Gateway/api/search/page?limit=1&from={FROM}&to={TO}"
    # The gateway only proxies POST /api/logs, so wake ingestion-service directly (a 404 is fine).
    "ingestion-service"             = "https://ingestion-service-u5ai.onrender.com/"
    # Also hit the backends on their own public URLs, so they stay awake even when the
    # gateway cannot reach them (and the log shows which side is broken).
    "process-monitor (direct)"      = "https://process-monitor-service.onrender.com/api/process/state"
    "search-service (direct)"       = "https://search-service-1urn.onrender.com/api/search/page?limit=1&from={FROM}&to={TO}"
    # Add ai-service / frontend here if you want them kept warm too, e.g.:
    # "ai-service" = "https://<your-ai-service>.onrender.com/"
}

# Only one keepalive at a time: a second copy exits immediately instead of piling up.
$createdNew = $false
$Mutex = New-Object System.Threading.Mutex($true, "MoSesKeepalive", [ref]$createdNew)
if (-not $createdNew) {
    Write-Host "keepalive is already running; exiting."
    exit 0
}

function Write-Log([string]$Message) {
    $line = "{0:yyyy-MM-dd HH:mm:ss}  {1}" -f (Get-Date), $Message
    Write-Host $line
    # Append with FileShare.ReadWrite so `Get-Content -Wait` can tail the log while we write
    # (Add-Content in Windows PowerShell 5.1 locks readers out). Retry briefly if something
    # else (OneDrive sync, antivirus) holds the file; never let logging stop the loop.
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($line + [Environment]::NewLine)
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        try {
            $stream = New-Object System.IO.FileStream($LogFile, [System.IO.FileMode]::Append, [System.IO.FileAccess]::Write, [System.IO.FileShare]::ReadWrite)
            try { $stream.Write($bytes, 0, $bytes.Length) } finally { $stream.Dispose() }
            return
        }
        catch { Start-Sleep -Milliseconds 500 }
    }
}

function Resolve-Url([string]$Url) {
    $now = [DateTime]::UtcNow
    $Url.Replace("{FROM}", $now.AddHours(-1).ToString("yyyy-MM-ddTHH:mm:ssZ")).Replace("{TO}", $now.ToString("yyyy-MM-ddTHH:mm:ssZ"))
}

function Ping-Target([string]$Name, [string]$RawUrl) {
    $Url = Resolve-Url $RawUrl
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec $TimeoutSeconds -ErrorAction Stop
        Write-Log ("OK    {0,-32} {1} in {2} ms" -f $Name, [int]$response.StatusCode, $watch.ElapsedMilliseconds)
    }
    catch {
        $status = $_.Exception.Response.StatusCode.value__
        if ($status) {
            # The service answered (404/405/502...): it is awake, or at least reachable.
            # 5xx means it is awake but broken (e.g. the gateway cannot reach its backend).
            $label = if ($status -ge 500) { "WARN " } else { "OK   " }
            Write-Log ("{0} {1,-32} HTTP {2} in {3} ms" -f $label, $Name, $status, $watch.ElapsedMilliseconds)
        }
        else {
            Write-Log ("FAIL  {0,-32} {1} after {2} ms" -f $Name, $_.Exception.Message, $watch.ElapsedMilliseconds)
        }
    }
}

Write-Log "keepalive started: every $IntervalMinutes min, $($Targets.Count) target(s)"
while ($true) {
    foreach ($entry in $Targets.GetEnumerator()) {
        Ping-Target $entry.Key $entry.Value
    }
    Start-Sleep -Seconds ($IntervalMinutes * 60)
}
