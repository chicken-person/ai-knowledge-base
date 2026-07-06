$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectRoot

mvn test
mvn -DskipTests package

Write-Host ""
Write-Host "Build artifact: target/ai-knowledge-base-0.1.0.jar"
Write-Host "Run: powershell -ExecutionPolicy Bypass -File .\start-dev.ps1"
