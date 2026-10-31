package dev.streampocket;

import android.net.Uri;
import androidx.media3.common.MediaItem;

/** One original-time range contract for playback previews, drafts and export. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class ClipContract {
    private ClipContract() {}
    static void validate(long startMs,long endMs,long durationMs) {
        if(startMs<0 || endMs<=startMs) throw new IllegalArgumentException("终点必须晚于起点。");
        if(durationMs<=0 || startMs>=durationMs || endMs>durationMs)
            throw new IllegalArgumentException("起止时间不能超过原片时长。");
    }
    static MediaItem mediaItem(Uri uri,long startMs,long endMs) {
        if(startMs<0 || endMs<=startMs) throw new IllegalArgumentException("片段起止时间无效");
        return new MediaItem.Builder().setUri(uri)
                .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs).setEndPositionMs(endMs)
                        .setStartsAtKeyFrame(false).build()).build();
    }
    static long sourcePosition(long relativeMs,long clipStartMs,long clipEndMs) {
        return clipStartMs+Math.max(0,Math.min(relativeMs,clipEndMs-clipStartMs));
    }
}
