#!/usr/bin/env python3
"""Small source-only Gradle bootstrap. Downloads the official pinned distribution."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

VERSION = '8.14.6'
SHA256 = '7988ed071b2a07900e2ec715fca15c6ab72bce6db433af301fa7fa7e9407bd24'
URL = f'https://downloads.gradle.org/distributions/gradle-{VERSION}-bin.zip'
ROOT = Path(__file__).resolve().parents[1]


def main():
    cache = ROOT / '.tools'
    distribution = cache / f'gradle-{VERSION}'
    binary = distribution / 'bin' / ('gradle.bat' if os.name == 'nt' else 'gradle')
    if not binary.is_file():
        cache.mkdir(exist_ok=True)
        archive = cache / f'gradle-{VERSION}-bin.zip'
        partial = archive.with_suffix('.download')
        if not archive.is_file():
            print(f'Downloading official Gradle {VERSION} (SHA-256 pinned)…', flush=True)
            digest = hashlib.sha256()
            try:
                with urllib.request.urlopen(URL, timeout=60) as response, partial.open('wb') as output:
                    while True:
                        chunk = response.read(1024 * 1024)
                        if not chunk:
                            break
                        digest.update(chunk)
                        output.write(chunk)
                if digest.hexdigest() != SHA256:
                    raise RuntimeError('Gradle checksum mismatch; the download will not execute')
                partial.replace(archive)
            finally:
                partial.unlink(missing_ok=True)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
            raise RuntimeError('Cached Gradle archive checksum mismatch; remove the archive and retry')
        with tempfile.TemporaryDirectory(dir=cache) as staging:
            staging_path = Path(staging).resolve()
            with zipfile.ZipFile(archive) as package:
                for item in package.infolist():
                    target = (staging_path / item.filename).resolve()
                    if staging_path not in target.parents:
                        raise RuntimeError('Unsafe path in archive')
                package.extractall(staging_path)
            shutil.move(str(staging_path / distribution.name), str(distribution))
        if os.name != 'nt':
            binary.chmod(0o755)
    return subprocess.call([str(binary), *sys.argv[1:]], cwd=ROOT)


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        print(f'Build bootstrap failed: {error}', file=sys.stderr)
        print('Requires Python 3.9+, JDK 17, Android SDK 36 / Build Tools 35.0.0, and access to official repositories.', file=sys.stderr)
        sys.exit(1)
