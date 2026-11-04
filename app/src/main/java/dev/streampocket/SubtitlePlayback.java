package dev.streampocket;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;

/** Opens only catalogue-owned subtitle URIs, on the player's existing I/O executor. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class SubtitlePlayback {
    static final class Result {
        final List<MediaItem.SubtitleConfiguration> tracks = new ArrayList<>();
        int skipped;
        String failure="";
        String warning() { return !failure.isEmpty()?failure:skipped==0 ? "" : skipped+" 份已下载字幕无法加载，已跳过。请检查字幕文件与目录访问权限。"; }
    }

    private SubtitlePlayback() {}

    static Result load(Context context,List<JSONObject> records) {
        Result result = new Result(); Set<String> seen = new HashSet<>();
        List<JSONObject> ordered=new ArrayList<>(records);
        // Stable ordering preserves legacy records; an unreadable preferred file cannot take the default flag.
        ordered.sort((left,right)->Boolean.compare(right.optBoolean("preferred"),left.optBoolean("preferred")));
        for(JSONObject record:ordered) {
            String value=record.optString("uri"),mime=SubtitleTracks.mimeType(record.optString("name"),record.optString("mime"));
            if(!seen.add(value)) continue;
            if(mime.isEmpty() || !SubtitleTracks.localUri(value)) { result.skipped++; continue; }
            Uri uri=Uri.parse(value);
            try(AssetFileDescriptor descriptor=context.getContentResolver().openAssetFileDescriptor(uri,"r")) {
                if(descriptor==null || descriptor.getLength()==0 || descriptor.getLength()>SubtitleTracks.MAX_BYTES) { result.skipped++; continue; }
                try(InputStream input=descriptor.createInputStream()) {
                    byte[] header=new byte[8192];int length=0,read;
                    while(length<header.length && (read=input.read(header,length,header.length-length))>0) length+=read;
                    if(!SubtitleTracks.validHeader(mime,header,length)) { result.skipped++; continue; }
                }
                String language=SubtitleTracks.languageTag(record.optString("language"));
                result.tracks.add(new MediaItem.SubtitleConfiguration.Builder(uri)
                        .setId("sp-subtitle-"+record.optLong("id"))
                        .setMimeType(mime).setLanguage(language.isEmpty()?null:language)
                        .setLabel(SubtitleTracks.label(record.optString("label"),language,record.optString("name")))
                        .setSelectionFlags(result.tracks.isEmpty()?C.SELECTION_FLAG_DEFAULT:0).build());
            } catch(Exception inaccessible) { result.skipped++; }
        }
        return result;
    }

    static MediaItem.SubtitleConfiguration forFormat(List<MediaItem.SubtitleConfiguration> tracks,String formatId) {
        if(formatId!=null)for(MediaItem.SubtitleConfiguration track:tracks)if(formatId.equals(track.id))return track;
        return null;
    }
}
