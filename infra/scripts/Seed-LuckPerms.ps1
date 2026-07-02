#Requires -Version 5.1
<#
.SYNOPSIS
  Seed baseline LuckPerms groups, permissions, and prefix meta for SkyPvP.

.DESCRIPTION
  Runs idempotent LuckPerms CLI commands through the skypvp-web docker compose
  luckperms service (same skypvp_network database as game servers).

  Usage:
    .\infra\scripts\Seed-LuckPerms.ps1
    .\infra\scripts\Seed-LuckPerms.ps1 -UseCluster
    .\infra\scripts\Seed-LuckPerms.ps1 -WebRoot E:\Minecraft\skypvp-web
#>
param(
    [string]$WebRoot = (Join-Path (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path '..\skypvp-web'),
    [switch]$UseCluster,
    [string]$LuckPermsNamespace = 'skypvp-web',
    [string]$LuckPermsDeployment = 'luckperms',
    [string]$PostgresNamespace = 'skypvp-web',
    [string]$PostgresPod = 'postgres-0'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Test-ClusterLuckPerms {
    param(
        [string]$Namespace,
        [string]$Deployment
    )
    $null = kubectl get deploy -n $Namespace $Deployment 2>$null
    return $LASTEXITCODE -eq 0
}

if (-not $UseCluster) {
    if (Test-ClusterLuckPerms -Namespace $LuckPermsNamespace -Deployment $LuckPermsDeployment) {
        Write-Host 'Detected skypvp-web LuckPerms deployment in k8s; using cluster backend.' -ForegroundColor Yellow
        Write-Host 'Pass -UseCluster explicitly to suppress this auto-detection.' -ForegroundColor DarkGray
        $UseCluster = $true
    }
}

$UseDocker = -not $UseCluster
if ($UseDocker) {
    $WebRoot = (Resolve-Path $WebRoot).Path
    $ComposeFile = Join-Path $WebRoot 'docker-compose.yml'

    if (-not (Test-Path $ComposeFile)) {
        throw "skypvp-web docker-compose.yml not found at: $ComposeFile"
    }
}

function Send-LuckPerms {
    param([Parameter(Mandatory)][string]$Command)
    Write-Host "  lp> $Command" -ForegroundColor DarkGray
    $args = $Command -split ' '
    if ($UseCluster) {
        $output = kubectl exec -n $LuckPermsNamespace deploy/$LuckPermsDeployment -- send @args 2>&1 | Out-String
    } else {
        $output = & docker compose -f $ComposeFile exec -T luckperms send @args 2>&1 | Out-String
    }
    if ($LASTEXITCODE -ne 0 -and ($output -notmatch 'already exists|Already exists|already has|already a parent')) {
        throw "LuckPerms command failed: $Command`n$output"
    }
}

function Ensure-Group {
    param(
        [string]$Name,
        [int]$Weight,
        [string]$Parent = ''
    )
    Send-LuckPerms "creategroup $Name"
    Send-LuckPerms "group $Name setweight $Weight"
    if ($Parent) {
        Send-LuckPerms "group $Name parent add $Parent"
    }
}

function Ensure-LuckPermsDatabase {
    Write-Host 'Ensuring skypvp_network database exists...' -ForegroundColor Cyan
    if ($UseCluster) {
        $output = kubectl exec -n $PostgresNamespace $PostgresPod -- psql -U skypvp -d skypvp_store -c "CREATE DATABASE skypvp_network OWNER skypvp;" 2>&1 | Out-String
    } else {
        $output = docker compose -f $ComposeFile exec -T postgres psql -U skypvp -d skypvp_store -c "CREATE DATABASE skypvp_network OWNER skypvp;" 2>&1 | Out-String
    }
    if ($output -match 'already exists') {
        Write-Host 'Database skypvp_network already exists.' -ForegroundColor DarkGray
    } elseif ($LASTEXITCODE -eq 0) {
        Write-Host 'Created database skypvp_network.' -ForegroundColor Green
    } else {
        throw "Failed to ensure skypvp_network database:`n$output"
    }
}

function Wait-LuckPermsReady {
    $deadline = (Get-Date).AddMinutes(2)
    do {
        if ($UseCluster) {
            $info = kubectl exec -n $LuckPermsNamespace deploy/$LuckPermsDeployment -- send info 2>&1 | Out-String
        } else {
            $info = docker compose -f $ComposeFile exec -T luckperms send info 2>&1 | Out-String
        }
        if ($LASTEXITCODE -eq 0 -and $info -notmatch 'does not exist|FATAL') { break }
        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)
    if ($LASTEXITCODE -ne 0) {
        throw "LuckPerms backend did not become ready.`n$info"
    }
}

if ($UseCluster) {
    Write-Host "Using cluster LuckPerms deployment ($LuckPermsNamespace/$LuckPermsDeployment)..." -ForegroundColor Cyan
    Ensure-LuckPermsDatabase
    Wait-LuckPermsReady
} else {
    Write-Host 'Starting skypvp-web postgres + luckperms (if needed)...' -ForegroundColor Cyan
}

Push-Location $(if ($UseDocker) { $WebRoot } else { $PSScriptRoot })
try {
    if ($UseDocker) {
        docker compose up -d postgres luckperms | Out-Null
        $deadline = (Get-Date).AddMinutes(2)
        do {
            $ready = docker compose exec -T postgres pg_isready -U skypvp -d skypvp_store 2>&1
            if ($LASTEXITCODE -eq 0) { break }
            Start-Sleep -Seconds 3
        } while ((Get-Date) -lt $deadline)
        if ($LASTEXITCODE -ne 0) {
            throw "Postgres container did not become ready.`n$ready"
        }

        Ensure-LuckPermsDatabase
        Wait-LuckPermsReady
    }

    Write-Host 'Seeding rank ladder groups...' -ForegroundColor Cyan
    Ensure-Group -Name 'default' -Weight 0
    Ensure-Group -Name 'vip' -Weight 10
    Ensure-Group -Name 'vip+' -Weight 15 -Parent 'vip'
    Ensure-Group -Name 'mvp' -Weight 20
    Ensure-Group -Name 'mvp+' -Weight 25 -Parent 'mvp'
    Ensure-Group -Name 'mvp++' -Weight 30 -Parent 'mvp+'
    Ensure-Group -Name 'legend' -Weight 35
    Ensure-Group -Name 'helper' -Weight 40
    Ensure-Group -Name 'mod' -Weight 45 -Parent 'helper'
    Ensure-Group -Name 'staff' -Weight 50 -Parent 'mod'
    Ensure-Group -Name 'admin' -Weight 60 -Parent 'staff'
    Ensure-Group -Name 'owner' -Weight 70 -Parent 'admin'
    Ensure-Group -Name 'founder' -Weight 80 -Parent 'owner'

    Write-Host 'Seeding player-facing permissions...' -ForegroundColor Cyan
    Send-LuckPerms 'group default permission set skypvp.comment.create true'
    Send-LuckPerms 'group default permission set skypvp.wiki.suggest true'
    Send-LuckPerms 'group vip permission set skypvp.store.view true'
    Send-LuckPerms 'group vip permission set skypvp.profile.rewards true'
    Send-LuckPerms 'group vip permission set skypvp.profile.drawer true'
    Send-LuckPerms 'group mvp permission set skypvp.notifications.read true'

    Write-Host 'Seeding staff and admin permissions...' -ForegroundColor Cyan
    Send-LuckPerms 'group helper permission set skypvp.staff true'
    Send-LuckPerms 'group mod permission set skypvp.staff true'
    Send-LuckPerms 'group mod permission set skypvp.staff.spy true'
    Send-LuckPerms 'group staff permission set skypvp.staff true'
    Send-LuckPerms 'group staff permission set skypvp.staff.spy true'
    Send-LuckPerms 'group staff permission set skypvp.comment.moderate true'
    Send-LuckPerms 'group staff permission set skypvp.wiki.create true'
    Send-LuckPerms 'group staff permission set skypvp.wiki.update true'
    Send-LuckPerms 'group staff permission set skypvp.wiki.moderate true'
    Send-LuckPerms 'group staff permission set skypvp.wiki.publish true'
    Send-LuckPerms 'group admin permission set skypvp.admin true'
    Send-LuckPerms 'group admin permission set skypvp.admin.rank true'
    Send-LuckPerms 'group admin permission set skypvp.admin.currency true'
    Send-LuckPerms 'group admin permission set skypvp.admin.web true'
    Send-LuckPerms 'group admin permission set skypvp.maintenance.bypass true'
    Send-LuckPerms 'group admin permission set skypvp.web.admin true'
    Send-LuckPerms 'group admin permission set skypvp.store.admin true'
    Send-LuckPerms 'group admin permission set skypvp.blog.create true'
    Send-LuckPerms 'group admin permission set skypvp.blog.update true'
    Send-LuckPerms 'group admin permission set skypvp.blog.delete true'
    Send-LuckPerms 'group admin permission set skypvp.wiki.delete true'
    Send-LuckPerms 'group admin permission set skypvp.notifications.manage true'
    Send-LuckPerms 'group owner permission set skypvp.admin true'
    Send-LuckPerms 'group owner permission set skypvp.maintenance.bypass true'
    Send-LuckPerms 'group founder permission set skypvp.admin true'
    Send-LuckPerms 'group founder permission set skypvp.maintenance.bypass true'

    Write-Host 'Seeding prefix meta...' -ForegroundColor Cyan
    Send-LuckPerms 'group vip meta setprefix 100 "&a[VIP] "'
    Send-LuckPerms 'group vip+ meta setprefix 110 "&a[VIP+] "'
    Send-LuckPerms 'group mvp meta setprefix 120 "&b[MVP] "'
    Send-LuckPerms 'group mvp+ meta setprefix 130 "&b[MVP+] "'
    Send-LuckPerms 'group mvp++ meta setprefix 140 "&b[MVP++] "'
    Send-LuckPerms 'group legend meta setprefix 150 "&d[Legend] "'
    Send-LuckPerms 'group helper meta setprefix 160 "&9[Helper] "'
    Send-LuckPerms 'group mod meta setprefix 170 "&9[Mod] "'
    Send-LuckPerms 'group staff meta setprefix 180 "&c[Staff] "'
    Send-LuckPerms 'group admin meta setprefix 190 "&4[Admin] "'
    Send-LuckPerms 'group owner meta setprefix 200 "&4[Owner] "'
    Send-LuckPerms 'group founder meta setprefix 210 "&4[Founder] "'

    Send-LuckPerms 'group vip meta set profile-badge vip'
    Send-LuckPerms 'group admin meta set profile-badge admin'

    Write-Host 'LuckPerms baseline seed complete.' -ForegroundColor Green
}
finally {
    Pop-Location
}
