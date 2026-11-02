"""Exercise the bundled official extractor offline, with neutral synthetic pages only."""
import json
import unittest
from types import SimpleNamespace

from yt_dlp import YoutubeDL
from yt_dlp.extractor.pornhub import PornHubIE
from yt_dlp.utils import ExtractorError


class PornhubExtractorTests(unittest.TestCase):
    URL = 'https://www.pornhub.com/view_video.php?viewkey=phfixture123'

    def extractor(self, page, redirected=None):
        ie = PornHubIE(YoutubeDL({'quiet': True, 'no_warnings': True}))
        # No login, age verification, remote traffic or media is performed here.
        ie._login = lambda host: None
        ie._set_age_cookies = lambda host: None
        ie._download_webpage_handle = lambda url, *args, **kwargs: (page, SimpleNamespace(url=redirected or url))
        return ie

    def test_bundled_extractor_routes_all_regular_host_aliases(self):
        for host in ('www.pornhub.com', 'fr.pornhub.net', 'm.pornhub.org'):
            for path in ('/view_video.php?viewkey=phfixture123', '/video/show?viewkey=phfixture123', '/embed/phfixture123'):
                self.assertTrue(PornHubIE.suitable('https://' + host + path))
        self.assertFalse(PornHubIE.suitable('https://pornhub.com.attacker.example/embed/phfixture123'))

    def test_real_extract_returns_requested_synthetic_video_formats_and_headers(self):
        page = '<meta name="twitter:title" content="Synthetic channel fixture"><script>var flashvars_1 = ' + json.dumps({
            'video_duration': '12', 'mediaDefinitions': [{'quality': '720', 'videoUrl': 'https://cdn.example/720p_1000k.mp4'}],
        }) + ';</script>'
        result = self.extractor(page)._real_extract(self.URL)
        self.assertEqual(result['id'], 'phfixture123')
        self.assertEqual(result['title'], 'Synthetic channel fixture')
        self.assertEqual(result['duration'], 12)
        self.assertEqual(result['formats'][0]['url'], 'https://cdn.example/720p_1000k.mp4')
        self.assertEqual(result['formats'][0]['height'], 720)
        self.assertIn('Referer', result['http_headers'])

    def test_redirect_to_another_video_never_becomes_a_success(self):
        with self.assertRaises(ExtractorError):
            self.extractor('', redirected=self.URL.replace('phfixture123', 'other'))._real_extract(self.URL)

    def test_removed_and_region_restricted_pages_fail(self):
        for page in ('<section class="noVideo">Fixture removed</section>', '<div class="geoBlocked"></div>'):
            with self.assertRaises(ExtractorError):
                self.extractor(page)._real_extract(self.URL)


if __name__ == '__main__':
    unittest.main()
