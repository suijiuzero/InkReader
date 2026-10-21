param([switch]$Quiet)
$ErrorActionPreference = 'Stop'
$pythonPath = $null
if (Test-Path -LiteralPath 'E:\python\python.exe') { $pythonPath = 'E:\python\python.exe' }
if (-not $pythonPath) {
    $pythonCommand = Get-Command pythonw.exe,python.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($pythonCommand) { $pythonPath = $pythonCommand.Source }
}
if (-not $pythonPath) { throw 'Please install Python 3.10 or newer.' }
try {
    $running = Invoke-RestMethod -Uri 'http://127.0.0.1:37262/' -TimeoutSec 2
} catch {
    Start-Process -FilePath $pythonPath -ArgumentList ('"' + (Join-Path $PSScriptRoot 'bridge.py') + '"') -WorkingDirectory $PSScriptRoot -WindowStyle Hidden
    Start-Sleep -Seconds 3
}
if (-not $Quiet) { Start-Process 'http://127.0.0.1:37262/' }
