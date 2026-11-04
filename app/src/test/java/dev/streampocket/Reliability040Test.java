package dev.streampocket;

import com.yausername.youtubedl_android.YoutubeDLRequest;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class Reliability040Test {
    @Test public void resolvedMediaExpiresAndIsBoundToSourceAndAccountRevision() throws Exception {
        String source="https://www.instagram.com/p/fixture/";
        JSONObject info=new JSONObject().put("id","fixture").put("url","https://cdn.example/file.mp4?signature=synthetic-secret");
        String id=ResolvedMediaCache.put(info,source,"account1",1000);
        assertEquals(info.toString(),ResolvedMediaCache.get(id,source,"account1",1001).toString());
        assertNull(ResolvedMediaCache.get(id,source,"account2",1002));
        id=ResolvedMediaCache.put(info,source,"account1",1000);
        assertNull(ResolvedMediaCache.get(id,source,"account1",301000));
        id=ResolvedMediaCache.put(info,source,"account1",1000);
        assertNull(ResolvedMediaCache.get(id,source+"other","account1",1001));
    }
    @Test public void serializedTaskRetainsOnlyOpaqueResolutionHandleAndContentChoices() throws Exception {
        Engine.Media media=new Engine.Media();media.url="https://www.instagram.com/p/fixture/";media.resolvedKey="opaque";
        media.saveCover=true;media.saveSubtitles=true;media.subtitleLanguage="zh-Hans";
        JSONObject saved=media.toJson();assertFalse(saved.has("formats"));assertFalse(saved.has("http_headers"));
        Engine.Media restored=Engine.Media.fromJson(saved);assertEquals("opaque",restored.resolvedKey);
        assertTrue(restored.saveCover);assertTrue(restored.saveSubtitles);assertEquals("zh-Hans",restored.subtitleLanguage);
    }
    @Test public void refreshRemovesOnlyTheCachedInfoOptionAndKeepsFormatAndAccountSnapshot() {
        YoutubeDLRequest original=new YoutubeDLRequest("https://example.org/video");
        original.addOption("--cookies","/private/cookies");original.addOption("--load-info-json","/private/info.json");original.addOption("--format","bv*+ba/b");
        List<String> args=Engine.withoutResolved(original).buildCommand();
        assertFalse(args.contains("--load-info-json"));assertFalse(args.contains("/private/info.json"));
        assertTrue(args.contains("/private/cookies"));assertTrue(args.contains("bv*+ba/b"));assertTrue(args.contains("https://example.org/video"));
        assertTrue(Engine.expiredMedia("ERROR: HTTP Error 403: Forbidden"));assertFalse(Engine.expiredMedia("HTTP Error 429: Too Many Requests"));
    }
    @Test public void collectionsRequireExplicitBoundedSupportedLinks() {
        assertEquals("https://www.youtube.com/playlist?list=PLabcdefghijk",CollectionLinks.normalize("https://www.youtube.com/watch?v=jNQXAC9IVRw&list=PLabcdefghijk"));
        assertEquals("https://www.bilibili.com/video/BV1nx411u79K",CollectionLinks.normalize("https://www.bilibili.com/video/BV1nx411u79K?p=2"));
        for(String url:new String[]{"https://www.youtube.com/watch?v=jNQXAC9IVRw","https://www.youtube.com/playlist?list=PLabcdefghijk&list=PLabcdefghijk","https://www.youtube.com.attacker.example/playlist?list=PLabcdefghijk","https://example.org/collection"}) {
            try{CollectionLinks.normalize(url);fail(url);}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void collectionPreviewSkipsLiveUnknownAndDuplicateItems() throws Exception {
        JSONObject one=new JSONObject().put("id","jNQXAC9IVRw").put("title","Offline video");
        JSONArray entries=new JSONArray().put(one).put(one).put(new JSONObject().put("id","BaW_jenozKc").put("is_live",true)).put(new JSONObject().put("url","https://example.org/not-a-source"));
        List<Engine.CollectionItem> items=Engine.collectionItems(new JSONObject().put("entries",entries),"https://www.youtube.com/playlist?list=PLabcdefghijk");
        assertEquals(1,items.size());assertEquals("https://www.youtube.com/watch?v=jNQXAC9IVRw",items.get(0).url);assertEquals(-1,items.get(0).bytes);
    }
    @Test public void imageAlbumIsOneTaskAndNeverOffersAudioExtraction() throws Exception {
        JSONObject raw=new JSONObject().put("id","fixture").put("title","Image fixture").put("_streampocket_album",true)
                .put("entries",new JSONArray().put(new JSONObject().put("id","fixture-1").put("url","https://cdn.example/1.jpg")));
        List<Engine.Media> items=Engine.mediaFromMetadata(raw,"https://www.xiaohongshu.com/explore/123abc");
        assertEquals(1,items.size());Engine.Media media=items.get(0);assertTrue(media.album);assertEquals(1,media.imageCount);
        assertEquals("images",media.downloadChoices().get(0).id);assertEquals(1,media.downloadChoices().size());
        assertTrue(Engine.Media.fromJson(media.toJson()).album);
    }
    @Test public void subtitlesPreferChineseThenEnglishAndNeverDownloadLiveChat() throws Exception {
        JSONObject available=new JSONObject("{\"subtitles\":{\"en\":[{\"url\":\"https://example.org/en.vtt\"}],\"zh-Hans\":[{\"url\":\"https://example.org/zh.vtt\"}]},\"automatic_captions\":{\"fr\":[{\"url\":\"https://example.org/fr.vtt\"}]}}");
        assertEquals("zh-Hans",Engine.preferredSubtitle(available));
        available.getJSONObject("subtitles").put("zh-Hans",new JSONArray());
        assertEquals("en",Engine.preferredSubtitle(available));
        available.getJSONObject("subtitles").put("en",new JSONArray());
        assertEquals("fr",Engine.preferredSubtitle(available));
        assertEquals("",Engine.preferredSubtitle(new JSONObject("{\"subtitles\":{\"zh-Hans\":[],\"en\":[{}],\"live_chat\":[{\"url\":\"https://example.org/chat.json\"}]}}")));
    }
    @Test public void htmlWithSuccessfulHttpStatusNeverCountsAsACompletedMediaFile() throws Exception {
        File file=File.createTempFile("not-media-",".mp4");
        try{Files.write(file.toPath(),"<!DOCTYPE html><html><title>Site Unavailable</title></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try{MediaValidation.rejectPage(file);fail("HTML must fail before media publication");}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("没有下载到媒体"));}
        }finally{file.delete();}
    }
    @Test public void fastStartMp4WithTruncatedDeclaredMdatIsRejected() throws Exception {
        File file=File.createTempFile("truncated-mdat-",".mp4");
        try(java.io.DataOutputStream out=new java.io.DataOutputStream(new java.io.FileOutputStream(file))){
            out.writeInt(16);out.writeBytes("ftyp");out.writeLong(0);out.writeInt(8);out.writeBytes("moov");
            out.writeInt(1000);out.writeBytes("mdat");out.writeLong(0);
        }
        try{MediaValidation.containerBounds(file);fail("Metadata before the data must not hide truncation");}
        catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("截断"));}finally{file.delete();}
    }
    @Test public void invalidFilesHaveActionableMessagesWithoutMisleadingAccountAdvice() {
        String page=CoreRules.userMessage(new java.io.IOException("源站返回了网页或错误信息，没有下载到媒体文件"));
        assertTrue(page.contains("网页"));assertFalse(page.contains("登录"));
        String cut=CoreRules.userMessage(new java.io.IOException("媒体文件已截断，未保存不完整内容"));assertTrue(cut.contains("不完整"));assertTrue(cut.contains("重试"));
        String xhs=CoreRules.inspectionMessage(new java.io.IOException("小红书未返回所选笔记"));assertTrue(xhs.contains("完整分享链接"));
    }
}
