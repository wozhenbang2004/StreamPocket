# SPDX-License-Identifier: GPL-3.0-or-later
"""Selected-post image adapters using the bundled yt-dlp official extractors.

Schema: yt-dlp 2026.10.26 (Unlicense), extractor/xiaohongshu.py and tiktok.py.
No remote parser, fabricated verification token, or recommended-post fallback.
"""
from urllib.parse import urlsplit

from yt_dlp.extractor.xiaohongshu import XiaoHongShuIE as BaseXHS
from yt_dlp.utils import ExtractorError, int_or_none, float_or_none, js_to_json, url_or_none
from yt_dlp.utils.traversal import traverse_obj


def album_result(post_id, title, images, referer, uploader=None):
    if not images or len(images) > 100:
        raise ExtractorError('图集没有有效图片或超过单帖 100 张上限', expected=True)
    entries = []
    for index, image in enumerate(images, 1):
        if not isinstance(image, str) or not url_or_none(image) or urlsplit(image).scheme not in ('http', 'https'):
            raise ExtractorError('图集含无效图片地址，未保存不完整图集', expected=True)
        ext = 'webp' if 'webp' in image.lower() else 'png' if '.png' in image.lower() else 'jpg'
        entries.append({'id': f'{post_id}-{index:03d}', 'title': f'{post_id}-{index:03d}',
                        'url': image, 'ext': ext, 'http_headers': {'Referer': referer}})
    return {'_type': 'playlist', '_streampocket_album': True, '_streampocket_image_count': len(entries), 'id': post_id,
            'title': title or f'图集 {post_id}', 'uploader': uploader,
            'thumbnail': images[0], 'entries': entries, 'webpage_url': referer}


class XiaoHongShuIE(BaseXHS, plugin_name='streampocket'):
    @staticmethod
    def selected_note(state, post_id):
        notes = traverse_obj(state, ('note', 'noteDetailMap', {dict})) or {}
        item = notes.get(post_id)
        note = item.get('note') if isinstance(item, dict) else None
        if not isinstance(note, dict) or str(note.get('noteId') or post_id) != post_id:
            raise ExtractorError('小红书未返回所选笔记，请使用当前有效的分享链接或在官方页面确认访问权限', expected=True)
        return note

    def _real_extract(self, url):
        post_id = self._match_id(url)
        webpage = self._download_webpage(url, post_id)
        state = self._search_json(r'window\.__INITIAL_STATE__\s*=', webpage, '笔记数据', post_id,
                                  transform_source=js_to_json)
        note = self.selected_note(state, post_id)
        if note.get('type') == 'normal':
            images = []
            for image in note.get('imageList') or []:
                if not isinstance(image, dict):
                    raise ExtractorError('图集图片数据不完整', expected=True)
                image_url = image.get('urlDefault') or image.get('urlPre')
                if not image_url:
                    image_url = next((x.get('url') for x in image.get('infoList') or []
                                      if isinstance(x, dict) and x.get('url')), None)
                images.append(image_url)
            return album_result(post_id, note.get('title'), images, url,
                                traverse_obj(note, ('user', 'nickname', {str})))
        formats = []
        for stream in traverse_obj(note, ('video', 'media', 'stream', ..., {list}, ...)) or []:
            if not isinstance(stream, dict):
                continue
            for media_url in [stream.get('masterUrl'), *(stream.get('backupUrls') or [])]:
                if url_or_none(media_url):
                    formats.append({'format_id': str(len(formats)), 'url': media_url, 'ext': 'mp4',
                                    'width': int_or_none(stream.get('width')), 'height': int_or_none(stream.get('height')),
                                    'vcodec': stream.get('videoCodec'), 'acodec': stream.get('audioCodec'),
                                    'filesize': int_or_none(stream.get('size'))})
        if not formats:
            origin = traverse_obj(note, ('video', 'consumer', 'originVideoKey', {str}))
            if origin and len(origin) <= 2048:
                handle = self._request_webpage(f'https://sns-video-bd.xhscdn.com/{origin}', post_id,
                                               'Checking original video availability', fatal=False)
                if handle:
                    formats.append({'format_id': 'direct', 'url': handle.url, 'ext': 'mp4',
                                    'filesize': int_or_none(handle.get_header('Content-Length'))})
        if not formats:
            raise ExtractorError('所选小红书笔记没有返回视频格式，请更新分享链接并确认源站可播放', expected=True)
        return {'id': post_id, 'title': note.get('title') or f'小红书 {post_id}', 'formats': formats,
                'duration': max((float_or_none(x.get('duration'), scale=1000) or 0
                                 for x in traverse_obj(note, ('video', 'media', 'stream', ..., {list}, ...)) or []), default=0),
                'thumbnail': traverse_obj(note, ('imageList', 0, 'urlDefault', {url_or_none})),
                'uploader': traverse_obj(note, ('user', 'nickname', {str})),
                'webpage_url': url, 'http_headers': {'Referer': 'https://www.xiaohongshu.com/'}}
