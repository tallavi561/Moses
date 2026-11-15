<#
.SYNOPSIS
  Builds and starts all 7 MoSes services locally, each in its own tab of a single
  Windows Terminal window (or its own PowerShell window if Windows Terminal is missing).
  Requires: Java 21, Maven, Node.js, and MongoDB - either reachable at localhost:27017,
  or an external one given in the MONGODB_URI environment variable.
#>

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

# MONGODB_URI (optional) points the services at an external database such as the MongoDB Atlas
# cluster the Render deployment used. Without it every service falls back to its default,
# mongodb://localhost:27017/moses_monitor - a separate, local-only data set.
# Set it for this shell ($env:MONGODB_URI = '...') or permanently as a user environment variable.
$mongoUri = $env:MONGODB_URI
if (-not $mongoUri) { $mongoUri = [Environment]::GetEnvironmentVariable("MONGODB_URI", "User") }

if ($mongoUri) {
    # The URI holds credentials, so it is never printed.
    Write-Host "==> Using the external MongoDB from MONGODB_URI (not localhost)." -ForegroundColor Cyan
}
else {
    Write-Host "==> Checking MongoDB on localhost:27017..." -ForegroundColor Cyan
    $mongoUp = Test-NetConnection -ComputerName localhost -Port 27017 -WarningAction SilentlyContinue
    if (-not $mongoUp.TcpTestSucceeded) {
        Write-Host "MongoDB is not reachable on localhost:27017. Start it first (e.g. 'mongod')." -ForegroundColor Red
        exit 1
    }
}

Write-Host "==> Building all Java modules (mvn package -DskipTests)..." -ForegroundColor Cyan
Push-Location $root
mvn -q clean package -DskipTests
if ($LASTEXITCODE -ne 0) { Write-Host "Maven build failed." -ForegroundColor Red; exit 1 }
Pop-Location

# auth-service signs login tokens and api-gateway verifies them with the same key; both refuse
# to start without one. This fixed value is for local development only - a real deployment
# sets its own JWT_SECRET (see render.yaml). The windows started below inherit it.
if (-not $env:JWT_SECRET) {
    $env:JWT_SECRET = "local-dev-only-jwt-secret-do-not-use-in-production"
}

Write-Host "==> Preparing frontend..." -ForegroundColor Cyan
if (-not (Test-Path "$root\frontend\node_modules")) {
    Push-Location "$root\frontend"
    npm install --legacy-peer-deps
    Pop-Location
}
if (-not (Test-Path "$root\frontend\.env.local")) {
    "VITE_API_BASE_URL=http://localhost:8085" | Out-File -FilePath "$root\frontend\.env.local" -Encoding utf8
}

# One entry per tab: title, working directory, and the command to run in it.
$quotedSecret = $env:JWT_SECRET.Replace("'", "''")
# JWT_SECRET (and MONGODB_URI, when given) are set inside the tab itself: a tab opened by an
# already-running Windows Terminal does not reliably inherit this script's environment.
$tabEnv = "`$env:JWT_SECRET = '$quotedSecret'; "
if ($mongoUri) {
    $tabEnv += "`$env:MONGODB_URI = '$($mongoUri.Replace("'", "''"))'; "
}
$tabs = foreach ($name in "ingestion-service", "search-service", "process-monitor-service", "ai-service", "auth-service", "api-gateway") {
    @{ Title = $name; Dir = $root; Command = "${tabEnv}java -jar '$root\$name\target\$name.jar'" }
}
$tabs += @{ Title = "frontend"; Dir = "$root\frontend"; Command = "npm run dev" }

# Prefer Windows Terminal: a single window with one tab per service.
$wt = (Get-Command wt.exe -ErrorAction SilentlyContinue).Source
if (-not $wt) {
    $candidate = Join-Path $env:LOCALAPPDATA "Microsoft\WindowsApps\wt.exe"
    if (Test-Path $candidate) { $wt = $candidate }
}

Write-Host "==> Starting $($tabs.Count) services..." -ForegroundColor Cyan
if ($wt) {
    $wtTabs = foreach ($tab in $tabs) {
        # -EncodedCommand keeps quotes and ';' (Windows Terminal's own separator) out of the command line.
        $encoded = [Convert]::ToBase64String([System.Text.Encoding]::Unicode.GetBytes($tab.Command))
        "new-tab --title `"$($tab.Title)`" --suppressApplicationTitle -d `"$($tab.Dir)`" powershell -NoExit -EncodedCommand $encoded"
    }
    Start-Process $wt -ArgumentList ("-w new " + ($wtTabs -join " ; "))
    $where = "as tabs in one Windows Terminal window"
}
else {
    # No Windows Terminal: fall back to a separate PowerShell window per service.
    foreach ($tab in $tabs) {
        Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$($tab.Dir)'; $($tab.Command)" -WindowStyle Normal
    }
    $where = "in separate windows"
}

Write-Host ""
Write-Host "All services starting ${where}:" -ForegroundColor Green
Write-Host "  ingestion-service        http://localhost:8081"
Write-Host "  search-service           http://localhost:8082"
Write-Host "  process-monitor-service  http://localhost:8083"
Write-Host "  ai-service               http://localhost:8084"
Write-Host "  auth-service             http://localhost:8086"
Write-Host "  api-gateway              http://localhost:8085  (frontend talks to this)"
Write-Host "  frontend (Vite dev)      http://localhost:5173"
Write-Host ""
Write-Host "Open http://localhost:5173 once the frontend tab shows 'ready'." -ForegroundColor Green
