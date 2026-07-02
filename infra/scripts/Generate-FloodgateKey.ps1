#Requires -Version 5.1
<#
.SYNOPSIS
  Generate a Floodgate 2.x shared key (key.pem).

  Floodgate 2.x stores a random 128-bit AES key as base64 text in key.pem.
  Do NOT use an RSA/OpenSSL PEM here — Geyser will fail with
  "Invalid AES key length" when Bedrock players connect.
#>
param(
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$keyDir = Join-Path $root 'config\floodgate'
$keyPath = Join-Path $keyDir 'key.pem'

New-Item -ItemType Directory -Force -Path $keyDir | Out-Null

if ((Test-Path $keyPath) -and -not $Force) {
    $size = (Get-Item $keyPath).Length
    if ($size -gt 64) {
        Write-Host "Floodgate key at $keyPath looks invalid ($size bytes - expected ~24 for base64 AES-128)." -ForegroundColor Red
        Write-Host 'Re-run with -Force to replace it.' -ForegroundColor Yellow
        exit 1
    }
    Write-Host "Floodgate key already exists: $keyPath ($size bytes)" -ForegroundColor Yellow
    exit 0
}

$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $keyBytes = New-Object byte[] 16
    $rng.GetBytes($keyBytes)
    $encoded = [Convert]::ToBase64String($keyBytes)
    [IO.File]::WriteAllText($keyPath, $encoded)
    Write-Host "Generated Floodgate key: $keyPath ($($encoded.Length) chars, AES-128)" -ForegroundColor Green
    Write-Host 'Keep this file secret - all proxy and Paper servers must share the same key.' -ForegroundColor Cyan
}
finally {
    $rng.Dispose()
}
