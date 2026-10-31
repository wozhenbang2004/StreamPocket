"""Offline regression tests for the experimental plugin, using real yt-dlp APIs.

Requires an installed yt-dlp package. No platform requests or downloads run here.
The fixed signature vector was derived from the upstream formula with an explicit
preimage and cross-checked with OpenSSL, not supplied as a golden by the PR author.
"""

import importlib.util
import os
from pathlib import Path
import subprocess
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from yt_dlp import YoutubeDL
from yt_dlp.utils import ExtractorError


PROJECT = Path(__file__).resolve().parents[1]
PLUGIN_ROOT = PROJECT / 'app/src/main/assets/plugins'
PLUGIN_PATH = PLUGIN_ROOT / 'streampocket/yt_dlp_plugins/extractor/douyin_compat.py'
SPEC = importlib.util.spec_from_file_location('streampocket_douyin_test_module', PLUGIN_PATH)
PLUGIN = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = PLUGIN
SPEC.loader.exec_module(PLUGIN)
EXTRACTOR = PLUGIN._StreamPocketDouyinIE
VIDEO_ID = '6961737553342991651'


class DouyinPluginTests(unittest.TestCase):
    def setUp(self):
        self.ydl = YoutubeDL({'quiet': True, 'no_warnings': True})
        self.ie = EXTRACTOR(self.ydl)
        # A new in-memory jar per case; these tests never use personal cookies.
        self.ydl.cookiejar.clear()

    def test_loader_finds_asset_layout_and_preserves_builtin_key(self):
        # --list-extractors exits before CLI plugin initialization, so exercise
        # the actual loader and subsequent YoutubeDL lookup in a fresh process.
        code = (
            'import sys; from yt_dlp.globals import plugin_dirs; '
            'from yt_dlp.plugins import load_all_plugins; from yt_dlp import YoutubeDL; '
            'plugin_dirs.value=[sys.argv[1]]; load_all_plugins(); '
            'ydl=YoutubeDL({"quiet":True}); ie=ydl.get_info_extractor("Douyin"); '
            'print(ie.IE_NAME); print(ie.ie_key())'
        )
        result = subprocess.run([
            sys.executable, '-c', code, str(PLUGIN_ROOT),
        ], capture_output=True, text=True, timeout=30, env=os.environ.copy())
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines(), ['Douyin+streampocket-experimental', 'Douyin'])
        self.assertEqual(EXTRACTOR.ie_key(), 'Douyin')

    def test_existing_required_cookies_are_not_replaced(self):
        self.ie._set_cookie('.douyin.com', 'passport_csrf_token', 'existing-csrf')
        self.ie._set_cookie('.douyin.com', 'ttwid', 'existing-ttwid')
        with patch.object(self.ie, '_request_webpage', side_effect=AssertionError('Unexpected network')):
            self.ie.check_and_get_required_cookies()
        cookies = self.ie._get_cookies(self.ie._WEBPAGE_HOST)
        self.assertEqual(cookies['passport_csrf_token'].value, 'existing-csrf')
        self.assertEqual(cookies['ttwid'].value, 'existing-ttwid')

    def test_missing_required_cookies_are_initialized(self):
        with patch.object(self.ie, '_get_ttwid', return_value='fixture-ttwid'):
            self.ie.check_and_get_required_cookies()
        cookies = self.ie._get_cookies(self.ie._WEBPAGE_HOST)
        self.assertEqual(cookies['ttwid'].value, 'fixture-ttwid')
        self.assertEqual(cookies['passport_csrf_token'].value, self.ie._CSRF)

    def test_missing_registration_cookie_fails_clearly(self):
        with patch.object(self.ie, '_get_ttwid', return_value=None):
            with self.assertRaisesRegex(ExtractorError, 'Unable to generate required cookies'):
                self.ie.check_and_get_required_cookies()

    def test_cookie_uifid_and_header_fallback(self):
        self.ie._set_cookie('.douyin.com', 'UIFID', 'fixture-cookie-id')
        with patch.object(self.ie, '_request_webpage', side_effect=AssertionError('Unexpected network')):
            self.assertEqual(self.ie._get_uifid(), 'fixture-cookie-id')
        self.ydl.cookiejar.clear()
        with patch.object(self.ie, '_request_webpage', return_value=SimpleNamespace(
                headers={'x-tt-trace-host': 'fixture-header-id'})):
            self.assertEqual(self.ie._get_uifid(), 'fixture-header-id')

    def test_manually_derived_signature_vector(self):
        # Literal bytes independently assembled from the pinned PR's formula:
        # fixture-uifid_1700000000_A96D855A08C0A9707F8BEF0D9A527E4E_
        # aweme_id=6961737553342991651&aid=6383&uifid=fixture-uifid&timestamp=1700000000
        # openssl dgst -md5 on those concatenated bytes produced the digest below.
        original = {'aweme_id': VIDEO_ID, 'aid': 6383}
        with patch.object(self.ie, '_get_uifid', return_value='fixture-uifid'), \
                patch.object(PLUGIN.time, 'time', return_value=1700000000):
            signed = self.ie._sign_query(original)
        self.assertEqual(signed['x-secsdk-web-signature'], '60ecac822af18e4853311c42e8232fb8')
        self.assertEqual(signed['timestamp'], '1700000000')
        self.assertEqual(original, {'aweme_id': VIDEO_ID, 'aid': 6383})

    def test_detail_query_includes_full_quality_aid(self):
        detail = {'aweme_id': VIDEO_ID}
        with patch.object(self.ie, '_sign_query', side_effect=lambda value: value) as sign, \
                patch.object(self.ie, '_download_json', return_value={'aweme_detail': detail}) as get:
            self.assertEqual(self.ie._get_aweme_detail(VIDEO_ID), detail)
        sign.assert_called_once_with({'aweme_id': VIDEO_ID, 'aid': 6383})
        self.assertEqual(get.call_args.kwargs['query']['aid'], 6383)

    def test_extraction_keeps_formats_and_sets_required_referer(self):
        detail = {'aweme_id': VIDEO_ID}
        formats = [{'url': 'https://example.invalid/video.mp4', 'height': 1080}]
        with patch.object(self.ie, 'check_and_get_required_cookies'), \
                patch.object(self.ie, '_get_aweme_detail', return_value=detail), \
                patch.object(self.ie, '_parse_aweme_video_app', return_value={
                    'id': VIDEO_ID, 'formats': formats}):
            result = self.ie._real_extract(f'https://www.douyin.com/video/{VIDEO_ID}')
        self.assertEqual(result['formats'], formats)
        self.assertEqual(result['http_headers']['Referer'], 'https://www.douyin.com/')


if __name__ == '__main__':
    unittest.main(verbosity=2)
