package dev.streampocket;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebView;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/**
 * Own-app recreation and real AES/GCM/SQLite fixtures. No personal credentials,
 * Android package installation or device AndroidKeyStore claim is made here.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
@LooperMode(LooperMode.Mode.PAUSED)
public final class UpgradeRetentionTest {
    private static final String HEADER="# Netscape HTTP Cookie File\n\n";
    private Context app;
    private final SecretKeySpec key=new SecretKeySpec(new byte[32],"AES");
    private AccountStore accounts;
    private ActivityController<LoginActivity> controller;

    @Before public void setup() throws Exception {
        app=RuntimeEnvironment.getApplication();
        app.getSharedPreferences("account-sessions-metadata",0).edit().clear().commit();
        app.getSharedPreferences("tasks",0).edit().clear().commit();
        app.getSharedPreferences("config",0).edit().clear().commit();
        remove(new File(app.getNoBackupFilesDir(),"account-sessions"));
        new File(app.getFilesDir(),"cookies.txt").delete();
        new File(app.getFilesDir(),"cookies.txt.new").delete();
        app.deleteDatabase("media-library.db");
        accounts=new AccountStore(app,key);
        CookieManager.getInstance().removeAllCookies(null);
    }

    @After public void cleanup() throws Exception {
        if(controller!=null) {
            ExecutorService io=field(controller.get(),"io",ExecutorService.class);
            controller.pause().stop().destroy();
            assertTrue(io.awaitTermination(5,TimeUnit.SECONDS));
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        CookieManager.getInstance().removeAllCookies(null);
        remove(new File(app.getNoBackupFilesDir(),"account-sessions"));
        remove(new File(app.getExternalFilesDir("downloads"),"retention-active-fixture"));
        app.deleteDatabase("media-library.db");
    }

    @Test public void applicationAndStoresRecreateWithoutDroppingSessionsHistoryLibraryOrDrafts() throws Exception {
        Map<LoginPlatform,String[]> sessionFixtures=new EnumMap<>(LoginPlatform.class);
        sessionFixtures.put(LoginPlatform.X,new String[]{".x.com","auth_token"});
        sessionFixtures.put(LoginPlatform.BILIBILI,new String[]{".bilibili.com","SESSDATA"});
        sessionFixtures.put(LoginPlatform.DOUYIN,new String[]{".douyin.com","sessionid"});
        sessionFixtures.put(LoginPlatform.YOUTUBE,new String[]{".youtube.com","SAPISID"});
        sessionFixtures.put(LoginPlatform.XIAOHONGSHU,new String[]{".xiaohongshu.com","web_session"});
        sessionFixtures.put(LoginPlatform.KUAISHOU,new String[]{".kuaishou.com","kuaishou.server.web_st"});
        sessionFixtures.put(LoginPlatform.TIKTOK,new String[]{".tiktok.com","sessionid"});
        sessionFixtures.put(LoginPlatform.INSTAGRAM,new String[]{".instagram.com","sessionid"});
        sessionFixtures.put(LoginPlatform.FACEBOOK,new String[]{".facebook.com","c_user"});
        sessionFixtures.put(LoginPlatform.PINTEREST,new String[]{".pinterest.com","_pinterest_sess"});
        sessionFixtures.put(LoginPlatform.PORNHUB,new String[]{".pornhub.com","synthetic_session"});
        sessionFixtures.put(LoginPlatform.HEYBOX,new String[]{".xiaoheihe.cn","pkey"});
        sessionFixtures.put(LoginPlatform.WEIBO,new String[]{".weibo.com","synthetic_session"});
        sessionFixtures.put(LoginPlatform.XIGUA,new String[]{".ixigua.com","synthetic_session"});
        sessionFixtures.put(LoginPlatform.ACFUN,new String[]{".acfun.cn","synthetic_session"});
        sessionFixtures.put(LoginPlatform.REDDIT,new String[]{".reddit.com","reddit_session"});
        sessionFixtures.put(LoginPlatform.VIMEO,new String[]{".vimeo.com","vimeo"});
        sessionFixtures.put(LoginPlatform.TWITCH,new String[]{".twitch.tv","auth-token"});
        assertEquals("Each platform needs its own retention fixture",EnumSet.allOf(LoginPlatform.class),sessionFixtures.keySet());
        for(Map.Entry<LoginPlatform,String[]> fixture:sessionFixtures.entrySet()) {
            LoginPlatform platform=fixture.getKey(); String[] cookie=fixture.getValue();
            assertTrue(platform.id,platform.ownsDomain(cookie[0]));
            accounts.save(platform,HEADER+row(cookie[0],true,"/",true,0,cookie[1],"synthetic-"+platform.id),
                    platform==LoginPlatform.X||platform==LoginPlatform.BILIBILI?"网页登录":"文件导入");
        }
        accounts.setEnabled(LoginPlatform.BILIBILI,false);
        Map<String,byte[]> encrypted=new LinkedHashMap<>();
        for(LoginPlatform p:LoginPlatform.values()) encrypted.put(p.id,Files.readAllBytes(session(p).toPath()));
        Map<String,?> metadata=new LinkedHashMap<>(app.getSharedPreferences("account-sessions-metadata",0).getAll());
        app.getSharedPreferences("config",0).edit().putInt("download_connections",8).putString("proxy","http://fixture.invalid:8080").commit();
        JSONObject completed=completedTask();
        JSONObject active=new JSONObject().put("id","retention-active-fixture").put("title","正在下载的合成任务")
                .put("status","downloading").put("url","https://x.com/fixture/status/987")
                .put("progress",48).put("createdAt",123L);
        app.getSharedPreferences("tasks",0).edit().putString("items",new JSONArray().put(completed).put(active).toString()).commit();
        File partial=new File(app.getExternalFilesDir("downloads"),"retention-active-fixture/video.part");
        assertTrue(partial.getParentFile().mkdirs()); Files.write(partial.toPath(),new byte[]{1,2,3,4});
        long video,point,range,collection,project,clip;
        try(LibraryStore store=new LibraryStore(app)) {
            video=store.upsertCompletedDownload(completed); store.savePlayback(video,35_250);
            point=store.addMarker(video,34_250,"合成时间点"); range=store.addRangeMarker(video,20_250,35_875,"合成精彩片段");
            collection=store.createCollection("合成合集"); store.setCollection(video,collection,true);
            project=store.createProject("保留的剪辑草稿"); clip=store.addClip(project,video,20_250,35_875);
        }
        // Execute the actual startup path with the same application's storage,
        // explicitly resetting only the in-memory queue initialization guard.
        Field initialized=TaskStore.class.getDeclaredField("initialized"); initialized.setAccessible(true); initialized.setBoolean(null,false);
        PocketApp recreated=new PocketApp(); Method attach=Application.class.getDeclaredMethod("attach",Context.class); attach.setAccessible(true); attach.invoke(recreated,app); recreated.onCreate();
        AccountStore reopened=new AccountStore(recreated,key); reopened.prepare();
        for(LoginPlatform p:LoginPlatform.values()) {
            assertTrue(reopened.hasSaved(p)); assertArrayEquals(encrypted.get(p.id),Files.readAllBytes(session(p).toPath()));
            assertEquals(accounts.load(p),reopened.load(p));
        }
        assertFalse(reopened.isEnabled(LoginPlatform.BILIBILI)); assertTrue(reopened.isEnabled(LoginPlatform.X));
        assertEquals(metadata,app.getSharedPreferences("account-sessions-metadata",0).getAll());
        assertEquals(8,app.getSharedPreferences("config",0).getInt("download_connections",0));
        assertEquals("http://fixture.invalid:8080",app.getSharedPreferences("config",0).getString("proxy",""));
        assertEquals("completed",TaskStore.get(recreated,"retention-completed-fixture").getString("status"));
        assertEquals(completed.getString("uri"),TaskStore.get(recreated,"retention-completed-fixture").getString("uri"));
        assertEquals("interrupted",TaskStore.get(recreated,"retention-active-fixture").getString("status"));
        assertEquals(active.getString("url"),TaskStore.get(recreated,"retention-active-fixture").getString("url"));
        assertArrayEquals(new byte[]{1,2,3,4},Files.readAllBytes(partial.toPath()));
        try(LibraryStore store=new LibraryStore(recreated)) {
            assertEquals(35_250,store.video(video).getLong("playbackMs"));
            List<JSONObject> marks=store.markers(video); assertEquals(2,marks.size());
            assertTrue(marks.stream().anyMatch(m->m.optLong("id")==point));
            assertTrue(marks.stream().anyMatch(m->m.optLong("id")==range && m.optLong("endMs")==35_875));
            assertEquals(1,store.listVideos("","",collection,10,0).size());
            assertEquals("保留的剪辑草稿",store.project(project).getString("title"));
            assertEquals(clip,store.clips(project).get(0).getLong("id"));
        }
    }

    @Test public void unreadableSessionNeverDeletesItsMasterOrUnrelatedHistory() throws Exception {
        accounts.save(LoginPlatform.X,HEADER+row(".x.com",true,"/",true,0,"auth_token","retained-synthetic"),"网页登录");
        byte[] before=Files.readAllBytes(session(LoginPlatform.X).toPath());
        Map<String,?> metadata=new LinkedHashMap<>(app.getSharedPreferences("account-sessions-metadata",0).getAll());
        JSONObject task=completedTask(); app.getSharedPreferences("tasks",0).edit().putString("items",new JSONArray().put(task).toString()).commit();
        long video; try(LibraryStore store=new LibraryStore(app)) { video=store.upsertCompletedDownload(task); store.addMarker(video,12_000,"保留标记"); }
        byte[] wrong=new byte[32]; wrong[0]=1; AccountStore unreadable=new AccountStore(app,new SecretKeySpec(wrong,"AES"));
        for(int i=0;i<2;i++) {
            unreadable.prepare();
            try { unreadable.load(LoginPlatform.X); fail("Wrong key must fail closed"); }
            catch(IOException expected) { assertFalse(expected.getMessage().contains("retained-synthetic")); }
            try { unreadable.writeSnapshot(new File(app.getCacheDir(),"failed-restore-fixture.txt")); fail(); }
            catch(IOException expected) { /* A request must not erase the master after a read failure. */ }
        }
        assertArrayEquals(before,Files.readAllBytes(session(LoginPlatform.X).toPath())); assertEquals(metadata,app.getSharedPreferences("account-sessions-metadata",0).getAll());
        assertTrue(accounts.load(LoginPlatform.X).contains("retained-synthetic"));
        assertEquals(task.getString("uri"),TaskStore.get(app,task.getString("id")).getString("uri"));
        try(LibraryStore store=new LibraryStore(app)) { assertEquals(1,store.markers(video).size()); }
    }

    @Test public void browserRestorePreservesScopePathSecurityAndExpiryAndSkipsExpiredCookies() throws Exception {
        long future=System.currentTimeMillis()/1000+3600;
        String fixture=HEADER+"#HttpOnly_"+row(".bilibili.com",true,"/",true,future,"SESSDATA","synthetic")
                +row("api.bilibili.com",false,"/private",false,0,"host_only","narrow")
                +row(".bilibili.com",true,"/",true,1,"expired","gone");
        List<AccountStore.WebCookie> restored=AccountStore.webRestoreCookies(LoginPlatform.BILIBILI,fixture);
        assertEquals(2,restored.size());
        assertEquals("https://bilibili.com/",restored.get(0).url);
        assertTrue(restored.get(0).value.contains("Domain=.bilibili.com")); assertTrue(restored.get(0).value.contains("; Secure"));
        assertTrue(restored.get(0).value.contains("; HttpOnly")); assertTrue(restored.get(0).value.contains("; Expires="));
        assertEquals("https://api.bilibili.com/",restored.get(1).url); assertEquals("host_only=narrow; Path=/private",restored.get(1).value);
        Map<String,List<String>> roundTrip=new LinkedHashMap<>(); roundTrip.put("https://bilibili.com/",Arrays.asList(restored.get(0).value));
        String round=AccountStore.fromWebCookieInfo(LoginPlatform.BILIBILI,roundTrip);
        assertEquals(future,Long.parseLong(round.split("\n")[2].split("\t")[4]),1);
        for(String invalid:new String[]{HEADER+row(".x.com",true,"/",true,0,"auth_token","wrong-platform"),
                HEADER+row(".bilibili.com",true,"/",true,0,"SESSDATA","value; Domain=.evil.example"),
                HEADER+row(".bilibili.com",true,"/; Secure",true,0,"SESSDATA","synthetic")}) {
            try { AccountStore.webRestoreCookies(LoginPlatform.BILIBILI,invalid); fail("Restore must not broaden cookie attributes"); }
            catch(IOException expected) {}
        }
    }

    @Test public void browserSeedsCookiesBeforeEnablingThePageAndCancelsLateCallbacks() throws Exception {
        controller=Robolectric.buildActivity(LoginActivity.class,new Intent().putExtra("platform","bilibili")).create().start().resume();
        LoginActivity activity=controller.get(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        List<AccountStore.WebCookie> rows=AccountStore.webRestoreCookies(LoginPlatform.BILIBILI,HEADER
                +row(".bilibili.com",true,"/",true,0,"SESSDATA","synthetic-web-session")
                +row(".bilibili.com",true,"/",true,0,"bili_jct","synthetic-csrf"));
        Field ready=fieldObject(activity,"ready"),token=fieldObject(activity,"browserGeneration"); ready.setBoolean(activity,false);
        Method restore=LoginActivity.class.getDeclaredMethod("restoreWebCookies",int.class,List.class,int.class,int.class); restore.setAccessible(true);
        restore.invoke(activity,token.getInt(activity),rows,0,0);
        assertFalse("The native page stays unready until cookie callbacks finish",ready.getBoolean(activity));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(ready.getBoolean(activity)); assertEquals(LoginPlatform.BILIBILI.loginUrl,field(activity,"web",WebView.class).getUrl());
        assertTrue(CookieManager.getInstance().getCookie("https://bilibili.com/").contains("SESSDATA=synthetic-web-session"));
        assertFalse("Restoring the temporary browser must not silently save a master",new AccountStore(app).hasSaved(LoginPlatform.BILIBILI));
        ready.setBoolean(activity,false); int stale=token.getInt(activity); restore.invoke(activity,stale,rows,0,0);
        token.setInt(activity,stale+1); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse("A stale callback may not reopen or mark a replacement page ready",ready.getBoolean(activity));
    }

    private JSONObject completedTask() throws Exception {
        return new JSONObject().put("id","retention-completed-fixture").put("title","保留的合成下载记录").put("status","completed")
                .put("uri","content://media/external/downloads/987654").put("mime","video/mp4").put("size",4096).put("createdAt",100L)
                .put("media",new JSONObject().put("duration",100).put("url","https://x.com/fixture/status/123"));
    }
    private File session(LoginPlatform platform) { return new File(app.getNoBackupFilesDir(),"account-sessions/"+platform.id+".session"); }
    private static String row(String domain,boolean subdomains,String path,boolean secure,long expires,String name,String value) {
        return domain+"\t"+(subdomains?"TRUE":"FALSE")+"\t"+path+"\t"+(secure?"TRUE":"FALSE")+"\t"+expires+"\t"+name+"\t"+value+"\n";
    }
    private static Field fieldObject(Object target,String name) throws Exception { Field field=target.getClass().getDeclaredField(name); field.setAccessible(true); return field; }
    private static <T> T field(Object target,String name,Class<T> type) throws Exception { return type.cast(fieldObject(target,name).get(target)); }
    private static void remove(File file) {
        File[] children=file.listFiles(); if(children!=null) for(File child:children) remove(child); file.delete();
    }
}
