<#
.SYNOPSIS
  Stops all locally running MoSes java services + the frontend dev server.
#>

$pattern = 'ingestion-service\.jar|search-service\.jar|process-monitor-service\.jar|ai-service\.jar|api-gateway\.jar'

Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -match $pattern } |
    ForEach-Object {
        Write-Host "Stopping PID $($_.ProcessId): $($_.CommandLine)" -ForegroundColor Yellow
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }

Get-CimInstance Win32_Process -Filter "Name='node.exe'" |
    Where-Object { $_.CommandLine -match 'vite' } |
    ForEach-Object {
        Write-Host "Stopping PID $($_.ProcessId): frontend dev server" -ForegroundColor Yellow
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }

Write-Host "Done." -ForegroundColor Green
