# Prepare a Windows 10/11 machine for this project: WSL2 + Ubuntu 24.04, Docker Desktop, VS Code (+ WSL extension), .wslconfig.
# Idempotent: safe to run again; steps already done are skipped.
#
# Usage: open PowerShell (no need for "Run as administrator"; Windows will prompt when needed) and run:
#   powershell -ExecutionPolicy Bypass -File dev\windows\setup-windows.ps1
# Optional parameters:
#   -Distro "Ubuntu-24.04"   -WslMemoryGB 10   -SkipDocker   -SkipVSCode
[CmdletBinding()]
param(
  [string]$Distro = "Ubuntu-24.04",
  [int]$WslMemoryGB = 10,
  [switch]$SkipDocker,
  [switch]$SkipVSCode
)
$ErrorActionPreference = "Stop"

function Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host " ok $m" -ForegroundColor Green }
function Warn($m) { Write-Host " !! $m" -ForegroundColor Yellow }
function Have($c) { [bool](Get-Command $c -ErrorAction SilentlyContinue) }

# ---- 0. Prerequisites ------------------------------------------------------------------------------------------------
$build = [System.Environment]::OSVersion.Version.Build
if ($build -lt 19041) { throw "Windows 10 version 2004 (build 19041) or later, or Windows 11, is required. Your machine: build $build." }
$ramGB = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB)
if ($ramGB -lt 16) { Warn "This machine has $ramGB GB of RAM; 16 GB is recommended (the local cluster uses ~6 GB, the full profile adds ~4.5 GB)." }
if (-not (Have winget)) { Warn "'winget' (App Installer) is not available. Docker Desktop and VS Code must be installed manually." }

# ---- 1. WSL2 + Ubuntu ----------------------------------------------------------------------------------------------
Step "WSL2 and $Distro"
if (-not (Have wsl)) { throw "The 'wsl' command was not found. Enable the Windows 'Windows Subsystem for Linux' feature and run again." }
$installed = (wsl.exe -l -q 2>$null | ForEach-Object { ($_ -replace "`0", "").Trim() }) | Where-Object { $_ }
if ($installed -contains $Distro) {
  Ok "$Distro is already installed"
} else {
  Write-Host "Installing $Distro (a reboot may be required; the first run takes a few minutes)..."
  wsl.exe --install -d $Distro --no-launch
  if ($LASTEXITCODE -ne 0) { Warn "wsl --install returned code $LASTEXITCODE. If Windows asks for a reboot, reboot and then run this script again." }
  $script:needFirstRun = $true
}
wsl.exe --set-default-version 2 2>$null | Out-Null
wsl.exe --set-default $Distro 2>$null | Out-Null

# ---- 2. .wslconfig (cap WSL RAM so it does not consume the whole machine) --------------------------------------------
Step "WSL configuration (.wslconfig)"
$cfg = Join-Path $env:USERPROFILE ".wslconfig"
if (Test-Path $cfg) {
  Ok ".wslconfig already exists, keeping it: $cfg (check that 'memory' >= 8GB if the local cluster runs out of memory)"
} else {
  $mem = [math]::Min($WslMemoryGB, [math]::Max(6, $ramGB - 4))
  @"
[wsl2]
memory=${mem}GB
processors=$([math]::Max(4, [math]::Min(8, [Environment]::ProcessorCount)))
swap=4GB
"@ | Set-Content -Path $cfg -Encoding ascii
  Ok "created $cfg (memory=${mem}GB)"
  Warn "Run 'wsl --shutdown' for the configuration to take effect."
}

# ---- 3. Docker Desktop ---------------------------------------------------------------------------------------------
if (-not $SkipDocker) {
  Step "Docker Desktop"
  if (Have docker) {
    Ok "Docker already present: $(docker --version)"
  } elseif (Have winget) {
    winget install -e --id Docker.DockerDesktop --accept-package-agreements --accept-source-agreements
    Warn "Open Docker Desktop once, accept the terms, then enable WSL integration (see step 4)."
  } else {
    Warn "Install Docker Desktop manually: https://www.docker.com/products/docker-desktop/"
  }
}

# ---- 4. VS Code + extension WSL ------------------------------------------------------------------------------------
if (-not $SkipVSCode) {
  Step "VS Code and the WSL extension"
  if (-not (Have code)) {
    if (Have winget) { winget install -e --id Microsoft.VisualStudioCode --accept-package-agreements --accept-source-agreements }
    else { Warn "Install VS Code manually: https://code.visualstudio.com/" }
  } else { Ok "VS Code already present" }
  if (Have code) {
    code --install-extension ms-vscode-remote.remote-wsl --force | Out-Null
    code --install-extension vscjava.vscode-java-pack --force | Out-Null
    code --install-extension ms-python.python --force | Out-Null
    Ok "extension: Remote - WSL, Java, Python"
  } else {
    Warn "Reopen PowerShell after installing VS Code, then run the script again to install the extensions."
  }
}

# ---- 5. Next steps ---------------------------------------------------------------------------------------------------
Write-Host ""
Step "Remaining steps (once, by hand)"
if ($script:needFirstRun) {
  Write-Host "  1. Restart Windows if prompted."
  Write-Host "  2. Open 'Ubuntu 24.04' from the Start Menu and set the Linux username and password (first time only)."
} else {
  Write-Host "  1. Open 'Ubuntu 24.04' from the Start Menu (or type: wsl)."
}
Write-Host "  3. Docker Desktop -> Settings -> Resources -> WSL integration: enable it for '$Distro'. Click Apply."
Write-Host "  4. In the Ubuntu terminal: clone the repo into ~/work, then run  bash dev/scripts/setup-ubuntu.sh"
Write-Host "     (step-by-step details: docs/onboarding-windows.md)"
