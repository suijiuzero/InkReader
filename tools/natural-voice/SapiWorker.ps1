$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$voice = New-Object -ComObject SAPI.SpVoice
$tokens = $voice.GetVoices()
$local = $null
for ($i = 0; $i -lt $tokens.Count; $i++) {
    $candidate = $tokens.Item($i)
    if ($candidate.Id.EndsWith('\Local-Xiaoxiao')) { $local = $candidate; break }
}
if ($null -eq $local) { throw 'Local Xiaoxiao voice not installed' }
$voice.Voice = $local
$audioDir = Join-Path $PSScriptRoot '.runtime\audio'
[IO.Directory]::CreateDirectory($audioDir) | Out-Null
while ($null -ne ($line = [Console]::ReadLine())) {
    $path = Join-Path $audioDir (([Guid]::NewGuid().ToString('N')) + '.wav')
    $stream = $null
    try {
        $request = $line | ConvertFrom-Json
        $stream = New-Object -ComObject SAPI.SpFileStream
        $stream.Open($path, 3, $false)
        $voice.AudioOutputStream = $stream
        $voice.Rate = [int][Math]::Round([Math]::Log([double]$request.rate, 2) * 5)
        $pitch = [int][Math]::Round([Math]::Log([double]$request.pitch, 2) * 10)
        $escaped = [Security.SecurityElement]::Escape([string]$request.text)
        # Only our escaped text enters SAPI XML. Book markup is never evaluated.
        $xml = '<pitch absmiddle="' + $pitch + '">' + $escaped + '</pitch>'
        $voice.Speak($xml, 8) | Out-Null
        $stream.Close()
        $stream = $null
        $encoded = [Convert]::ToBase64String([IO.File]::ReadAllBytes($path))
        [Console]::WriteLine((@{ok=$true; audio=$encoded} | ConvertTo-Json -Compress))
    } catch {
        [Console]::WriteLine('{"ok":false}')
    } finally {
        if ($null -ne $stream) { try { $stream.Close() } catch {} }
        if ([IO.File]::Exists($path)) { [IO.File]::Delete($path) }
    }
}
