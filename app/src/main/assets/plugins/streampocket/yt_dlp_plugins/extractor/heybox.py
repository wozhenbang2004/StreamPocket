# SPDX-License-Identifier: GPL-3.0-or-later
# Signing functions adapted from nonebot-plugin-parser-lite (MIT),
# a6a68c4db0f0806a1a61f19e2b0474851b365675 / parsers/heybox/encrypt.py.
# Copyright (c) 2025 molanp; Copyright (c) 2024 Les Freire.
# Original reverse-engineering credit: 焦化葱葱 (negichan).
# The complete upstream MIT notice is in heybox-signing.LICENSE.txt.
# This adapter reads a selected official post; it never calls a parsing service
# or fabricates device verification tokens.

# 本脚本由 焦化葱葱(negichan) 提供
# 感谢 焦化葱葱(negichan) 的逆向工程
# 感谢群友们的积极测试和为逆向付出的努力
# 严禁非法滥用或用于渗透测试


import hashlib
import itertools
import time as _time
from typing import Final

BASE_URL: Final[str] = "api.xiaoheihe.cn"
PATH: Final[str] = "/bbs/app/link/tree"


def get_nonce(time: int) -> str:
    t = str(time).encode("utf-8")
    return hashlib.md5(t).hexdigest().upper()


def _vm(e: int) -> int:
    """等价 JS Vm"""
    return ((e << 1) & 0xFF) ^ 27 if (e & 0x80) else ((e << 1) & 0xFF)


def _qm(e: int) -> int:
    """等价 JS qm"""
    return _vm(e) ^ e


def _mm(e: int) -> int:
    """等价 JS $m"""
    return _qm(_vm(e))


def _ym(e: int) -> int:
    """等价 JS Ym"""
    return _mm(_qm(_vm(e)))


def _gm(e: int) -> int:
    """等价 JS Gm"""
    return _ym(e) ^ _mm(e) ^ _qm(e)


def _km(e: list[int]) -> list[int]:
    """等价 JS Km"""
    t0 = _gm(e[0]) ^ _ym(e[1]) ^ _mm(e[2]) ^ _qm(e[3])
    t1 = _qm(e[0]) ^ _gm(e[1]) ^ _ym(e[2]) ^ _mm(e[3])
    t2 = _mm(e[0]) ^ _qm(e[1]) ^ _gm(e[2]) ^ _ym(e[3])
    t3 = _ym(e[0]) ^ _mm(e[1]) ^ _qm(e[2]) ^ _gm(e[3])
    e[0], e[1], e[2], e[3] = t0, t1, t2, t3
    return e


def _av(e: str, t: str, n: int) -> str:
    """等价 JS av(e, t, n)"""
    # JS: var i = t.slice(0, n);
    i = t[:n]
    if not i:
        return ""
    res_chars: list[str] = []
    for ch in e:
        idx = ord(ch) % len(i)
        res_chars.append(i[idx])
    return "".join(res_chars)


def _sv(e: str, t: str) -> str:
    """等价 JS sv(e, t)"""
    if not t:
        return ""
    res_chars: list[str] = [t[ord(ch) % len(t)] for ch in e]
    return "".join(res_chars)


def _interleave_js(arr: list[str]) -> str:
    """
    等价 JS 中 iv + 匿名函数那段：
      var maxLength = Math.max.apply(Math, iv(e.map(elem => elem.length)));
      for i in [0..maxLength):
        e.forEach(elem => { if (i < elem.length) result += elem[i]; })
    """
    if not arr:
        return ""
    max_len = max(len(s) for s in arr)
    out: list[str] = [
        s[i] for i, s in itertools.product(range(max_len), arr) if i < len(s)
    ]
    return "".join(out)


def get_hkey(time: int) -> str:
    """
    精确还原 hkey&nonce.js 的 getHkey 内部逻辑
    注意：函数签名里 (e, t, n) 在脚本里被立即覆盖，不真正使用入参
    """
    e = PATH  # e = path;
    t = time + 1  # t = time + 1;
    n = get_nonce(time)  # n = getNonce();

    # e = "/".concat( e.split("/").filter(Boolean).join("/"), "/");
    parts = [seg for seg in e.split("/") if seg]
    e_norm = "/" + "/".join(parts) + "/"

    r = "AB45STUVWZEFGJ6CH01D237IXYPQRKLMN89"

    # i = interleave([ av(String(t), r, -2), sv(e, r), sv(n, r) ]).slice(0, 20);
    i_str = _interleave_js(
        [
            _av(str(t), r, -2),
            _sv(e_norm, r),
            _sv(n, r),
        ]
    )[:20]

    # CryptoJS.MD5(i).toString() -> 默认 hex 小写
    o = hashlib.md5(i_str.encode("utf-8")).hexdigest()

    # 取最后 6 位，转 charCodeAt 数组
    last6 = o[-6:]
    arr = [ord(ch) for ch in last6]

    # Km(arr).reduce((e, t) => e + t, 0) % 100
    mixed = _km(arr)
    total = sum(mixed)
    a_val = total % 100
    a = f"{a_val:02d}"  # 前缀补零，长度不足 2 补到 2

    # s = av(o.substring(0, 5), r, -4)
    s = _av(o[:5], r, -4)

    return f"{s}{a}"


def build_url(link_id: str) -> str:
    """构造等价的请求 URL"""
    time = int(_time.time())
    return (
        f"https://{BASE_URL}{PATH}"
        "?os_type=web&app=heybox&client_type=web&version=999.0.4"
        f"&_time={time}&nonce={get_nonce(time)}&hkey={get_hkey(time)}&link_id={link_id}"
        "&page=1&index=1&limit=5&x_client_type=weboutapp&x_app=heybox_website&x_os_type=Windows"
        "&web_version=2.5"
    )


import re
from urllib.parse import unquote, urlsplit

from yt_dlp.extractor.common import InfoExtractor
from yt_dlp.utils import ExtractorError, determine_ext, int_or_none, url_or_none


class StreamPocketHeyboxIE(InfoExtractor):
    IE_NAME = 'heybox:streampocket'
    IE_DESC = '小黑盒 · 试用'
    _VALID_URL = r'https?://(?:(?:www|api|m)\.)?xiaoheihe\.cn/(?P<id>[^#]+)'

    @staticmethod
    def post_id(url):
        try:
            parsed = urlsplit(url)
            if parsed.scheme not in ('http', 'https') or parsed.username or parsed.password:
                return None
            host = parsed.hostname or ''
            if host != 'xiaoheihe.cn' and not host.endswith('.xiaoheihe.cn'):
                return None
            path = unquote(parsed.path)
            match = re.fullmatch(r'/(?:app/bbs/link|a)/([A-Za-z0-9]{1,64})/?', path)
            post_id = match.group(1) if match else None
            seen = False
            for part in parsed.query.split('&'):
                pair = part.split('=', 1)
                if unquote(pair[0]).lower() != 'link_id':
                    continue
                if seen or len(pair) != 2 or pair[0] != 'link_id':
                    return None
                seen = True
                query_id = unquote(pair[1])
                if post_id and query_id != post_id:
                    return None
                post_id = query_id
            if not match and not re.fullmatch(r'/(?:bbs/post_share|(?:v3/)?bbs/app/(?:api/web/share|link/tree))/?', path):
                return None
            return post_id if re.fullmatch(r'[A-Za-z0-9]{1,64}', post_id or '') else None
        except ValueError:
            return None

    @classmethod
    def extract_post(cls, response, requested_id):
        if not isinstance(response, dict):
            raise ExtractorError('小黑盒返回了无效的帖子数据，请稍后重试', expected=True)
        status = response.get('status')
        if status != 'ok':
            if status in ('show_captcha', 'need_login', 'login_required', 'not_login'):
                raise ExtractorError('小黑盒要求登录或网页验证，请在账号管理中打开官方登录页完成后重试', expected=True)
            raise ExtractorError('小黑盒未允许读取此帖子，可能已删除、需要账号权限或暂时不可用', expected=True)
        result = response.get('result')
        link = result.get('link') if isinstance(result, dict) else None
        if not isinstance(link, dict):
            raise ExtractorError('小黑盒未返回所选帖子，请重新复制单个视频帖的链接', expected=True)
        post_id = str(link.get('linkid') or '')
        # Share ids are opaque aliases of the numeric linkid. Verify the official
        # share_url belongs to the requested alias; never traverse recommendations.
        if not post_id.isdigit() or (post_id != requested_id and cls.post_id(link.get('share_url') or '') != requested_id):
            raise ExtractorError('小黑盒返回的帖子与链接不一致，请重新复制原帖链接', expected=True)
        video_url = url_or_none(link.get('video_url'))
        if not int_or_none(link.get('has_video')) or not video_url or urlsplit(video_url).scheme not in ('https', 'http'):
            raise ExtractorError('小黑盒此帖没有可下载的视频，图文、评论和直播暂不支持', expected=True)
        user = link.get('user') if isinstance(link.get('user'), dict) else {}
        return {
            'id': post_id, 'title': link.get('title') or f'小黑盒视频 {post_id}',
            'description': link.get('description'), 'uploader': user.get('username'),
            'uploader_id': str(user.get('userid') or '') or None,
            'timestamp': int_or_none(link.get('create_at')),
            'thumbnail': url_or_none(link.get('video_thumb')),
            'duration': int_or_none(link.get('video_duration')),
            'width': int_or_none(link.get('video_width')),
            'height': int_or_none(link.get('video_height')),
            'webpage_url': f'https://www.xiaoheihe.cn/app/bbs/link/{post_id}',
            'http_headers': {'Referer': 'https://www.xiaoheihe.cn/', 'Origin': 'https://www.xiaoheihe.cn'},
            'url': video_url,
        }

    def _real_extract(self, url):
        post_id = self.post_id(url)
        if not post_id:
            raise ExtractorError('请粘贴小黑盒单个视频帖的分享链接，暂不处理首页、游戏页或整批帖子', expected=True)
        response = self._download_json(build_url(post_id), post_id, headers={
            'Referer': 'https://www.xiaoheihe.cn/', 'Origin': 'https://www.xiaoheihe.cn',
            'Accept': 'application/json, text/plain, */*',
        })
        info = self.extract_post(response, post_id)
        video_url = info.pop('url')
        if determine_ext(video_url) == 'm3u8':
            info['formats'] = self._extract_m3u8_formats(video_url, info['id'], 'mp4',
                entry_protocol='m3u8_native', m3u8_id='hls', headers=info['http_headers'])
        else:
            info['formats'] = [{
                'format_id': 'source', 'url': video_url, 'ext': determine_ext(video_url, 'mp4'),
                'width': info['width'], 'height': info['height'],
            }]
        return info
