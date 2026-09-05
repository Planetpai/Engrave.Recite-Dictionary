# Install the Android Emulator Hypervisor Driver (AEHD).
# Needs admin; a UAC prompt will appear. Waits for completion and reports.

$ErrorActionPreference = 'Continue'

$Aehd = 'E:\AndroidDev\sdk\extras\google\Android_Emulator_Hypervisor_Driver'
$Log  = 'E:\AndroidDev\aehd-install.log'

function Test-Aehd {
    $out = & sc.exe query aehd 2>&1
    return ($LASTEXITCODE -eq 0)
}

Write-Host '=== AEHD install ==='
Write-Host ''

if (Test-Aehd) {
    Write-Host 'AEHD service already exists. Current state:'
    & sc.exe query aehd | Where-Object { $_ -match 'STATE|SERVICE_NAME' } | ForEach-Object { Write-Host "  $_" }
    Write-Host ''
    Write-Host 'Nothing to install.'
    exit 0
}

if (-not (Test-Path "$Aehd\silent_install.bat")) {
    Write-Host "[FAIL] $Aehd\silent_install.bat not found"
    exit 1
}

Write-Host 'A UAC prompt will appear now. Click YES to allow the driver install.'
Write-Host ''

if (Test-Path $Log) { Remove-Item $Log -Force }

$inner = "cd /d `"$Aehd`" && silent_install.bat > `"$Log`" 2>&1"

try {
    $p = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', $inner -Verb RunAs -Wait -PassThru
    Write-Host "Elevated process finished (exit code $($p.ExitCode))."
} catch {
    Write-Host "[FAIL] Elevation was cancelled or blocked: $($_.Exception.Message)"
    exit 1
}

Write-Host ''
if (Test-Path $Log) {
    Write-Host '--- installer output ---'
    Get-Content $Log | ForEach-Object { Write-Host "  $_" }
    Write-Host '------------------------'
} else {
    Write-Host 'No installer log was produced.'
}

Write-Host ''
if (Test-Aehd) {
    Write-Host '[OK] AEHD service is now registered:'
    & sc.exe query aehd | Where-Object { $_ -match 'STATE|SERVICE_NAME' } | ForEach-Object { Write-Host "  $_" }
    Write-Host ''
    Write-Host 'REBOOT the PC, then run check-env.bat to confirm acceleration.'
    exit 0
} else {
    Write-Host '[FAIL] AEHD service is still not registered.'
    Write-Host '       Likely causes: UAC declined, or Secure Boot blocks unsigned drivers.'
    exit 1
}
