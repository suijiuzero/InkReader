"""Add the verified local Nanami model to the existing adapter voice folder (Windows)."""
from pathlib import Path, PurePosixPath
import hashlib
import tempfile
import time
import urllib.request
import winreg
import xml.etree.ElementTree as ET
import zipfile

NAME = 'MicrosoftWindows.Voice.ja-JP.Nanami.1_1.0.4.0_x64__cw5n1h2txyewy.Msix'
URL = 'https://dl.nvdacn.com/NVDA-Addons/TTS/NaturalVoices/' + NAME
# Pin the exact package exercised with the existing adapter, not a claim of publisher authentication.
SHA256 = 'c19bc53946846b5f6964e10bd45263989ca768e05dffb4fbe4f3293bdd74f92a'


def main():
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r'Software\NaturalVoiceSAPIAdapter\Enumerator') as key:
        folder = Path(winreg.QueryValueEx(key, 'NarratorVoicePath')[0]).resolve(strict=True)
    if not folder.is_dir() or not str(folder).isascii():
        raise ValueError('Adapter requires an existing ASCII-only voice folder')
    target = (folder / 'Nanami').resolve()
    if target.parent != folder:
        raise ValueError('Invalid destination')
    if target.exists():
        raise FileExistsError('Nanami folder already exists; no files were replaced')
    cache = Path(__file__).resolve().parent / '.runtime/packages'
    cache.mkdir(parents=True, exist_ok=True)
    package = cache / NAME
    if not package.exists():
        partial = package.with_suffix('.partial')
        request = urllib.request.Request(URL + '?inkreader=' + str(int(time.time())), headers={'User-Agent': 'InkReader local voice setup'})
        with urllib.request.urlopen(request, timeout=45) as response, partial.open('wb') as output:
            total = 0
            while chunk := response.read(1024 * 1024):
                total += len(chunk)
                if total > 150_000_000:
                    raise ValueError('Package too large')
                output.write(chunk)
        partial.replace(package)
    if hashlib.sha256(package.read_bytes()).hexdigest() != SHA256:
        raise ValueError('Package hash mismatch; refusing to extract')
    with zipfile.ZipFile(package) as archive:
        if len(archive.infolist()) > 5000 or sum(i.file_size for i in archive.infolist()) > 500_000_000:
            raise ValueError('Package extraction budget exceeded')
        for entry in archive.infolist():
            path = PurePosixPath(entry.filename.replace('\\', '/'))
            if path.is_absolute() or '..' in path.parts or ':' in str(path):
                raise ValueError('Invalid archive path')
        identity = ET.fromstring(archive.read('AppxManifest.xml')).find('{*}Identity')
        if identity is None or identity.get('Name') != 'MicrosoftWindows.Voice.ja-JP.Nanami.1':
            raise ValueError('Unexpected voice identity')
        if archive.testzip() is not None:
            raise ValueError('Corrupt package')
        # Stage outside the enumerated Voices folder; existing voices stay usable.
        with tempfile.TemporaryDirectory(prefix='InkReader-Nanami-', dir=folder.parent) as staging:
            extracted = Path(staging) / 'Nanami'
            extracted.mkdir()
            archive.extractall(extracted)
            extracted.rename(target)
    print('Installed local Japanese Nanami voice. Restart the InkReader computer bridge.')


if __name__ == '__main__':
    main()
