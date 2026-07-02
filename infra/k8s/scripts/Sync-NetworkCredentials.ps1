#Requires -Version 5.1
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot '_game-mode-helpers.ps1')

Test-Kubectl

Write-Step 'Syncing credentials from skypvp-web-env into skypvp-network'

$sourceNs = 'skypvp-web'
$targetNs = 'skypvp-network'
$sourceSecret = 'skypvp-web-env'
$targetSecret = 'skypvp-network-env'

function Get-SecretValue {
    param(
        [Parameter(Mandatory = $true)]
        $SecretData,
        [Parameter(Mandatory = $true)]
        [string]$Key
    )
    if ($SecretData.PSObject.Properties.Name -contains $Key) {
        return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($SecretData.$Key))
    }
    return $null
}

function Get-SecretValueWithFallback {
    param(
        [Parameter(Mandatory = $true)]
        $SecretData,
        [Parameter(Mandatory = $true)]
        [string[]]$Keys
    )
    foreach ($key in $Keys) {
        $value = Get-SecretValue -SecretData $SecretData -Key $key
        if ($null -ne $value -and $value -ne '') {
            return $value
        }
    }
    return $null
}

function Sanitize-EnvValue {
    param([string]$Value)
    if ($null -eq $Value) { return $null }
    $trimmed = $Value.Trim().Trim('"').Trim("'")
    $hash = $trimmed.IndexOf('#')
    if ($hash -ge 0) {
        $trimmed = $trimmed.Substring(0, $hash).Trim()
    }
    if ($trimmed -eq '') { return $null }
    return $trimmed
}

function Read-DockerEnv {
    param([string]$Path)
    $dockerEnv = @{}
    if (-not (Test-Path $Path)) {
        return $dockerEnv
    }
    Get-Content $Path | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
            $value = Sanitize-EnvValue $Matches[2]
            if ($null -ne $value) {
                $dockerEnv[$Matches[1]] = $value
            }
        }
    }
    return $dockerEnv
}

$raw = kubectl get secret $sourceSecret -n $sourceNs -o json | ConvertFrom-Json
if (-not $raw.data.POSTGRES_PASSWORD) {
    throw "Secret $sourceNs/$sourceSecret is missing POSTGRES_PASSWORD"
}

$postgresPassword = Get-SecretValue -SecretData $raw.data -Key 'POSTGRES_PASSWORD'
$redisPassword = Get-SecretValue -SecretData $raw.data -Key 'REDIS_PASSWORD'
$inGameSecret = Get-SecretValue -SecretData $raw.data -Key 'IN_GAME_PLUGIN_SECRET'
$chatModerationEnabled = Get-SecretValueWithFallback -SecretData $raw.data -Keys @(
    'CHAT_MODERATION_ENABLED',
    'SPVP_CHAT_MODERATION_ENABLED'
)
$chatModerationEndpoint = Get-SecretValueWithFallback -SecretData $raw.data -Keys @(
    'CHAT_MODERATION_ENDPOINT',
    'SPVP_CHAT_MODERATION_ENDPOINT'
)
$chatModerationApiKey = Get-SecretValueWithFallback -SecretData $raw.data -Keys @(
    'CHAT_MODERATION_API_KEY',
    'SPVP_CHAT_MODERATION_API_KEY'
)
$chatTranslationEnabled = Get-SecretValueWithFallback -SecretData $raw.data -Keys @(
    'CHAT_TRANSLATION_ENABLED',
    'SPVP_CHAT_TRANSLATION_ENABLED'
)
$chatTranslationEndpoint = Get-SecretValueWithFallback -SecretData $raw.data -Keys @(
    'CHAT_TRANSLATION_ENDPOINT',
    'SPVP_CHAT_TRANSLATION_ENDPOINT'
)
$chatTranslationApiKey = Get-SecretValueWithFallback -SecretData $raw.data -Keys @(
    'CHAT_TRANSLATION_API_KEY',
    'SPVP_CHAT_TRANSLATION_API_KEY'
)
$chatTranslationRegion = Get-SecretValueWithFallback -SecretData $raw.data -Keys @(
    'CHAT_TRANSLATION_REGION',
    'SPVP_CHAT_TRANSLATION_REGION'
)
$chatTranslationDebug = Get-SecretValueWithFallback -SecretData $raw.data -Keys @(
    'CHAT_TRANSLATION_DEBUG',
    'SPVP_CHAT_TRANSLATION_DEBUG'
)

$dockerEnvPath = Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'docker\.env'
$dockerEnv = Read-DockerEnv -Path $dockerEnvPath

if (-not $chatModerationEnabled -or -not $chatModerationEndpoint -or -not $chatModerationApiKey) {
    if (Test-Path $dockerEnvPath) {
        Write-Host "Moderation keys missing from $sourceSecret; reading fallback from $dockerEnvPath" -ForegroundColor Yellow
        if (-not $chatModerationEnabled) { $chatModerationEnabled = $dockerEnv['SPVP_CHAT_MODERATION_ENABLED'] }
        if (-not $chatModerationEndpoint) { $chatModerationEndpoint = $dockerEnv['SPVP_CHAT_MODERATION_ENDPOINT'] }
        if (-not $chatModerationApiKey) { $chatModerationApiKey = $dockerEnv['SPVP_CHAT_MODERATION_API_KEY'] }
    }
}

if (-not $chatTranslationEnabled -or -not $chatTranslationEndpoint -or -not $chatTranslationApiKey) {
    if (Test-Path $dockerEnvPath) {
        Write-Host "Translation keys missing from $sourceSecret; reading fallback from $dockerEnvPath" -ForegroundColor Yellow
        if (-not $chatTranslationEnabled) { $chatTranslationEnabled = $dockerEnv['SPVP_CHAT_TRANSLATION_ENABLED'] }
        if (-not $chatTranslationEndpoint) { $chatTranslationEndpoint = $dockerEnv['SPVP_CHAT_TRANSLATION_ENDPOINT'] }
        if (-not $chatTranslationApiKey) {
            $chatTranslationApiKey = $dockerEnv['SPVP_CHAT_TRANSLATION_API_KEY']
            if (-not $chatTranslationApiKey) { $chatTranslationApiKey = $chatModerationApiKey }
        }
        if (-not $chatTranslationRegion) { $chatTranslationRegion = $dockerEnv['SPVP_CHAT_TRANSLATION_REGION'] }
        if (-not $chatTranslationDebug) { $chatTranslationDebug = $dockerEnv['SPVP_CHAT_TRANSLATION_DEBUG'] }
    }
}

$secretArgs = @(
    'create', 'secret', 'generic', $targetSecret, '-n', $targetNs,
    "--from-literal=POSTGRES_PASSWORD=$postgresPassword",
    "--from-literal=REDIS_PASSWORD=$redisPassword",
    "--from-literal=IN_GAME_PLUGIN_SECRET=$inGameSecret"
)

foreach ($pair in @(
        @{ Key = 'CHAT_MODERATION_ENABLED'; Value = $chatModerationEnabled },
        @{ Key = 'CHAT_MODERATION_ENDPOINT'; Value = $chatModerationEndpoint },
        @{ Key = 'CHAT_MODERATION_API_KEY'; Value = $chatModerationApiKey },
        @{ Key = 'CHAT_TRANSLATION_ENABLED'; Value = $chatTranslationEnabled },
        @{ Key = 'CHAT_TRANSLATION_ENDPOINT'; Value = $chatTranslationEndpoint },
        @{ Key = 'CHAT_TRANSLATION_API_KEY'; Value = $chatTranslationApiKey },
        @{ Key = 'CHAT_TRANSLATION_REGION'; Value = $chatTranslationRegion },
        @{ Key = 'CHAT_TRANSLATION_DEBUG'; Value = $chatTranslationDebug }
    )) {
    if ($null -ne $pair.Value -and $pair.Value -ne '') {
        $secretArgs += "--from-literal=$($pair.Key)=$($pair.Value)"
    }
}

& kubectl @secretArgs --dry-run=client -o yaml | kubectl apply -f - | Out-Host

Write-Host "Synced $targetNs/$targetSecret from $sourceNs/$sourceSecret" -ForegroundColor Green
