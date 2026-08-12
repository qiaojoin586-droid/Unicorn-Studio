# Unicorn Studio - Development Startup SCRIPT
# Starts both SpringBoot backend and Vite dev server concurrently.
#
# Usage:
#   ./scripts/dev.ps1
#
# Prerequisites:
#   - JDK 17+ installed
#   - Node.js 18+ installed
#   - backend/ built: cd backend && ./mvnw compile
#   - frontend deps installed: npm install

$ErrorActionPreference = "Stop"
$RootDir = Split-Path -Parent $PSScriptRoot

# Load .env if present
$envFile = Join-Path $RootDir ".env"
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#")) {
            $parts = $line -split "=", 2
            if ($parts.Count -eq 2) {
                $key = $parts[0].Trim()
                $val = $parts[1].Trim().Trim('"').Trim("'")
                if (-not [Environment]::GetEnvironmentVariable($key)) {
                    [Environment]::SetEnvironmentVariable($key, $val, "Process")
                }
            }
        }
    }
}

# Defaults for development
if (-not $env:LEARNING_PASSWORD) { $env:LEARNING_PASSWORD = "unicorn" }
if (-not $env:JWT_SECRET) { $env:JWT_SECRET = "local-development-jwt-secret-at-least-32-chars!!" }

Write-Host "=== Unicorn Studio Dev Server ===" -ForegroundColor Cyan
Write-Host ""

# Start SpringBoot backend
Write-Host "[backend] Starting SpringBoot on :8080..." -ForegroundColor Yellow
$backendJob = Start-Process -FilePath "java" `
    -ArgumentList "-cp", (Join-Path $RootDir "backend/target/classes"), `
                  "-Dspring-boot.run.profiles=dev", `
                  "org.springframework.boot.devtools.RestartLauncher", `
                  "com.unicorn.studio.UnicornStudioApplication" `
    -WorkingDirectory (Join-Path $RootDir "backend") `
    -PassThru -NoNewWindow

# Start Vite dev server
Write-Host "[frontend] Starting Vite dev server on :5173..." -ForegroundColor Green
$frontendJob = Start-Process -FilePath "node" `
    -ArgumentList "node_modules/vite/bin/vite.js" `
    -WorkingDirectory $RootDir `
    -PassThru -NoNewWindow

Write-Host ""
Write-Host "Frontend: http://localhost:5173" -ForegroundColor Green
Write-Host "Backend:  http://localhost:8080" -ForegroundColor Yellow
Write-Host "Press Ctrl+C to stop both." -ForegroundColor Gray
Write-Host ""

# Wait and handle shutdown
try {
    Wait-Process -Id $backendJob.Id, $frontendJob.Id
} finally {
    Write-Host "`nShutting down..." -ForegroundColor Red
    if (-not $backendJob.HasExited) { Stop-Process -Id $backendJob.Id -Force -ErrorAction SilentlyContinue }
    if (-not $frontendJob.HasExited) { Stop-Process -Id $frontendJob.Id -Force -ErrorAction SilentlyContinue }
}
