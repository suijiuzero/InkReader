$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$voice = New-Object -ComObject SAPI.SpVoice
$tokens = $voice.GetVoices()
$local = $null
$japanese = $null
$nanami = $null
for ($i = 0; $i -lt $tokens.Count; $i++) {
    $candidate = $tokens.Item($i)
    if ($candidate.Id.EndsWith('\Local-Xiaoxiao')) { $local = $candidate }
    if ($candidate.Id.EndsWith('\Local-Nanami')) { $japanese = $candidate; $nanami = $candidate }
}
if ($null -eq $local) { throw 'Local Xiaoxiao voice not installed' }
if ($null -eq $japanese) {
    for ($i = 0; $i -lt $tokens.Count; $i++) {
        $candidate = $tokens.Item($i)
        if ($candidate.Id -match '\\(?:Local-|TTS_MS_)' -and
            (($candidate.GetAttribute('Language') -split ';') -contains '411')) { $japanese = $candidate; break }
    }
}
$languages = @('zh-CN','en-US')
if ($null -ne $japanese) { $languages += 'ja-JP' }
$models = @('auto','xiaoxiao')
if ($null -ne $nanami) { $models += 'nanami' }
$audioDir = Join-Path $PSScriptRoot '.runtime\audio'
[IO.Directory]::CreateDirectory($audioDir) | Out-Null
while ($null -ne ($line = [Console]::ReadLine())) {
    $path = Join-Path $audioDir (([Guid]::NewGuid().ToString('N')) + '.wav')
    $stream = $null
    try {
        $request = $line | ConvertFrom-Json
        $selected = $local
        $name = 'Xiaoxiao'
        if ($request.model -eq 'nanami') {
            if ($null -eq $nanami) { throw 'Local Nanami voice not installed' }
            $selected = $nanami
            $name = $nanami.GetDescription()
        } elseif ($request.model -ne 'xiaoxiao' -and ($request.language -eq 'ja-JP' -or $request.text -match '[\u3041-\u3096\u309d\u309e\u30a1-\u30fa\u30fd\u30fe\uff66-\uff9d]')) {
            if ($null -eq $japanese) { throw 'Local Japanese voice not installed' }
            $selected = $japanese
            $name = $japanese.GetDescription()
        }
        $voice.Voice = $selected
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
        [Console]::WriteLine((@{ok=$true; audio=$encoded; voice=$name; languages=$languages; models=$models} | ConvertTo-Json -Compress))
    } catch {
        [Console]::WriteLine('{"ok":false}')
    } finally {
        if ($null -ne $stream) { try { $stream.Close() } catch {} }
        if ([IO.File]::Exists($path)) { [IO.File]::Delete($path) }
    }
}
