#Requires -Version 5.1
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$runtime = Join-Path $root 'runtime'
$templates = Join-Path $root 'config\server-templates'

if (-not (Test-Path $runtime)) {
    Write-Host 'runtime/ already removed — nothing to migrate.' -ForegroundColor Yellow
    exit 0
}

$excludeDirNames = @(
    'cache', 'logs', 'libraries', 'versions',
    'world', 'world_nether', 'world_the_end',
    'limbo_temp', '.paper-remapped', 'spark', 'PlaceholderAPI'
)

function Copy-ServerRoot {
    param(
        [string]$SourceRoot,
        [string]$DestRoot
    )

    New-Item -ItemType Directory -Force -Path $DestRoot | Out-Null

    Get-ChildItem -Path $SourceRoot -File | ForEach-Object {
        Copy-Item -Path $_.FullName -Destination (Join-Path $DestRoot $_.Name) -Force
    }

    $configSrc = Join-Path $SourceRoot 'config'
    if (Test-Path $configSrc) {
        $configDst = Join-Path $DestRoot 'config'
        New-Item -ItemType Directory -Force -Path $configDst | Out-Null
        Get-ChildItem -Path $configSrc -File | ForEach-Object {
            Copy-Item -Path $_.FullName -Destination (Join-Path $configDst $_.Name) -Force
        }
    }

    $pluginsSrc = Join-Path $SourceRoot 'plugins'
    $pluginsDst = Join-Path $DestRoot 'plugins'
    New-Item -ItemType Directory -Force -Path $pluginsDst | Out-Null

    if (Test-Path $pluginsSrc) {
        Get-ChildItem -Path $pluginsSrc -File -Filter '*.jar' | ForEach-Object {
            Copy-Item -Path $_.FullName -Destination (Join-Path $pluginsDst $_.Name) -Force
        }
    }
}

Write-Host 'Migrating runtime/ -> config/server-templates/ ...' -ForegroundColor Cyan

Copy-ServerRoot (Join-Path $runtime 'proxy') (Join-Path $templates 'proxy')
Copy-ServerRoot (Join-Path $runtime 'lobby-1') (Join-Path $templates 'lobby')
Copy-ServerRoot (Join-Path $runtime 'extraction-1') (Join-Path $templates 'extraction')

# Remove runtime-only state files from templates.
$stateFiles = @('usercache.json', 'version_history.json')
foreach ($preset in @('lobby', 'extraction')) {
    foreach ($name in $stateFiles) {
        $path = Join-Path $templates "$preset\$name"
        if (Test-Path $path) { Remove-Item $path -Force }
    }
}

Write-Host 'Migration complete. Templated plugin configs in server-templates/ were preserved (not overwritten).' -ForegroundColor Green
