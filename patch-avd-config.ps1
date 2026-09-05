# Patch the Watch4 AVD config.ini to match a real Galaxy Watch 4.
# Safe re-run: idempotent, only touches the keys listed in $Overrides.
# IMPORTANT: shut the emulator down first. It rewrites config.ini on exit,
# so any edit made while it runs gets overwritten.

$ErrorActionPreference = 'Stop'

$Cfg = 'E:\AndroidDev\android-home\avd\Watch4.avd\config.ini'

$Overrides = [ordered]@{
    'hw.rotaryInput'  = 'yes'    # Watch4 has a physical rotating bezel
    'hw.gpu.enabled'  = 'yes'    # was 'no' = software rendering, very slow
    'hw.gpu.mode'     = 'auto'
    'hw.keyboard'     = 'yes'    # host keyboard input in the emulator
    'hw.ramSize'      = '1536'   # Watch4 ships with 1.5 GB
    'hw.lcd.width'    = '450'    # real panel; the generic profile said 454
    'hw.lcd.height'   = '450'
    'hw.lcd.density'  = '340'    # 450px at 340dpi = 211.7dp wide, same as the watch
    'hw.lcd.circular' = 'true'   # round screen, corners masked like on device
}

if (-not (Test-Path $Cfg)) {
    Write-Host "[FAIL] $Cfg not found. Run create-avd.bat first."
    exit 1
}

$lines = Get-Content -LiteralPath $Cfg
$seen = @{}
$out = foreach ($line in $lines) {
    # The emulator writes back "key = value" with spaces, avdmanager writes "key=value"
    if ($line -match '^\s*([^=]+?)\s*=\s*(.*)$') {
        $k = $Matches[1]
        $v = $Matches[2].Trim()
        if ($Overrides.Contains($k)) {
            if ($v -ne $Overrides[$k]) {
                Write-Host ("  {0,-18} {1,-6} -> {2}" -f $k, $v, $Overrides[$k])
            }
            $seen[$k] = $true
            "$k=$($Overrides[$k])"
        } else {
            $line
        }
    } else {
        $line
    }
}

$missing = $Overrides.Keys | Where-Object { -not $seen.ContainsKey($_) }
foreach ($k in $missing) {
    Write-Host "  $k not present, appending = $($Overrides[$k])"
    $out += "$k=$($Overrides[$k])"
}

Set-Content -LiteralPath $Cfg -Value $out -Encoding ASCII

Write-Host ''
Write-Host 'Verifying:'
Get-Content -LiteralPath $Cfg | Where-Object {
    $_ -match '^hw\.(rotaryInput|gpu\.enabled|gpu\.mode|keyboard|ramSize|lcd\.(width|height|density|circular))='
} | ForEach-Object { Write-Host "  $_" }

Write-Host ''
Write-Host 'Integrity check (line count should be unchanged or larger):'
Write-Host "  before = $($lines.Count)   after = $((Get-Content -LiteralPath $Cfg).Count)"
