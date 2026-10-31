"""Keep an optional aria2 transfer tied to the actual yt-dlp process lifetime.

Own implementation using yt-dlp's public postprocessor API. No network requests,
account access, or downloader source replacement are performed by this plugin.
"""

import os
import signal

from yt_dlp.postprocessor.common import PostProcessor
from yt_dlp.utils import PostProcessingError


def _terminate_transfer_group(signum, frame):
    # The group was isolated and verified before any external download began.
    # Reset first, so forwarding SIGTERM terminates this Python process too.
    signal.signal(signal.SIGTERM, signal.SIG_DFL)
    os.killpg(os.getpid(), signal.SIGTERM)


def _install_cancel_guard():
    try:
        if os.getpgrp() != os.getpid():
            os.setpgrp()
        if os.getpgrp() != os.getpid():
            raise OSError('Transfer group was not isolated')
        signal.signal(signal.SIGTERM, _terminate_transfer_group)
    except (OSError, ValueError, AttributeError) as error:
        # Never start an accelerated child without reliable cancellation.
        raise PostProcessingError('无法建立并行下载的取消保护，请在下载与速度中切换标准模式后重试。') from error


class StreamPocketTransferPP(PostProcessor):
    def run(self, information):
        _install_cancel_guard()
        arguments = self._downloader.params.get('external_downloader_args') or {}
        self._downloader.params['external_downloader_args'] = arguments
        current = list(arguments.get('aria2c') or ())
        current = [argument for argument in current if not argument.startswith('--stop-with-process=')]
        current.append(f'--stop-with-process={os.getpid()}')
        arguments['aria2c'] = current
        return [], information
