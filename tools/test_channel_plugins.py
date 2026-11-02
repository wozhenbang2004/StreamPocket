"""Offline response and real plugin-loader tests; no personal cookies or platform traffic."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import unittest
from types import SimpleNamespace

from yt_dlp import YoutubeDL
from yt_dlp.utils import ExtractorError

ROOT = Path(__file__).resolve().parents[1]
PLUGIN_ROOT = ROOT / 'app/src/main/assets/plugins'
SPEC = importlib.util.spec_from_file_location('streampocket_kuaishou_test', PLUGIN_ROOT / 'streampocket/yt_dlp_plugins/extractor/kuaishou.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
IE = MODULE.StreamPocketKuaishouIE


class ChannelPluginTests(unittest.TestCase):
    def fixture(self):
        return {'defaultClient': {
            'VisionVideoDetailPhoto:3target': {'caption': 'Target only', 'duration': 10000, 'manifest': {'type': 'id', 'id': 'Manifest:1'}},
            'Manifest:1': {'adaptationSet': [{'__ref': 'Set:1'}]},
            'Set:1': {'representation': [{'type': 'id', 'id': 'Rep:1'}]},
            'Rep:1': {'url': {'json': ['https://cdn.example/target.mp4']}, 'backupUrl': 'https://cdn.example/backup.mp4', 'height': 1080, 'width': 1920, 'frameRate': 60},
            'VisionVideoDetailPhoto:unrelated': {'caption': 'Recommendation', 'photoUrl': 'https://cdn.example/wrong.mp4'},
        }}

    def test_selects_requested_photo_and_resolves_manifests_and_backup_urls(self):
        result = IE.extract_photo(self.fixture(), '3target')
        self.assertEqual(result['id'], '3target')
        self.assertEqual(result['title'], 'Target only')
        self.assertEqual(result['duration'], 10)
        self.assertEqual(result['formats'][0]['height'], 1080)
        self.assertEqual(result['formats'][0]['fps'], 60)
        self.assertEqual([f['url'] for f in result['formats']], ['https://cdn.example/target.mp4', 'https://cdn.example/backup.mp4'])

    def test_missing_photo_never_falls_back_to_a_recommendation(self):
        with self.assertRaises(ExtractorError):
            IE.extract_photo(self.fixture(), 'absent')

    def test_cycles_images_and_non_http_sources_fail_without_returning_a_video(self):
        for photo in [{'manifest': {'__ref': 'VisionVideoDetailPhoto:3target'}}, {'photoUrl': 'javascript:alert(1)'}, {'atlas': ['https://cdn.example/image.jpg']}]:
            with self.assertRaises(ExtractorError):
                IE.extract_photo({'defaultClient': {'VisionVideoDetailPhoto:3target': photo}}, '3target')

    def test_photo_url_fallback_and_json_encoded_h265_manifest(self):
        state = {'defaultClient': {'VisionVideoDetailPhoto:3target': {'photoUrl': 'https://cdn.example/direct.mp4',
            'manifestH265': {'json': {'adaptationSet': [{'representation': [{'url': 'https://cdn.example/h265.mp4', 'height': 720}]}]}}}}}
        result = IE.extract_photo(state, '3target')
        self.assertEqual(len(result['formats']), 2)
        self.assertEqual(result['formats'][0]['height'], 720)

    def test_short_links_and_long_links_are_routed_to_the_plugin(self):
        for url in ['https://www.kuaishou.com/short-video/3target', 'https://v.kuaishou.com/share', 'https://www.gifshow.com/s/share', 'https://www.chenzhongtech.com/fw/photo/3target?x=1']:
            self.assertTrue(IE.suitable(url), url)
        self.assertFalse(IE.suitable('https://kuaishou.com.attacker.example/short-video/3target'))
        self.assertEqual(IE.photo_id('https://www.chenzhongtech.com/fw/photo/3target?x=1'), '3target')
        self.assertEqual(IE.photo_id('https://www.kuaishou.com/?photoId=3target'), '3target')

    def test_real_extract_uses_the_final_share_target_and_page_state(self):
        ie = IE(YoutubeDL({'quiet': True, 'no_warnings': True}))
        ie._download_webpage_handle = lambda *args, **kwargs: (
            '<script>window.__APOLLO_STATE__=' + json.dumps(self.fixture()) + ';</script>',
            SimpleNamespace(url='https://www.kuaishou.com/short-video/3target'))
        self.assertEqual(ie._real_extract('https://v.kuaishou.com/share')['id'], '3target')

    def test_real_asset_loader_exposes_the_added_extractor(self):
        code = 'from yt_dlp.globals import plugin_dirs;from yt_dlp.plugins import load_all_plugins;from yt_dlp import YoutubeDL;import sys;plugin_dirs.value=[sys.argv[1]];load_all_plugins();print(YoutubeDL({"quiet":True}).get_info_extractor("StreamPocketKuaishou").IE_NAME)'
        result = subprocess.run([sys.executable, '-c', code, str(PLUGIN_ROOT)], capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('kuaishou:streampocket', result.stdout)

    def test_mobile_share_page_resolves_id_then_fetches_official_work_page_once(self):
        ie = IE(YoutubeDL({'quiet': True, 'no_warnings': True}))
        calls = []
        def fetch(url, *args, **kwargs):
            calls.append(url)
            if len(calls) == 1:
                return '<html>移动分享页</html>', SimpleNamespace(url='https://www.chenzhongtech.com/fw/photo/3target')
            return '<script>window.__APOLLO_STATE__=' + json.dumps(self.fixture()) + ';</script>', SimpleNamespace(url=url)
        ie._download_webpage_handle = fetch
        self.assertEqual(ie._real_extract('https://v.kuaishou.com/share')['id'], '3target')
        self.assertEqual(calls, ['https://v.kuaishou.com/share', 'https://www.kuaishou.com/short-video/3target'])

    def test_canonical_challenge_is_not_refetched_or_treated_as_success(self):
        ie = IE(YoutubeDL({'quiet': True, 'no_warnings': True}))
        calls = []
        def fetch(url, *args, **kwargs):
            calls.append(url)
            return '<html>验证页面</html>', SimpleNamespace(url=url)
        ie._download_webpage_handle = fetch
        with self.assertRaises(ExtractorError):
            ie._real_extract('https://www.kuaishou.com/short-video/3target')
        self.assertEqual(len(calls), 1)


if __name__ == '__main__':
    unittest.main()
