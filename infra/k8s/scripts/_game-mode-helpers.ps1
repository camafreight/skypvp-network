Set-StrictMode -Version Latest

function Get-GameModesConfigPath {
    $infraRoot = Split-Path $PSScriptRoot -Parent
    return Join-Path $infraRoot 'game-modes.json'
}

function Get-GameModesConfig {
    $configPath = Get-GameModesConfigPath
    if (-not (Test-Path $configPath)) {
        throw "Game modes config not found: $configPath"
    }

    return Get-Content $configPath -Raw | ConvertFrom-Json
}

function Get-GameModeById {
    param([Parameter(Mandatory = $true)][string]$ModeId)

    $config = Get-GameModesConfig
    $mode = $config.modes | Where-Object { $_.id -eq $ModeId } | Select-Object -First 1
    if (-not $mode) {
        $available = ($config.modes | ForEach-Object { $_.id }) -join ', '
        throw "Unknown game mode '$ModeId'. Available modes: $available"
    }

    return $mode
}

function Get-ActiveGameModes {
    return (Get-GameModesConfig).modes
}

function Write-Step([string]$Message) {
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function Test-Kubectl {
    kubectl cluster-info *> $null
    if ($LASTEXITCODE -ne 0) {
        throw 'kubectl is not configured or the cluster is unreachable.'
    }
}
