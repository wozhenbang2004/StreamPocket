"""Bootstrap Bilibili byte ranges through standard aria2 Metalink metadata.

The CDN compatibility GET keeps Range: bytes=0-.  The actual aria2 requests
must generate their own disjoint ranges: a static Range header overrides them.
Uses yt-dlp's public postprocessor, networking and progress-hook APIs only.
"""
import atexit
import os
from pathlib import Path
import re
import shutil
import tempfile
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET

from yt_dlp.networking import Request
from yt_dlp.postprocessor.common import PostProcessor
from yt_dlp.utils import PostProcessingError, prepend_extension


def _bilibili_source(information):
    try:
        host = (urlsplit(information.get('webpage_url') or information.get('original_url') or '').hostname or '').lower()
        return host == 'bilibili.com' or host.endswith('.bilibili.com') or host == 'b23.tv'
    except ValueError:
        return False


def _download_names(downloader, information, formats):
    # Matches the pinned yt-dlp multi-format filename convention; the public
    # prepare_filename API owns templates, paths, Unicode and sanitisation.
    original = downloader.prepare_filename(information, 'temp')
    if not original or original == '-':
        raise PostProcessingError('B站范围下载需要本机文件路径。')
    extension = information.get('ext')
    names = []
    for selected in formats:
        name = original
        if information.get('requested_formats'):
            actual = selected['ext']
            previous = os.path.splitext(name)[1][1:]
            name = (os.path.splitext(name)[0] if previous in (extension, actual) else name) + '.' + actual
            name = prepend_extension(name, 'f' + selected['format_id'], actual)
        names.append(name if downloader.params.get('nopart') else name + '.part')
    return names


class StreamPocketBiliRangesPP(PostProcessor):
    def __init__(self, downloader=None, metadata_root=None):
        super().__init__(downloader)
        self._metadata_root = Path(metadata_root) if metadata_root else None

    def _native(self, information, marker, names):
        # Never feed an aria2 sparse file to a sequential downloader. A marker
        # created before the first native byte makes future retries consistent.
        if not marker.exists() and any(Path(name).exists() or Path(name + '.aria2').exists() for name in names):
            raise PostProcessingError('B站并行断点已保留；源站暂不支持当前范围请求，请切换标准模式后重试。')
        try:
            marker.parent.mkdir(parents=True, exist_ok=True)
            marker.write_text('native-range-v1\n', encoding='ascii')
        except OSError as error:
            raise PostProcessingError('无法保存兼容续传状态，请检查手机存储空间。') from error
        chosen = dict(self._downloader.params.get('external_downloader') or {})
        chosen['default'] = 'native'
        self._downloader.params['external_downloader'] = chosen
        self._downloader.to_stdout('SP_TRANSFER_MODE:native-http')
        self.to_screen('B站使用兼容范围下载，已有兼容断点会继续保留。')
        return [], information

    def run(self, information):
        if not _bilibili_source(information):
            return [], information
        formats = information.get('requested_formats') or [information]
        if not formats or not all(selected.get('protocol') in ('http', 'https') for selected in formats):
            return [], information
        names = _download_names(self._downloader, information, formats)
        marker = Path(names[0]).parent / '.streampocket-bili-native'
        if marker.exists():
            return self._native(information, marker, names)
        private = None
        prepared = []
        try:
            for selected in formats:
                source = selected.get('url', '')
                if urlsplit(source).scheme not in ('http', 'https'):
                    raise ValueError('Non-HTTP media')
                headers = dict(selected.get('http_headers') or information.get('http_headers') or {})
                headers = {key: value for key, value in headers.items() if key.lower() not in ('range', 'accept-encoding')}
                headers.update({'Range': 'bytes=0-', 'Accept-Encoding': 'identity'})
                # Headers only. urlopen retains yt-dlp's account jar, proxy,
                # certificate trust and bounded socket timeout.
                with self._downloader.urlopen(Request(source, headers=headers)) as response:
                    match = re.fullmatch(r'bytes 0-(\d+)/(\d+)', response.headers.get('Content-Range', ''))
                    if response.status != 206 or not match:
                        raise ValueError('No exact full range')
                    size = int(match[2])
                    if size <= 0 or size > (1 << 63) - 1 or int(match[1]) + 1 != size:
                        raise ValueError('Invalid full range')
                    length = response.headers.get('Content-Length')
                    if length is not None and int(length) != size:
                        raise ValueError('Inconsistent response length')
                    if response.headers.get('Content-Encoding', 'identity').lower() not in ('', 'identity'):
                        raise ValueError('Encoded byte ranges')
                prepared.append((selected, source, size, {key: value for key, value in headers.items() if key.lower() != 'range'}))
            # Android supplies an app-private per-request cache directory. The
            # service removes it even when SIGTERM bypasses Python's atexit.
            # A standalone CLI keeps metadata beneath its own task directory.
            base = self._metadata_root or Path(names[0]).parent
            base.mkdir(parents=True, exist_ok=True)
            private = Path(tempfile.mkdtemp(prefix='.streampocket-bili-meta-', dir=str(base)))
            metadata = []
            for index, (_, source, size, _) in enumerate(prepared):
                root = ET.Element('metalink', xmlns='urn:ietf:params:xml:ns:metalink')
                node = ET.SubElement(root, 'file', name=Path(names[index]).name)
                ET.SubElement(node, 'size').text = str(size)
                ET.SubElement(node, 'url').text = source
                target = private / f'selected-{index}.meta4'
                ET.ElementTree(root).write(target, encoding='utf-8', xml_declaration=True)
                target.chmod(0o600)
                metadata.append(str(target))
        except Exception:
            if private is not None:
                shutil.rmtree(private, ignore_errors=True)
            return self._native(information, marker, names)
        # Commit changes only after every selected track has a validated size.
        for selected, _, size, headers in prepared:
            selected['http_headers'] = headers
            selected['filesize'] = size
        information['http_headers'] = {key: value for key, value in (information.get('http_headers') or {}).items() if key.lower() != 'range'}
        arguments = self._downloader.params.setdefault('external_downloader_args', {})
        base = [argument for argument in (arguments.get('aria2c') or ())
                if not argument.startswith(('--metalink-file=', '--http-accept-gzip='))]
        base.append('--http-accept-gzip=false')
        arguments['aria2c'] = base + ['--metalink-file=' + metadata[0]]
        self._next = 0
        identifiers = [str(selected.get('format_id', '')) for selected in formats]

        def advance(progress):
            if progress.get('status') != 'finished' or self._next >= len(metadata):
                return
            identifier = str((progress.get('info_dict') or {}).get('format_id', ''))
            if identifier != identifiers[self._next]:
                return
            self._next += 1
            if self._next < len(metadata):
                arguments['aria2c'] = base + ['--metalink-file=' + metadata[self._next]]

        self._downloader.add_progress_hook(advance)
        atexit.register(shutil.rmtree, private, ignore_errors=True)
        self._downloader.to_stdout('SP_TRANSFER_MODE:parallel-http')
        self.to_screen('B站范围信息已确认，使用并行下载。')
        return [], information
