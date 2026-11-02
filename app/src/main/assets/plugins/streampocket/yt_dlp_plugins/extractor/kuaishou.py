# SPDX-License-Identifier: GPL-3.0-or-later
"""Kuaishou share-page adapter for the existing local yt-dlp pipeline.

Schema reference: OmniDownloader 5ff8ff48, DomesticDirectExtractor.kt (MIT).
Apollo graph selection was cross-checked against Janusilver's public kuaishou.py.
Only the requested photo is traversed; recommended videos are never a fallback.
"""
import json
import re
from urllib.parse import parse_qs, urlsplit

from yt_dlp.extractor.common import InfoExtractor
from yt_dlp.utils import ExtractorError, float_or_none, int_or_none, js_to_json, url_or_none


class StreamPocketKuaishouIE(InfoExtractor):
    IE_NAME = 'kuaishou:streampocket'
    IE_DESC = '快手 · 试用'
    _VALID_URL = r'https?://(?:(?:www|m|v)\.)?(?:kuaishou\.(?:com|cn)|gifshow\.com|chenzhongtech\.com)/(?P<id>[^?#]+)'

    @staticmethod
    def photo_id(url):
        parsed = urlsplit(url)
        match = re.search(r'/(?:short-video|fw/photo|photo)/([a-zA-Z0-9]+)(?:/|$)', parsed.path)
        if match:
            return match.group(1)
        query = parse_qs(parsed.query)
        value = (query.get('photoId') or query.get('photo_id') or [''])[0]
        return value if re.fullmatch(r'[a-zA-Z0-9]+', value or '') else None

    @staticmethod
    def extract_photo(state, photo_id):
        graph = state.get('defaultClient') or {}
        if not isinstance(graph, dict):
            raise ExtractorError('快手页面未提供作品数据，请重新解析分享链接', expected=True)
        photo = graph.get(f'VisionVideoDetailPhoto:{photo_id}')
        if not isinstance(photo, dict):
            raise ExtractorError('快手未返回所选作品，可能需要登录、验证或作品已不可用', expected=True)

        def resolve(value):
            seen = set()
            for _ in range(12):
                if isinstance(value, str):
                    try:
                        value = json.loads(value)
                    except (ValueError, TypeError):
                        return value
                if not isinstance(value, dict):
                    return value
                key = value.get('__ref') or (value.get('id') if value.get('type') == 'id' else None)
                if key:
                    if key in seen:
                        return None
                    seen.add(key)
                    value = graph.get(key)
                elif 'json' in value:
                    value = value['json']
                else:
                    return value
            return None

        def array(value):
            value = resolve(value)
            return value if isinstance(value, list) else []

        formats, seen = [], set()

        def add(value, code, metadata=None):
            metadata = metadata or {}
            for url in array(value) or [resolve(value)]:
                if not isinstance(url, str) or not url_or_none(url) or urlsplit(url).scheme not in ('https', 'http') or url in seen:
                    continue
                seen.add(url)
                formats.append({
                    'format_id': f'{code}-{len(formats)}', 'url': url, 'ext': 'mp4',
                    'width': int_or_none(metadata.get('width')),
                    'height': int_or_none(metadata.get('height')),
                    'fps': float_or_none(metadata.get('frameRate') or metadata.get('fps')),
                    'tbr': float_or_none(metadata.get('avgBitrate'), scale=1000),
                })

        for name in ('manifest', 'manifestH265'):
            manifest = resolve(photo.get(name))
            if not isinstance(manifest, dict):
                continue
            for item in array(manifest.get('adaptationSet')):
                item = resolve(item)
                if not isinstance(item, dict):
                    continue
                for representation in array(item.get('representation')):
                    representation = resolve(representation)
                    if isinstance(representation, dict):
                        for key in ('url', 'backupUrl'):
                            add(representation.get(key), name, representation)
        for key in ('photoUrl', 'photoH265Url'):
            add(photo.get(key), key)
        if not formats:
            raise ExtractorError('快手所选作品没有可下载的视频，图文作品暂不支持', expected=True)
        author = resolve(photo.get('author'))
        return {
            'id': photo_id, 'title': photo.get('caption') or f'快手视频 {photo_id}',
            'thumbnail': url_or_none(photo.get('coverUrl') or photo.get('poster')),
            'uploader': author.get('name') if isinstance(author, dict) else None,
            'duration': float_or_none(photo.get('duration'), scale=1000),
            'formats': formats, 'http_headers': {'Referer': 'https://www.kuaishou.com/'},
        }

    def _real_extract(self, url):
        display_id = self._match_id(url)
        webpage, handle = self._download_webpage_handle(url, display_id)
        photo_id = self.photo_id(handle.url) or self.photo_id(url)
        if not photo_id:
            raise ExtractorError('未识别到快手作品，请复制单个视频的分享链接', expected=True)
        state = self._search_json(r'window\.__APOLLO_STATE__\s*=', webpage, '作品数据', photo_id,
                                  transform_source=js_to_json, default={})
        canonical = f'https://www.kuaishou.com/short-video/{photo_id}'
        resolved = urlsplit(handle.url)
        if not state and (resolved.hostname != 'www.kuaishou.com' or resolved.path.rstrip('/') != f'/short-video/{photo_id}'):
            # Mobile share pages expose the photo id but usually not the desktop graph.
            # Fetch the official work page once; a challenge on that page is terminal.
            webpage, handle = self._download_webpage_handle(canonical, photo_id)
            state = self._search_json(r'window\.__APOLLO_STATE__\s*=', webpage, '作品数据', photo_id,
                                      transform_source=js_to_json, default={})
        if not state:
            self.raise_login_required('快手页面未提供视频数据，请在账号管理中登录并完成源站验证后重试', method='cookies')
        return {**self.extract_photo(state, photo_id), 'webpage_url': handle.url}
