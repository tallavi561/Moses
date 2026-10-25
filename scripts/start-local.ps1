<#
.SYNOPSIS
  Builds and starts all 6 MoSes services locally, each in its own terminal window.
  Requires: Java 21, Maven, Node.js, and MongoDB reachable at localhost:27017.
#>

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

Write-Host "==> Checking MongoDB on localhost:27017..." -ForegroundColor Cyan
$mongoUp = Test-NetConnection -ComputerName localhost -Port 27017 -WarningAction SilentlyContinue
if (-not $mongoUp.TcpTestSucceeded) {
    Write-Host "MongoDB is not reachable on localhost:27017. Start it first (e.g. 'mongod')." -ForegroundColor Red
    exit 1
}

Write-Host "==> Building all Java modules (mvn package -DskipTests)..." -ForegroundColor Cyan
Push-Location $root
mvn -q clean package -DskipTests
if ($LASTEXITCODE -ne 0) { Write-Host "Maven build failed." -ForegroundColor Red; exit 1 }
Pop-Location

function Start-JavaService($name, $jarPath, $port) {
    Write-Host "==> Starting $name on port $port..." -ForegroundColor Cyan
    Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$root'; java -jar '$jarPath'" -WindowStyle Normal
}

Start-JavaService "ingestion-service" "$root\ingestion-service\target\ingestion-service.jar" 8081
Start-JavaService "search-service" "$root\search-service\target\search-service.jar" 8082
Start-JavaService "process-monitor-service" "$root\process-monitor-service\target\process-monitor-service.jar" 8083
Start-JavaService "ai-service" "$root\ai-service\target\ai-service.jar" 8084
Start-JavaService "api-gateway" "$root\api-gateway\target\api-gateway.jar" 8085

Write-Host "==> Waiting for backend services to come up..." -ForegroundColor Cyan
Start-Sleep -Seconds 10

Write-Host "==> Starting frontend (npm run dev)..." -ForegroundColor Cyan
if (-not (Test-Path "$root\frontend\node_modules")) {
    Push-Location "$root\frontend"
    npm install --legacy-peer-deps
    Pop-Location
}
if (-not (Test-Path "$root\frontend\.env.local")) {
    "VITE_API_BASE_URL=http://localhost:8085" | Out-File -FilePath "$root\frontend\.env.local" -Encoding utf8
}
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$root\frontend'; npm run dev" -WindowStyle Normal

Write-Host ""
Write-Host "All services starting in separate windows:" -ForegroundColor Green
Write-Host "  ingestion-service        http://localhost:8081"
Write-Host "  search-service           http://localhost:8082"
Write-Host "  process-monitor-service  http://localhost:8083"
Write-Host "  ai-service               http://localhost:8084"
Write-Host "  api-gateway              http://localhost:8085  (frontend talks to this)"
Write-Host "  frontend (Vite dev)      http://localhost:5173"
Write-Host ""
Write-Host "Open http://localhost:5173 once the frontend window shows 'ready'." -ForegroundColor Green
