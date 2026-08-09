# =============================================================================
# Anti-sleep helper for benchmark runs.
#
# Keeps the Windows system awake while this process runs by calling
# SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED). This requires
# NO admin rights (powercfg /change is denied on non-admin Windows 10 Home).
#
# Usage (launched in background by wsl-run-all.sh, killed when run ends):
#   powershell -NoProfile -ExecutionPolicy Bypass -File no-sleep.ps1
#
# NOTE: Keep this file pure ASCII. Windows PowerShell 5.1 reads UTF-8 without
# BOM as the system ANSI codepage (GBK), which corrupts non-ASCII comments.
#
# Exit: when the process is killed, Windows automatically clears the thread
# execution state, so no explicit restore is needed.
# =============================================================================

$src = @"
using System;
using System.Runtime.InteropServices;
public class KeepAwake {
    [DllImport("kernel32.dll")]
    public static extern UInt32 SetThreadExecutionState(UInt32 f);
}
"@
Add-Type -TypeDefinition $src

# ES_CONTINUOUS(0x80000000) | ES_SYSTEM_REQUIRED(0x1)
$flags = 2147483649
[KeepAwake]::SetThreadExecutionState($flags) | Out-Null

Write-Host ("no-sleep.ps1 active: SetThreadExecutionState(0x{0:X8})" -f $flags)
Write-Host ("System will not sleep while this process runs. PID: {0}" -f $PID)

# Stay alive until killed; re-assert periodically in case some policy clears it.
while ($true) {
    Start-Sleep -Seconds 60
    [KeepAwake]::SetThreadExecutionState($flags) | Out-Null
}
