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
    "api-gateway + search-service"  = "$Gateway/api/search/page?limit=1&from=2020-01-01T00:00:00Z&to=2100-01-01T00:00:00Z"
    # The gateway only proxies POST /api/logs, so wake ingestion-service directly (a 404 is fine).
    "ingestion-service"             = "https://ingestion-service-u5ai.onrender.com/"
    # Add ai-service / frontend here if you want them kept warm too, e.g.:
    # "ai-service" = "https://<your-ai-service>.onrender.com/"
}

function Write-Log([string]$Message) {
    $line = "{0:yyyy-MM-dd HH:mm:ss}  {1}" -f (Get-Date), $Message
    Write-Host $line
    Add-Content -Path $LogFile -Value $line
}

function Ping-Target([string]$Name, [string]$Url) {
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec $TimeoutSeconds -ErrorAction Stop
        Write-Log ("OK    {0,-32} {1} in {2} ms" -f $Name, [int]$response.StatusCode, $watch.ElapsedMilliseconds)
    }
    catch {
        $status = $_.Exception.Response.StatusCode.value__
        if ($status) {
            # The service answered (404/405/502...): it is awake, or at least reachable.
            Write-Log ("OK    {0,-32} HTTP {1} in {2} ms" -f $Name, $status, $watch.ElapsedMilliseconds)
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
