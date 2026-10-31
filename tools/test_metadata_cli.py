#!/usr/bin/env python3
"""Offline fixture for the two yt-dlp dump flags used by the Android adapter.

Requires an installed yt-dlp. This is mocked playlist metadata, not a live X,
Bilibili or Douyin download test. The invalid-fixture scheme fails locally;
--skip-download and --no-check-formats prevent fetching the valid mock clip.
"""

import json
import subprocess
import sys
import tempfile
from pathlib import Path


def main() -> None:
    try:
        from yt_dlp.version import __version__
    except ImportError as error:
        raise SystemExit("Install yt-dlp in the test environment before running this fixture.") from error

    clip = {
        "_type": "video",
        "id": "clip1",
        "title": "First mock clip",
        "extractor": "fixture",
        "extractor_key": "Generic",
        "url": "https://example.invalid/fixture.mp4",
        "ext": "mp4",
        "formats": [{
            "format_id": "http-mp4",
            "url": "https://example.invalid/fixture.mp4",
            "ext": "mp4",
            "vcodec": "h264",
            "acodec": "aac",
            "height": 720,
        }],
    }
    playlist = {
        "_type": "playlist",
        "id": "post",
        "title": "Mock post with a failed second clip",
        "extractor": "fixture",
        "extractor_key": "Generic",
        "entries": [clip, {
            "_type": "url",
            "url": "invalid-fixture://unavailable",
            "ie_key": "Generic",
        }],
    }
    with tempfile.TemporaryDirectory(prefix="streampocket-metadata-") as directory:
        fixture = Path(directory) / "partial-playlist.info.json"
        fixture.write_text(json.dumps(playlist), encoding="utf-8")
        command = [
            sys.executable, "-m", "yt_dlp",
            "--ignore-config", "--no-plugin-dirs",
            # Loader-only flag: otherwise yt-dlp sanitizes saved playlist entries away.
            "--no-clean-info-json",
            "--dump-json", "--dump-single-json", "--ignore-errors",
            "--skip-download", "--no-check-formats", "--no-playlist",
            "--socket-timeout", "1", "--retries", "0",
            "--load-info-json", str(fixture),
        ]
        result = subprocess.run(command, text=True, capture_output=True, timeout=15, check=False)

    assert result.returncode == 1, f"Expected partial failure exit 1, got {result.returncode}: {result.stderr}"
    rows = [json.loads(line) for line in result.stdout.splitlines() if line.startswith("{")]
    assert len(rows) == 2, f"Expected per-clip JSON plus playlist JSON, got {len(rows)}"
    root = rows[-1]
    assert root["id"] == "post", "Playlist root must be the last JSON object"
    assert root["entries"][0]["id"] == "clip1", "Successful media must remain available"
    assert root["entries"][1] is None, "Failed media must remain an explicit null slot"
    print(f"PASS: yt-dlp {__version__}; mocked partial playlist, exit 1, two JSON rows, final root [clip1, null]")


if __name__ == "__main__":
    main()
