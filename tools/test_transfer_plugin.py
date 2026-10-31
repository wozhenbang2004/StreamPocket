"""Offline tests using the bundled yt-dlp public plugin APIs; synthetic data only."""
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

PROJECT = Path(__file__).resolve().parents[1]
CORE = PROJECT / 'app/src/main/res/raw/ytdlp'
sys.path.insert(0, str(CORE))
from yt_dlp import YoutubeDL, parse_options
from yt_dlp.downloader import get_suitable_downloader
from yt_dlp.downloader.external import Aria2cFD

PLUGIN_PATH = PROJECT / 'app/src/main/assets/plugins/streampocket/yt_dlp_plugins/postprocessor/transfer.py'
SPEC = importlib.util.spec_from_file_location('streampocket_transfer_test', PLUGIN_PATH)
PLUGIN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PLUGIN)


class TransferPluginTests(unittest.TestCase):
    def setUp(self):
        guard = patch.object(PLUGIN, '_install_cancel_guard')
        self.mock_guard = guard.start()
        self.addCleanup(guard.stop)

    def test_tracks_actual_python_pid_without_stacking_old_guards(self):
        ydl = YoutubeDL({'quiet': True, 'external_downloader_args': {
            'aria2c': ['-x4', '--stop-with-process=123'], 'ffmpeg': ['-nostdin']}})
        processor = PLUGIN.StreamPocketTransferPP(ydl)
        with patch.object(PLUGIN.os, 'getpid', return_value=9876):
            self.assertEqual(processor.run({'id': 'fixture'}), ([], {'id': 'fixture'}))
            processor.run({'id': 'fixture'})
        self.assertEqual(ydl.params['external_downloader_args']['aria2c'], ['-x4', '--stop-with-process=9876'])
        self.assertEqual(ydl.params['external_downloader_args']['ffmpeg'], ['-nostdin'])
        self.assertEqual(self.mock_guard.call_count, 2)

    def test_optional_argument_map_and_cookie_free_input_are_supported(self):
        ydl = YoutubeDL({'quiet': True, 'external_downloader_args': None})
        information = {'id': 'fixture', 'title': 'no account data'}
        _, result = PLUGIN.StreamPocketTransferPP(ydl).run(information)
        self.assertEqual(result, information)
        self.assertEqual(ydl.params['external_downloader_args']['aria2c'], [f'--stop-with-process={os.getpid()}'])

    def test_cli_selects_native_hls_and_dash_and_bounds_aria2(self):
        options = parse_options(['--ignore-config', '--downloader', 'libaria2c.so',
                                 '--downloader', 'dash,m3u8:native', '--concurrent-fragments', '4',
                                 '--abort-on-unavailable-fragments', '--downloader-args',
                                 'aria2c:-x4 -s4 -j1 --min-split-size=1M']).ydl_opts
        self.assertEqual(options['concurrent_fragment_downloads'], 4)
        self.assertFalse(options['skip_unavailable_fragments'])
        for protocol, expected in [('m3u8_native', 'HlsFD'), ('http_dash_segments', 'DashSegmentsFD')]:
            cls = get_suitable_downloader({'url': 'https://example.invalid/media', 'protocol': protocol}, options)
            self.assertEqual(cls.__name__, expected)
        options['external_downloader_args']['aria2c'].append('--stop-with-process=9876')
        with YoutubeDL(options) as ydl:
            downloader = Aria2cFD(ydl, options)
            with patch.object(downloader, '_write_cookies', return_value='/private/synthetic.txt'):
                command = downloader._make_cmd('/private/fixture.part', {'url': 'https://example.invalid/media'})
        self.assertLess(command.index('-x16'), command.index('-x4'))
        self.assertIn('--stop-with-process=9876', command)

    def test_public_plugin_loader_finds_packaged_postprocessor(self):
        code = ('import sys;sys.path.insert(0,sys.argv[1]);'
                'from yt_dlp.globals import plugin_dirs;from yt_dlp.plugins import load_all_plugins;'
                'from yt_dlp.postprocessor import get_postprocessor;'
                'plugin_dirs.value=[sys.argv[2]];load_all_plugins();'
                'print(get_postprocessor("StreamPocketTransfer").__name__)')
        result = subprocess.run([sys.executable, '-c', code, str(CORE),
                                 str(PROJECT / 'app/src/main/assets/plugins')],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), 'StreamPocketTransferPP')


class CancellationGuardTests(unittest.TestCase):
    def test_installs_only_after_verifying_own_isolated_process_group(self):
        with patch.object(PLUGIN.os, 'getpid', return_value=9876), \
                patch.object(PLUGIN.os, 'getpgrp', side_effect=[123, 9876]), \
                patch.object(PLUGIN.os, 'setpgrp') as isolate, \
                patch.object(PLUGIN.signal, 'signal') as handler:
            PLUGIN._install_cancel_guard()
        isolate.assert_called_once_with()
        handler.assert_called_once_with(PLUGIN.signal.SIGTERM, PLUGIN._terminate_transfer_group)

    def test_refuses_external_download_if_group_cannot_be_isolated(self):
        from yt_dlp.utils import PostProcessingError
        with patch.object(PLUGIN.os, 'getpid', return_value=9876), \
                patch.object(PLUGIN.os, 'getpgrp', return_value=123), \
                patch.object(PLUGIN.os, 'setpgrp', side_effect=PermissionError), \
                patch.object(PLUGIN.signal, 'signal') as handler:
            with self.assertRaises(PostProcessingError):
                PLUGIN._install_cancel_guard()
        handler.assert_not_called()

    def test_cancel_targets_only_the_yt_dlp_owned_group(self):
        with patch.object(PLUGIN.os, 'getpid', return_value=9876), \
                patch.object(PLUGIN.signal, 'signal') as handler, \
                patch.object(PLUGIN.os, 'killpg') as terminate:
            PLUGIN._terminate_transfer_group(PLUGIN.signal.SIGTERM, None)
        handler.assert_called_once_with(PLUGIN.signal.SIGTERM, PLUGIN.signal.SIG_DFL)
        terminate.assert_called_once_with(9876, PLUGIN.signal.SIGTERM)


if __name__ == '__main__':
    unittest.main()
