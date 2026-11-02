# SPDX-License-Identifier: Unlicense
# Experimental compatibility plugin adapted from yt-dlp PR #17826 by viking1304.
# Source: https://github.com/yt-dlp/yt-dlp/pull/17826
# Pinned commit: 728fe46fbaa97d6f03efcaf9e766d4db63338b8d
# Upstream file: yt_dlp/extractor/tiktok.py
# Upstream file blob: dba66e736334dccf875719ac0ad9d7ee5715b51d
# The PR author releases this contribution under the Unlicense. See
# docs/DOUYIN_PATCH.md for provenance, integration, and validation boundaries.
"""Experimental Douyin override; no third-party parsing service is involved."""

import hashlib
import json
import time
import urllib.parse
import uuid

from yt_dlp.extractor.tiktok import DouyinIE
from yt_dlp.utils import ExtractorError
from yt_dlp.utils.traversal import traverse_obj


class _StreamPocketDouyinIE(DouyinIE, plugin_name='streampocket-experimental'):
    """Port of the pinned upstream patch, packaged as an extractor override."""

    _VALID_URL = r'https?://(?:www\.)?douyin\.com/(?:video|note)/(?P<id>[0-9]+)'

    def _parse_aweme_video_app(self, detail):
        images = detail.get('images')
        if not isinstance(images, list) or not images:
            return super()._parse_aweme_video_app(detail)
        from yt_dlp_plugins.extractor.albums import album_result
        from yt_dlp.utils import url_or_none
        post_id = str(detail.get('aweme_id') or '')
        urls = [traverse_obj(image, ('url_list', 0, {url_or_none})) for image in images]
        return album_result(post_id, detail.get('desc'), urls, f'https://www.douyin.com/note/{post_id}',
                            traverse_obj(detail, ('author', 'nickname', {str})))

    _SEC_SALT = 'A96D855A08C0A9707F8BEF0D9A527E4E'
    _REQUIRED_COOKIES = [
        'passport_csrf_token',  # pass anything just works (upstream comment)
        'ttwid',
    ]
    _AID = 6383  # aweme_general
    _CSRF = uuid.uuid4().hex

    def _get_ttwid(self):
        self._request_webpage(f'{self._WEBPAGE_HOST}ttwid/register', None, data=json.dumps({
            'aid': self._AID,
            'service': 'www.douyin.com',
        }).encode(), headers={
            'content-type': 'application/json',
        })
        cookie = self._get_cookies(self._WEBPAGE_HOST).get('ttwid')
        return cookie.value if cookie else None

    def check_and_get_required_cookies(self):
        for rc in self._REQUIRED_COOKIES:
            if self._get_cookies(self._WEBPAGE_HOST).get(rc):
                continue
            if rc == 'ttwid':
                cookie = self._get_ttwid()
            else:
                cookie = self._CSRF
            if not cookie:
                raise ExtractorError('Unable to generate required cookies')
            self._set_cookie('.douyin.com', rc, cookie)

    def _get_uifid(self):
        uifid_cookie_names = (
            'uifid',
            'uifid_temp',
            'uifidtemp',
            'UIFID',
            'UIFID_TEMP',
            'UIFIDTEMP',
        )
        uifid = traverse_obj(self._get_cookies(self._WEBPAGE_HOST), (uifid_cookie_names, 'value'), get_all=False)
        if not uifid:
            # Try x-tt-trace-host header (upstream fallback).
            urlh = self._request_webpage(self._WEBPAGE_HOST, None)
            return urlh.headers.get('x-tt-trace-host')
        return uifid

    def _sign_query(self, query):
        salt = self._SEC_SALT
        uifid = self._get_uifid()
        timestamp = str(int(time.time()))
        query = {**query, 'uifid': uifid, 'timestamp': timestamp}
        encoded_query = urllib.parse.urlencode(query)
        secsdk_signature = hashlib.md5(f'{uifid}_{timestamp}_{salt}_{encoded_query}'.encode()).hexdigest()
        return {**query, 'x-secsdk-web-signature': secsdk_signature}

    def _get_aweme_detail(self, video_id):
        return traverse_obj(self._download_json(
            'https://www.douyin.com/aweme/v1/web/aweme/detail/', video_id,
            'Downloading web detail JSON', 'Failed to download web detail JSON',
            query=self._sign_query({'aweme_id': video_id, 'aid': self._AID}), fatal=False), ('aweme_detail', {dict}))

    def _real_extract(self, url):
        video_id = self._match_id(url)

        self.check_and_get_required_cookies()
        aweme_detail = self._get_aweme_detail(video_id)
        if not aweme_detail:
            # Upstream still does not implement interactive verification.
            raise ExtractorError(
                'Fresh cookies (not necessarily logged in) are needed',
                expected=not self._get_cookies(self._WEBPAGE_HOST).get('s_v_web_id'))

        if str(aweme_detail.get('aweme_id')) != video_id:
            raise ExtractorError('抖音未返回所选作品，未使用其他推荐内容', expected=True)

        return {**self._parse_aweme_video_app(aweme_detail), 'http_headers': {'Referer': self._WEBPAGE_HOST}}
