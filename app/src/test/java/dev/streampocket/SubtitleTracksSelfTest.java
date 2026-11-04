package dev.streampocket;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** SDK-free checks for sidecar classification; also invoked by the JUnit suite. */
public final class SubtitleTracksSelfTest {
    public static void main(String[] args) {
        metadata(); uris(); headers(); failures();
        System.out.println("PASS: subtitle types/languages, local URI boundaries, format headers, exact failure attribution");
    }
    static void metadata() {
        equal("application/x-subrip",SubtitleTracks.mimeType("片段.zh-Hans.SRT","application/octet-stream"));
        equal("text/vtt",SubtitleTracks.mimeType("clip.en.vtt","text/plain"));
        equal("text/x-ssa",SubtitleTracks.mimeType("clip.ass","application/octet-stream"));
        equal("text/x-ssa",SubtitleTracks.mimeType("clip.ssa",""));
        equal("application/ttml+xml",SubtitleTracks.mimeType("clip.dfxp","application/xml"));
        equal("application/ttml+xml",SubtitleTracks.mimeType("clip.ttml","text/plain"));
        equal("application/x-subrip",SubtitleTracks.mimeType("","application/x-subrip; charset=utf-8"));
        for(String extension:new String[]{"jpg","png","lrc","html","mp4","json"})
            equal("",SubtitleTracks.mimeType("clip."+extension,"text/vtt"));
        equal("",SubtitleTracks.mimeType("","text/plain"));
        equal("zh-Hans",SubtitleTracks.language("","标题 [123].zh-Hans.srt","en"));
        equal("en",SubtitleTracks.language("","Title [id].en-orig.vtt","zh"));
        equal("pt-BR",SubtitleTracks.language("pt_BR","Title.en.srt","zh"));
        equal("zh-Hant",SubtitleTracks.language("","renamed.srt","zh-Hant"));
        equal("",SubtitleTracks.language("","episode.fun.srt","zh.*,en"));
        equal("",SubtitleTracks.languageTag("auto"));equal("",SubtitleTracks.languageTag("und"));
        equal("中文译文",SubtitleTracks.label("中文译文","en","clip.en.srt"));
        equal("clip.srt",SubtitleTracks.label("","","clip.srt"));
        check(!SubtitleTracks.label("","zh-Hans","").isEmpty(),"Language label missing");
    }
    static void uris() {
        check(SubtitleTracks.localUri("content://com.android.externalstorage.documents/document/primary%3AMovies%2Fclip.srt"),"SAF URI rejected");
        check(SubtitleTracks.localUri("content://media/external/file/42"),"MediaStore URI rejected");
        check(SubtitleTracks.localUri("file:///storage/emulated/0/Movies/my%20clip.srt"),"Local file rejected");
        for(String uri:new String[]{"https://example.com/a.srt","data:text/vtt,WEBVTT","file://remote/path.srt","file:relative.srt","content:///missing-authority","content://user@provider/a","content://provider/a#fragment","","/tmp/a.srt"})
            check(!SubtitleTracks.localUri(uri),"Unexpected subtitle URI accepted: "+uri);
    }
    static void headers() {
        check(valid("application/x-subrip","1\n00:00:01,000 --> 00:00:02,500\n字幕\n"),"SRT rejected");
        check(valid("text/vtt","\uFEFFWEBVTT\n\n00:01.000 --> 00:02.000\n字幕"),"VTT BOM rejected");
        check(valid("text/x-ssa","[Script Info]\nTitle: sample\n[Events]"),"ASS rejected");
        check(valid("application/ttml+xml","<?xml version=\"1.0\"?><tt xmlns=\"http://www.w3.org/ns/ttml\"><body/></tt>"),"TTML rejected");
        check(valid("application/ttml+xml","<tt:tt xmlns:tt=\"http://www.w3.org/ns/ttml\"><tt:body/></tt:tt>"),"Namespaced TTML rejected");
        byte[] utf16="\uFEFF1\n00:00:01,000 --> 00:00:02,000\n你好\n".getBytes(StandardCharsets.UTF_16LE);
        check(SubtitleTracks.validHeader("application/x-subrip",utf16,utf16.length),"UTF-16 SRT rejected");
        check(!valid("application/x-subrip","<!doctype html><html>sign in</html>"),"HTML response accepted as subtitles");
        check(!valid("text/vtt","1\n00:00:01,000 --> 00:00:02,000\nwrong format"),"Mismatched subtitle format accepted");
        check(!valid("text/vtt","WEBVTTfake"),"Invalid WebVTT signature accepted");
        check(!valid("application/x-subrip","not a subtitle"),"Invalid text accepted");
        check(!valid("text/vtt",""),"Empty file accepted");
    }
    static void failures() {
        IOException subtitle=new IOException("permission denied"),video=new IOException("permission denied");
        check(SubtitleTracks.causedBy(new RuntimeException(new IOException("load",subtitle)),subtitle),"Nested subtitle source error missed");
        check(!SubtitleTracks.causedBy(new RuntimeException(video),subtitle),"Main video error hidden by a similar subtitle error");
        check(!SubtitleTracks.causedBy(new RuntimeException(subtitle),null),"Unattributed failure treated as optional");
    }
    private static boolean valid(String mime,String text) { byte[] bytes=text.getBytes(StandardCharsets.UTF_8);return SubtitleTracks.validHeader(mime,bytes,bytes.length); }
    private static void equal(String expected,String actual) { check(expected.equals(actual),"Expected "+expected+", got "+actual); }
    private static void check(boolean result,String message) { if(!result)throw new AssertionError(message); }
}
