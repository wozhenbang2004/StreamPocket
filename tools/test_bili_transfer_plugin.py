"""Offline regressions for the Bili range bootstrap using the actual bundled core."""
import importlib.util
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

PROJECT = Path(__file__).resolve().parents[1]
CORE = PROJECT / 'app/src/main/res/raw/ytdlp'
sys.path.insert(0, str(CORE))
from yt_dlp import YoutubeDL
from yt_dlp.downloader import get_suitable_downloader
from yt_dlp.downloader.external import Aria2cFD
from yt_dlp.utils import PostProcessingError

PATH = PROJECT / 'app/src/main/assets/plugins/streampocket/yt_dlp_plugins/postprocessor/bili_ranges.py'
SPEC = importlib.util.spec_from_file_location('streampocket_bili_transfer_tests', PATH)
PLUGIN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PLUGIN)


class Response:
    status = 206
    def __init__(self, value='bytes 0-9135703/9135704', length='9135704'):
        self.headers = {'Content-Range': value, 'Content-Length': length}
        self.closed = False
    def __enter__(self): return self
    def __exit__(self, *args): self.closed = True
    def read(self, *args): raise AssertionError('The bootstrap must not download the complete media body')


class BiliTransferTests(unittest.TestCase):
    def setUp(self):
        self.directory = Path(tempfile.mkdtemp(prefix='bili-transfer-test-'))
        self.ydl = YoutubeDL({'quiet': True, 'outtmpl': str(self.directory / '%(title)s.%(ext)s'),
            'external_downloader': {'default': 'libaria2c.so', 'm3u8': 'native', 'dash': 'native'},
            'external_downloader_args': {'aria2c': ['-x4', '-s4', '-j1', '--stop-with-process=123']}})
        self.processor = PLUGIN.StreamPocketBiliRangesPP(self.ydl)
        self.output = []
        self.ydl.to_stdout = self.output.append
        self.ydl.to_screen = lambda *args, **kwargs: None
        self.metadata_directories = []
        original = PLUGIN.tempfile.mkdtemp
        def directory(*args, **kwargs):
            value = original(*args, **kwargs); self.metadata_directories.append(Path(value)); return value
        patcher = patch.object(PLUGIN.tempfile, 'mkdtemp', side_effect=directory)
        patcher.start(); self.addCleanup(patcher.stop)
    def tearDown(self):
        self.ydl.close()
        shutil.rmtree(self.directory, ignore_errors=True)
        for directory in self.metadata_directories: shutil.rmtree(directory, ignore_errors=True)
    def info(self):
        return {'id': 'fixture', 'title': '中文 <原片> & 范围', 'ext': 'mp4', 'format_id': '16',
            'url': 'https://cdn.example.invalid/video?private=fixture', 'webpage_url': 'https://www.bilibili.com/video/BVfixture',
            'protocol': 'https', 'http_headers': {'Range': 'bytes=0-', 'Referer': 'https://www.bilibili.com/', 'User-Agent': 'fixture'}}
    def test_exact_metadata_makes_first_piece_range_without_overriding_aria2_headers(self):
        info=self.info(); response=Response()
        with patch.object(self.ydl, 'urlopen', return_value=response) as request:
            self.processor.run(info)
        self.assertTrue(response.closed)
        self.assertEqual(request.call_args.args[0].headers['Range'], 'bytes=0-')
        self.assertEqual(info['filesize'], 9135704)
        self.assertNotIn('range', [key.lower() for key in info['http_headers']])
        arguments=self.ydl.params['external_downloader_args']['aria2c']
        self.assertIn('--stop-with-process=123', arguments)
        self.assertIn('-x4', arguments)
        metadata=Path(next(arg.split('=',1)[1] for arg in arguments if arg.startswith('--metalink-file=')))
        root=ET.parse(metadata).getroot(); node=root.find('{*}file')
        self.assertEqual(node.get('name'), Path(self.ydl.prepare_filename(info,'temp')+'.part').name)
        self.assertEqual(node.find('{*}size').text, '9135704')
        self.assertEqual(node.find('{*}url').text, info['url'])
        self.assertEqual(metadata.stat().st_mode & 0o777, 0o600)
        with patch.object(Aria2cFD, '_write_cookies', return_value='/private/fixture-cookie-snapshot'):
            command=Aria2cFD(self.ydl,self.ydl.params)._make_cmd('/private/video.mp4.part',info)
        self.assertFalse(any(arg.lower().startswith('range:') for arg in command))
        self.assertEqual(sum(arg.startswith('--metalink-file=') for arg in command),1)
        self.assertIn('SP_TRANSFER_MODE:parallel-http',self.output)
    def test_each_track_selects_its_own_metadata_after_the_real_finished_hook(self):
        info=self.info();info['format_id']='30066+30280'; info['ext']='mkv'
        info['requested_formats']=[dict(info,format_id='30066',ext='mp4'),dict(info,format_id='30280',ext='m4a')]
        with patch.object(self.ydl,'urlopen',side_effect=[Response(),Response('bytes 0-2026506/2026507','2026507')]):
            self.processor.run(info)
        first=self.ydl.params['external_downloader_args']['aria2c'][-1]
        hook=self.ydl._progress_hooks[-1]
        hook({'status':'downloading','info_dict':{'format_id':'30066'}})
        self.assertEqual(first,self.ydl.params['external_downloader_args']['aria2c'][-1])
        hook({'status':'finished','info_dict':{'format_id':'30066'}})
        second=self.ydl.params['external_downloader_args']['aria2c'][-1]
        self.assertNotEqual(first,second)
        hook({'status':'finished','info_dict':{'format_id':'30066'}})
        self.assertEqual(second,self.ydl.params['external_downloader_args']['aria2c'][-1])
        meta=ET.parse(second.split('=',1)[1]).getroot().find('{*}file')
        self.assertTrue(meta.get('name').endswith('.f30280.m4a.part'))
        self.assertEqual(meta.find('{*}size').text,'2026507')
    def test_invalid_or_unavailable_range_falls_back_before_writing_any_bytes(self):
        info=self.info()
        with patch.object(self.ydl,'urlopen',return_value=Response('bytes 0-1/9135704','2')):
            self.processor.run(info)
        self.assertEqual(self.ydl.params['external_downloader']['default'],'native')
        self.assertEqual(info['http_headers']['Range'],'bytes=0-')
        self.assertTrue((self.directory/'.streampocket-bili-native').exists())
        self.assertIn('SP_TRANSFER_MODE:native-http',self.output)
        self.assertEqual(get_suitable_downloader(info,self.ydl.params).__name__,'HttpFD')
    def test_existing_sparse_partial_cannot_be_reused_by_native_fallback(self):
        info=self.info();part=Path(PLUGIN._download_names(self.ydl,info,[info])[0]);part.write_bytes(b'sparse-fixture')
        with patch.object(self.ydl,'urlopen',side_effect=OSError('fixture network error')):
            with self.assertRaises(PostProcessingError):self.processor.run(info)
        self.assertEqual(part.read_bytes(),b'sparse-fixture')
        self.assertFalse((self.directory/'.streampocket-bili-native').exists())
        self.assertEqual(self.ydl.params['external_downloader']['default'],'libaria2c.so')
    def test_native_fallback_marker_preserves_native_retry_without_a_probe(self):
        info=self.info();(self.directory/'.streampocket-bili-native').write_text('native-range-v1')
        part=Path(PLUGIN._download_names(self.ydl,info,[info])[0]);part.write_bytes(b'native-fixture')
        with patch.object(self.ydl,'urlopen') as request:self.processor.run(info)
        request.assert_not_called();self.assertEqual(part.read_bytes(),b'native-fixture')
        self.assertEqual(self.ydl.params['external_downloader']['default'],'native')
    def test_host_scope_and_hls_keep_their_original_downloader_and_headers(self):
        for url,protocol in [('https://bilibili.com.evil.invalid/video','https'),('https://www.bilibili.com/video/BVfixture','m3u8_native')]:
            info=self.info();info.update(webpage_url=url,protocol=protocol)
            with patch.object(self.ydl,'urlopen') as request:self.processor.run(info)
            request.assert_not_called();self.assertEqual(info['http_headers']['Range'],'bytes=0-')
            self.assertEqual(self.ydl.params['external_downloader']['default'],'libaria2c.so')
    def test_public_plugin_loader_finds_the_packaged_bootstrap(self):
        code=('import sys;sys.path.insert(0,sys.argv[1]);from yt_dlp.globals import plugin_dirs;'
              'from yt_dlp.plugins import load_all_plugins;from yt_dlp.postprocessor import get_postprocessor;'
              'plugin_dirs.value=[sys.argv[2]];load_all_plugins();print(get_postprocessor("StreamPocketBiliRanges").__name__)')
        process=subprocess.run([sys.executable,'-c',code,str(CORE),str(PROJECT/'app/src/main/assets/plugins')],capture_output=True,text=True,timeout=30)
        self.assertEqual(process.returncode,0,process.stderr);self.assertEqual(process.stdout.strip(),'StreamPocketBiliRangesPP')

if __name__=='__main__': unittest.main()
