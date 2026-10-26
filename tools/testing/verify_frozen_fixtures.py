"""Verify exact frozen bytes before running parser tests, independently of Android/Gradle."""
import hashlib
import json
from pathlib import Path


def verify(root):
    checked = 0
    for frozen in sorted(root.glob('*/frozen-sha256.json')):
        values = json.loads(frozen.read_text(encoding='utf-8'))
        for name, expected in values.items():
            path = frozen.parent / name
            if path.parent != frozen.parent or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
                raise ValueError(f'Frozen input or annotation changed: {path.name}')
            checked += 1
    if checked == 0:
        raise ValueError('No frozen fixtures found')
    print(f'Validated {checked} frozen input/annotation files')


if __name__ == '__main__':
    verify(Path(__file__).resolve().parents[2] / 'tests/fixtures')
