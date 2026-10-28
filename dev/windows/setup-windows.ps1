# Chuẩn bị máy Windows 10/11 để làm việc với dự án: WSL2 + Ubuntu 24.04, Docker Desktop, VS Code (+ extension WSL), .wslconfig.
# Idempotent: chạy lại được; bước nào đã xong sẽ được bỏ qua.
#
# Dùng: mở PowerShell (không cần "Run as administrator"; khi cần, Windows sẽ tự hỏi quyền) rồi chạy:
#   powershell -ExecutionPolicy Bypass -File dev\windows\setup-windows.ps1
# Tham số tùy chọn:
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

# ---- 0. Điều kiện --------------------------------------------------------------------------------------------------
$build = [System.Environment]::OSVersion.Version.Build
if ($build -lt 19041) { throw "Cần Windows 10 bản 2004 (build 19041) trở lên hoặc Windows 11. Máy bạn: build $build." }
$ramGB = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB)
if ($ramGB -lt 16) { Warn "Máy có $ramGB GB RAM; khuyến nghị 16 GB (cụm local dùng ~6 GB, profile full thêm ~4.5 GB)." }
if (-not (Have winget)) { Warn "Không có 'winget' (App Installer). Docker Desktop và VS Code sẽ phải cài tay." }

# ---- 1. WSL2 + Ubuntu ----------------------------------------------------------------------------------------------
Step "WSL2 và $Distro"
if (-not (Have wsl)) { throw "Không tìm thấy lệnh 'wsl'. Bật tính năng Windows 'Windows Subsystem for Linux' rồi chạy lại." }
$installed = (wsl.exe -l -q 2>$null | ForEach-Object { ($_ -replace "`0", "").Trim() }) | Where-Object { $_ }
if ($installed -contains $Distro) {
  Ok "$Distro đã được cài"
} else {
  Write-Host "Đang cài $Distro (có thể cần khởi động lại máy, lần đầu mất vài phút)..."
  wsl.exe --install -d $Distro --no-launch
  if ($LASTEXITCODE -ne 0) { Warn "wsl --install trả về mã $LASTEXITCODE. Nếu Windows yêu cầu khởi động lại thì hãy khởi động lại rồi chạy lại script này." }
  $script:needFirstRun = $true
}
wsl.exe --set-default-version 2 2>$null | Out-Null
wsl.exe --set-default $Distro 2>$null | Out-Null

# ---- 2. .wslconfig (giới hạn RAM của WSL để không nuốt hết máy) -----------------------------------------------------
Step "Cấu hình WSL (.wslconfig)"
$cfg = Join-Path $env:USERPROFILE ".wslconfig"
if (Test-Path $cfg) {
  Ok ".wslconfig đã có, giữ nguyên: $cfg (kiểm tra 'memory' >= 8GB nếu cụm local bị OOM)"
} else {
  $mem = [math]::Min($WslMemoryGB, [math]::Max(6, $ramGB - 4))
  @"
[wsl2]
memory=${mem}GB
processors=$([math]::Max(4, [math]::Min(8, [Environment]::ProcessorCount)))
swap=4GB
"@ | Set-Content -Path $cfg -Encoding ascii
  Ok "đã tạo $cfg (memory=${mem}GB)"
  Warn "Chạy 'wsl --shutdown' để cấu hình có hiệu lực."
}

# ---- 3. Docker Desktop ---------------------------------------------------------------------------------------------
if (-not $SkipDocker) {
  Step "Docker Desktop"
  if (Have docker) {
    Ok "Docker đã có: $(docker --version)"
  } elseif (Have winget) {
    winget install -e --id Docker.DockerDesktop --accept-package-agreements --accept-source-agreements
    Warn "Mở Docker Desktop một lần, chấp nhận điều khoản, rồi bật WSL integration (xem hướng dẫn bước 4)."
  } else {
    Warn "Cài Docker Desktop thủ công: https://www.docker.com/products/docker-desktop/"
  }
}

# ---- 4. VS Code + extension WSL ------------------------------------------------------------------------------------
if (-not $SkipVSCode) {
  Step "VS Code và extension WSL"
  if (-not (Have code)) {
    if (Have winget) { winget install -e --id Microsoft.VisualStudioCode --accept-package-agreements --accept-source-agreements }
    else { Warn "Cài VS Code thủ công: https://code.visualstudio.com/" }
  } else { Ok "VS Code đã có" }
  if (Have code) {
    code --install-extension ms-vscode-remote.remote-wsl --force | Out-Null
    code --install-extension vscjava.vscode-java-pack --force | Out-Null
    code --install-extension ms-python.python --force | Out-Null
    Ok "extension: Remote - WSL, Java, Python"
  } else {
    Warn "Mở lại PowerShell sau khi cài VS Code rồi chạy lại script để cài extension."
  }
}

# ---- 5. Tiếp theo --------------------------------------------------------------------------------------------------
Write-Host ""
Step "Việc còn lại (làm tay một lần)"
if ($script:needFirstRun) {
  Write-Host "  1. Khởi động lại Windows nếu được yêu cầu."
  Write-Host "  2. Mở 'Ubuntu 24.04' từ Start Menu, đặt tên người dùng và mật khẩu Linux (chỉ lần đầu)."
} else {
  Write-Host "  1. Mở 'Ubuntu 24.04' từ Start Menu (hoặc gõ: wsl)."
}
Write-Host "  3. Docker Desktop -> Settings -> Resources -> WSL integration: bật cho '$Distro'. Bấm Apply."
Write-Host "  4. Trong terminal Ubuntu: clone repo vào ~/work rồi chạy  bash dev/scripts/setup-ubuntu.sh"
Write-Host "     (chi tiết từng bước: docs/onboarding-windows.md)"
