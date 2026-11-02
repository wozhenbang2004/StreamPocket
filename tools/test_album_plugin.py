"""Selected-post fixtures and actual bundled CLI image transfers, without platform accounts."""
import importlib.util
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import threading
import unittest
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from yt_dlp import YoutubeDL
from yt_dlp.utils import ExtractorError

ROOT = Path(__file__).resolve().parents[1]
PLUGINS = ROOT / 'app/src/main/assets/plugins'
SPEC = importlib.util.spec_from_file_location('streampocket_album_test', PLUGINS / 'streampocket/yt_dlp_plugins/extractor/albums.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
IE = MODULE.XiaoHongShuIE


class AlbumPluginTests(unittest.TestCase):
    def ie(self, note):
        ie = IE(YoutubeDL({'quiet': True, 'no_warnings': True}))
        state = {'note': {'noteDetailMap': {'123abc': {'note': note}, 'other': {'note': {'noteId': 'other', 'type': 'normal'}}}}}
        ie._download_webpage = lambda *a, **k: '<script>window.__INITIAL_STATE__=' + json.dumps(state) + ';</script>'
        return ie

    def test_only_selected_note_can_become_an_album(self):
        note = {'noteId': '123abc', 'type': 'normal', 'title': 'Synthetic album',
                'imageList': [{'urlDefault': 'https://sns-webpic.xhscdn.com/1.webp?signature=a%2Fb%3D'},
                              {'urlDefault': 'https://sns-webpic.xhscdn.com/2.jpg'}]}
        info = self.ie(note)._real_extract('https://www.xiaohongshu.com/explore/123abc')
        self.assertTrue(info['_streampocket_album'])
        self.assertEqual(len(info['entries']), 2)
        self.assertIn('signature=a%2Fb%3D', info['entries'][0]['url'])
        note['noteId'] = 'other'
        with self.assertRaises(ExtractorError): self.ie(note)._real_extract('https://www.xiaohongshu.com/explore/123abc')

    def test_empty_oversized_invalid_and_incomplete_albums_fail(self):
        for images in [[], [None], ['file:///private/image.jpg'], ['https://cdn.example/1.jpg'] * 101]:
            with self.assertRaises(ExtractorError): MODULE.album_result('123abc', 'fixture', images, 'https://www.xiaohongshu.com/explore/123abc')

    def test_video_formats_and_original_video_fallback_are_preserved(self):
        note = {'noteId': '123abc', 'type': 'video', 'video': {'media': {'stream': {'h264': [
            {'masterUrl': 'https://sns-video.xhscdn.com/1.mp4', 'backupUrls': ['https://sns-video.xhscdn.com/2.mp4'], 'height': 720, 'duration': 3000}]}}}}
        result = self.ie(note)._real_extract('https://www.xiaohongshu.com/explore/123abc')
        self.assertEqual(len(result['formats']), 2);self.assertEqual(result['duration'], 3)
        note['video'] = {'consumer': {'originVideoKey': 'fixture/original.mp4'}}
        ie = self.ie(note)
        class Handle:
            url = 'https://sns-video-bd.xhscdn.com/fixture/original.mp4'
            def get_header(self, name): return '123'
        ie._request_webpage = lambda *a, **k: Handle()
        result = ie._real_extract('https://www.xiaohongshu.com/explore/123abc')
        self.assertEqual(result['formats'][0]['format_id'], 'direct')

    def test_actual_plugin_namespace_keeps_douyin_compatibility_and_image_hook(self):
        code = """import sys
sys.path.insert(0, sys.argv[1])
from yt_dlp import YoutubeDL
from yt_dlp_plugins.extractor.douyin_compat import _StreamPocketDouyinIE
ie=_StreamPocketDouyinIE(YoutubeDL({'quiet':True}))
assert ie.suitable('https://www.douyin.com/note/1234567890')
result=ie._parse_aweme_video_app({'aweme_id':'1234567890','desc':'Synthetic image post','images':[{'url_list':['https://p3-pc-sign.douyinpic.com/fixture.webp']} ]})
assert result['_streampocket_album'] and result['_streampocket_image_count']==1
assert hasattr(ie,'_sign_query')
"""
        p = subprocess.run([sys.executable, '-c', code, str(PLUGINS / 'streampocket')], capture_output=True, text=True, timeout=20)
        self.assertEqual(p.returncode, 0, p.stderr)

    def test_actual_cli_downloads_each_image_and_keeps_album_marker(self):
        def chunk(name, value):
            return struct.pack('!I', len(value)) + name + value + struct.pack('!I', zlib.crc32(name + value) & 0xffffffff)
        png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!2I5B', 2, 2, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress((b'\0' + b'\x60\x80\x70' * 2) * 2)) + chunk(b'IEND', b'')
        calls = []
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                calls.append(self.path);self.send_response(200);self.send_header('Content-Type', 'image/png');self.send_header('Content-Length', str(len(png)));self.end_headers();self.wfile.write(png)
            def log_message(self, *args): pass
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True);thread.start()
        try:
            with tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary)
                info = MODULE.album_result('123abc', 'Synthetic album', [f'http://127.0.0.1:{server.server_port}/{n}.png' for n in range(2)], 'https://www.xiaohongshu.com/explore/123abc')
                info.pop('webpage_url', None)
                info.update(extractor='XiaoHongShu', extractor_key='XiaoHongShu')
                info_file = folder / 'private.json';info_file.write_text(json.dumps(info))
                p = subprocess.run([sys.executable, str(ROOT / 'app/src/main/res/raw/ytdlp'), '--ignore-config', '--plugin-dirs', str(PLUGINS),
                                    '--load-info-json', str(info_file), '--no-clean-info-json', '--no-simulate', '--no-progress', '--print', 'after_move:filepath',
                                    '--max-filesize', '2M', '-o', str(folder / '%(id)s.%(ext)s')], capture_output=True, text=True, timeout=30)
                self.assertEqual(p.returncode, 0, p.stderr)
                files = list(folder.glob('123abc-*.*'));self.assertEqual(len(files), 2)
                for file in files: self.assertEqual(file.read_bytes(), png)
                self.assertEqual(len(calls), 2)
                p = subprocess.run([sys.executable, str(ROOT / 'app/src/main/res/raw/ytdlp'), '--ignore-config', '--no-clean-info-json', '--load-info-json', str(info_file), '--skip-download', '--dump-single-json'], capture_output=True, text=True, timeout=30)
                self.assertEqual(p.returncode, 0, p.stderr);self.assertTrue(json.loads(p.stdout)['_streampocket_album'])
        finally: server.shutdown();server.server_close();thread.join(timeout=5)


if __name__ == '__main__': unittest.main()
