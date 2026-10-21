$ErrorActionPreference = 'Stop'
$shortcutPath = Join-Path ([Environment]::GetFolderPath('Startup')) 'InkReader Computer Voice.lnk'
if (Test-Path -LiteralPath $shortcutPath) {
    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut($shortcutPath)
    $ownedLauncher = Join-Path $PSScriptRoot 'Start-MoYueVoice.ps1'
    if (-not $shortcut.Arguments.Contains($ownedLauncher)) { throw 'The shortcut belongs to another installation.' }
    Remove-Item -LiteralPath $shortcutPath
}
Write-Output 'InkReader voice automatic startup removed.'
