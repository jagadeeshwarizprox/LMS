# Local development on Windows. Needs Java 17, Maven, Node 20 and a running MongoDB.
# Run from the project root:  .\run.ps1
# If PowerShell refuses to run it:  Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass

$ErrorActionPreference = 'Stop'

function Require-Command($name, $hint) {
    if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
        Write-Host "$name is not on your PATH. $hint" -ForegroundColor Red
        exit 1
    }
}

Require-Command mvn  'Install Maven, or open a new terminal if you just did.'
Require-Command node 'Install Node 20 from nodejs.org.'

# Mongo has to be answering before Spring starts, or the app exits at boot
$mongo = Test-NetConnection -ComputerName 127.0.0.1 -Port 27017 -WarningAction SilentlyContinue
if (-not $mongo.TcpTestSucceeded) {
    Write-Host 'MongoDB is not answering on 27017.' -ForegroundColor Red
    Write-Host 'Start it with:  net start MongoDB      (as administrator)'
    exit 1
}

if (-not $env:JWT_SECRET) {
    # HS256 needs at least 32 bytes; anything shorter fails at startup with a clear message
    $env:JWT_SECRET = 'development-secret-change-me-at-least-32-characters'
}

Write-Host 'Building the backend.' -ForegroundColor Cyan
Push-Location backend
mvn -q clean package -DskipTests
if ($LASTEXITCODE -ne 0) { Pop-Location; Write-Host 'Build failed. Fix the first error and run again.' -ForegroundColor Red; exit 1 }
Pop-Location

Write-Host 'Starting the API on 8080.' -ForegroundColor Cyan
$api = Start-Process -FilePath 'java' `
    -ArgumentList '-jar', 'backend\target\pib-lms-1.0.0.jar' `
    -PassThru -NoNewWindow

try {
    $up = $false
    foreach ($i in 1..60) {
        Start-Sleep -Seconds 1
        try {
            Invoke-RestMethod 'http://localhost:8080/api/health' -TimeoutSec 2 | Out-Null
            $up = $true
            break
        } catch { }
    }
    if (-not $up) { throw 'The API did not come up. Look at the output above.' }
    Write-Host 'API is up.' -ForegroundColor Green

    Write-Host 'Starting the frontend on 5173.' -ForegroundColor Cyan
    Push-Location frontend
    if (-not (Test-Path node_modules)) { npm install --no-audit --no-fund }
    npm run dev
    Pop-Location
}
finally {
    if ($api -and -not $api.HasExited) {
        Write-Host 'Stopping the API.' -ForegroundColor Cyan
        Stop-Process -Id $api.Id -Force -ErrorAction SilentlyContinue
    }
}
