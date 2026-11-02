"""Offline API fixtures and real loader checks for the selected Heybox post only."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import unittest

from yt_dlp import YoutubeDL
from yt_dlp.utils import ExtractorError

ROOT = Path(__file__).resolve().parents[1]
PLUGIN_ROOT = ROOT / 'app/src/main/assets/plugins'
SPEC = importlib.util.spec_from_file_location('streampocket_heybox_test', PLUGIN_ROOT / 'streampocket/yt_dlp_plugins/extractor/heybox.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
IE = MODULE.StreamPocketHeyboxIE


class HeyboxPluginTests(unittest.TestCase):
    def fixture(self):
        return {'status': 'ok', 'result': {'link': {
            'linkid': 123, 'share_url': 'https://api.xiaoheihe.cn/v3/bbs/app/api/web/share?link_id=abc123',
            'title': '合成游戏视频', 'description': 'Synthetic fixture', 'has_video': 1,
            'video_url': 'https://videoheybox.max-c.com/video/fixture.mp4?auth=a%2Fb%3D',
            'video_thumb': 'https://imgheybox.max-c.com/fixture.jpg',
            'video_width': 1280, 'video_height': 720, 'video_duration': 41,
            'user': {'username': 'fixture', 'userid': 456}},
            'recommendations': [{'linkid': 999, 'has_video': 1, 'video_url': 'https://cdn.example/wrong.mp4'}]}}

    def test_all_observed_single_post_urls_are_recognized(self):
        for url in ['https://www.xiaoheihe.cn/app/bbs/link/abc123', 'https://xiaoheihe.cn/a/abc123',
                    'https://www.xiaoheihe.cn/bbs/post_share?track=a&link_id=abc123',
                    'https://api.xiaoheihe.cn/v3/bbs/app/api/web/share?link_id=abc123&h_src=share',
                    'https://api.xiaoheihe.cn/v3/bbs/app/link/tree?link_id=abc123']:
            self.assertTrue(IE.suitable(url));self.assertEqual(IE.post_id(url), 'abc123')

    def test_invalid_and_conflicting_ids_are_rejected(self):
        for url in ['https://xiaoheihe.cn/app/bbs/home', 'https://xiaoheihe.cn/game/share_game_detail?appid=123',
                    'https://xiaoheihe.cn/bbs/post_share?link_id=&link_id=123',
                    'https://xiaoheihe.cn/bbs/post_share?link_id=123&%6cink_id=123',
                    'https://xiaoheihe.cn/app/bbs/link/123?link_id=456',
                    'https://xiaoheihe.cn/bbs/post_share?link_id=123%26x',
                    'https://xiaoheihe.cn.attacker.example/app/bbs/link/123',
                    'https://xiaoheihe.cn@evil.example/app/bbs/link/123']:
            self.assertIsNone(IE.post_id(url),url)

    def test_exact_numeric_post_and_share_alias_use_only_the_target_link(self):
        for requested in ['123','abc123']:
            info=IE.extract_post(self.fixture(),requested)
            self.assertEqual(info['id'],'123');self.assertEqual(info['height'],720)
            self.assertEqual(info['duration'],41);self.assertIn('auth=a%2Fb%3D',info['url'])
            self.assertEqual(info['http_headers']['Referer'],'https://www.xiaoheihe.cn/')

    def test_mismatched_post_never_uses_recommendations_or_share_metadata(self):
        data=self.fixture();data['result']['share_info']={'linkid':777,'video_url':'https://cdn.example/wrong.mp4'}
        with self.assertRaises(ExtractorError):IE.extract_post(data,'absent')
        del data['result']['link']
        with self.assertRaises(ExtractorError):IE.extract_post(data,'abc123')

    def test_images_and_invalid_media_schemes_never_become_video(self):
        for changes in [{'has_video':0},{'video_url':None},{'video_url':'javascript:alert(1)'},
                        {'video_url':'file:///tmp/fixture.mp4'},{'video_url':''}]:
            data=self.fixture();data['result']['link'].update(changes)
            with self.assertRaises(ExtractorError):IE.extract_post(data,'123')

    def test_challenge_login_deleted_and_malformed_responses_fail(self):
        for data in [None,[],{'status':'show_captcha','result':{}},{'status':'need_login'},
                     {'status':'error'},{'status':'ok','result':None}]:
            with self.assertRaises(ExtractorError):IE.extract_post(data,'123')

    def test_api_failure_does_not_fall_back_to_a_third_party_or_another_post(self):
        ie=IE(YoutubeDL({'quiet':True,'no_warnings':True}));calls=[]
        def fetch(url,*args,**kwargs):
            calls.append(url);return {'status':'show_captcha','result':{}}
        ie._download_json=fetch
        with self.assertRaises(ExtractorError):ie._real_extract('https://www.xiaoheihe.cn/app/bbs/link/123')
        self.assertEqual(len(calls),1);self.assertTrue(calls[0].startswith('https://api.xiaoheihe.cn/bbs/app/link/tree?'))

    def test_real_extract_retains_signed_media_bytes_dimensions_and_headers(self):
        ie=IE(YoutubeDL({'quiet':True,'no_warnings':True}));ie._download_json=lambda *a,**k:self.fixture()
        info=ie._real_extract('https://www.xiaoheihe.cn/app/bbs/link/abc123')
        self.assertEqual(info['formats'][0]['url'],self.fixture()['result']['link']['video_url'])
        self.assertEqual(info['formats'][0]['height'],720)
        self.assertEqual(info['webpage_url'],'https://www.xiaoheihe.cn/app/bbs/link/123')

    def test_hls_uses_existing_manifest_parser_with_official_headers(self):
        ie=IE(YoutubeDL({'quiet':True,'no_warnings':True}));data=self.fixture()
        data['result']['link']['video_url']='https://videoheybox.max-c.com/video/master.m3u8?auth=keep%2F'
        ie._download_json=lambda *a,**k:data;calls=[]
        def manifest(url,video_id,*args,**kwargs):
            calls.append((url,video_id,kwargs));return [{'url':'https://cdn.example/720.m3u8','height':720}]
        ie._extract_m3u8_formats=manifest;info=ie._real_extract('https://www.xiaoheihe.cn/app/bbs/link/123')
        self.assertEqual(len(calls),1);self.assertEqual(calls[0][0],data['result']['link']['video_url'])
        self.assertEqual(calls[0][2]['headers'],info['http_headers'])

    def test_homepage_is_rejected_before_network(self):
        ie=IE(YoutubeDL({'quiet':True,'no_warnings':True}))
        ie._download_json=lambda *a,**k:self.fail('No request may happen for a homepage')
        with self.assertRaises(ExtractorError):ie._real_extract('https://www.xiaoheihe.cn/app/bbs/home')

    def test_actual_plugin_loader_finds_the_installed_extractor(self):
        code='from yt_dlp.globals import plugin_dirs;from yt_dlp.plugins import load_all_plugins;from yt_dlp import YoutubeDL;import sys;plugin_dirs.value=[sys.argv[1]];load_all_plugins();print(YoutubeDL({"quiet":True}).get_info_extractor("StreamPocketHeybox").IE_NAME)'
        result=subprocess.run([sys.executable,'-c',code,str(PLUGIN_ROOT)],capture_output=True,text=True,timeout=30)
        self.assertEqual(result.returncode,0,result.stderr);self.assertIn('heybox:streampocket',result.stdout)


if __name__=='__main__':unittest.main()
