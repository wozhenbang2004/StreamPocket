package dev.streampocket;

import android.app.Application;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
@LooperMode(LooperMode.Mode.PAUSED)
public final class AppUpdatesTest {
    private static final byte[] DATA="fixture APK bytes, not an installable APK".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final String CERT="a".repeat(64),URL=UpdateOffer.REPO+"/releases/download/v9.0/"+UpdateOffer.APK;
    private JSONObject manifest()throws Exception{return new JSONObject().put("schemaVersion",1).put("versionCode",900).put("versionName","9.0")
            .put("packageName",BuildConfig.APPLICATION_ID).put("abi","arm64-v8a").put("minSdk",24).put("apkAssetName",UpdateOffer.APK)
            .put("apkSize",DATA.length).put("apkSha256",AppUpdates.hex(MessageDigest.getInstance("SHA-256").digest(DATA))).put("signingCertificateSha256",CERT);}
    private UpdateOffer offer()throws Exception{return new UpdateOffer(manifest(),"v9.0","真实发布说明由服务器提供",URL);}
    private void rejects(Throwing call)throws Exception{try{call.run();fail("Unsafe update must be rejected");}catch(java.io.IOException|IllegalArgumentException expected){}}
    interface Throwing{void run()throws Exception;}

    @Test public void manifestRoundTripAndMatchingUpgradeAreAccepted()throws Exception{
        UpdateOffer offer=UpdateOffer.saved(offer().json().toString());offer.compatible(BuildConfig.APPLICATION_ID,13,CERT,35,new String[]{"arm64-v8a"});
        assertEquals(900,offer.code);assertEquals(DATA.length,offer.size);assertEquals(URL,offer.url);
    }
    @Test public void downgradeWrongPackageSignatureSdkAndAbiAreRejected()throws Exception{
        UpdateOffer o=offer();rejects(()->o.compatible(BuildConfig.APPLICATION_ID,900,CERT,35,new String[]{"arm64-v8a"}));
        rejects(()->o.compatible(BuildConfig.APPLICATION_ID,901,CERT,35,new String[]{"arm64-v8a"}));
        rejects(()->o.compatible("another.app",13,CERT,35,new String[]{"arm64-v8a"}));
        rejects(()->o.compatible(BuildConfig.APPLICATION_ID,13,"b".repeat(64),35,new String[]{"arm64-v8a"}));
        rejects(()->o.compatible(BuildConfig.APPLICATION_ID,13,CERT,23,new String[]{"arm64-v8a"}));
        rejects(()->o.compatible(BuildConfig.APPLICATION_ID,13,CERT,35,new String[]{"x86_64"}));
    }
    @Test public void assetSourcesCannotEscapeThePinnedRepository()throws Exception{
        assertTrue(UpdateOffer.validAssetUrl(URL,"v9.0",UpdateOffer.APK));
        for(String bad:new String[]{URL.replace("https:","http:"),URL.replace("github.com","github.com.evil.test"),URL.replace("github.com","evil@github.com"),URL+"?other=1",URL.replace("StreamPocket/releases","Other/releases"),URL.replace("v9.0/","v9.0%2f/")})assertFalse(bad,UpdateOffer.validAssetUrl(bad,"v9.0",UpdateOffer.APK));
        assertFalse(UpdateOffer.allowedRedirect("http://release-assets.githubusercontent.com/file"));
        assertFalse(UpdateOffer.allowedRedirect("https://release-assets.githubusercontent.com.evil.test/file"));
        assertTrue(UpdateOffer.allowedRedirect("https://release-assets.githubusercontent.com/file?sig=public-download"));
    }
    @Test public void stableAndPreviewReleaseFeedsStaySeparated()throws Exception{
        JSONObject stable=new JSONObject().put("draft",false).put("prerelease",false).put("tag_name","v0.6.1");
        JSONObject previewMine=new JSONObject().put("draft",false).put("prerelease",true).put("tag_name","v0.6.0-preview.run-42.2");
        JSONObject previewOther=new JSONObject().put("draft",false).put("prerelease",true).put("tag_name","v0.6.0-preview.other.9");
        JSONObject draftMine=new JSONObject().put("draft",true).put("prerelease",true).put("tag_name","v0.6.0-preview.run-42.3");
        org.json.JSONArray feed=new org.json.JSONArray().put(draftMine).put(previewOther).put(stable).put(previewMine);
        assertEquals("v0.6.1",UpdateOffer.release(stable.toString(),false,"").getString("tag_name"));
        assertNull(UpdateOffer.release(previewMine.toString(),false,""));
        assertEquals("v0.6.0-preview.run-42.2",UpdateOffer.release(feed.toString(),true,"run-42").getString("tag_name"));
        assertNull(UpdateOffer.release(feed.toString(),true,"missing"));
        assertNull(UpdateOffer.release(feed.toString(),true,""));
        assertTrue(UpdateOffer.previewTag("v0.6.0-preview.run-42.2","run-42"));
        assertFalse(UpdateOffer.previewTag("v0.6.0-preview.other.2","run-42"));
    }

    @Test public void malformedOrOversizedManifestCannotCreateAnOffer()throws Exception{
        rejects(()->new UpdateOffer(manifest().put("schemaVersion",2),"v9.0","",URL));
        rejects(()->new UpdateOffer(manifest().put("apkSize",UpdateOffer.MAX_APK_BYTES+1),"v9.0","",URL));
        rejects(()->new UpdateOffer(manifest().put("apkSha256","not-a-hash"),"v9.0","",URL));
    }
    @Test public void truncatedWrongHashOversizedAndCancelledCopiesAreDeleted()throws Exception{
        File target=new File(RuntimeEnvironment.getApplication().getCacheDir(),"update-fixture.apk");UpdateOffer o=offer();
        rejects(()->AppUpdates.copyAndHash(new ByteArrayInputStream(new byte[2]),target,o,()->false));assertFalse(target.exists());
        rejects(()->AppUpdates.copyAndHash(new ByteArrayInputStream(new byte[DATA.length]),target,o,()->false));assertFalse(target.exists());
        rejects(()->AppUpdates.copyAndHash(new ByteArrayInputStream(new byte[DATA.length+1]),target,o,()->false));assertFalse(target.exists());
        rejects(()->AppUpdates.copyAndHash(new ByteArrayInputStream(DATA),target,o,()->true));assertFalse(target.exists());
    }
    @Test public void goodCopyIsRehashedBeforeInstallAndLaterTamperingFails()throws Exception{
        File target=new File(RuntimeEnvironment.getApplication().getCacheDir(),"verified-fixture.apk");UpdateOffer o=offer();
        AppUpdates.copyAndHash(new ByteArrayInputStream(DATA),target,o,()->false);AppUpdates.hashFile(target,o,()->false);
        try(FileOutputStream output=new FileOutputStream(target)){output.write(new byte[DATA.length]);}
        rejects(()->AppUpdates.hashFile(target,o,()->false));target.delete();
    }
    @Test public void archiveIdentityMustMatchEveryCriticalManifestField()throws Exception{
        UpdateOffer o=offer();AppUpdates.verifyIdentity(o,BuildConfig.APPLICATION_ID,900,"9.0",CERT,24);
        rejects(()->AppUpdates.verifyIdentity(o,"wrong",900,"9.0",CERT,24));
        rejects(()->AppUpdates.verifyIdentity(o,BuildConfig.APPLICATION_ID,899,"9.0",CERT,24));
        rejects(()->AppUpdates.verifyIdentity(o,BuildConfig.APPLICATION_ID,900,"9.1",CERT,24));
        rejects(()->AppUpdates.verifyIdentity(o,BuildConfig.APPLICATION_ID,900,"9.0","b".repeat(64),24));
        rejects(()->AppUpdates.verifyIdentity(o,BuildConfig.APPLICATION_ID,900,"9.0",CERT,26));
    }
    @Test public void processRestartRestoresOnlyItsOwnedDownloadAndCancellationRemovesIt()throws Exception{
        Application app=RuntimeEnvironment.getApplication();DownloadManager dm=(DownloadManager)app.getSystemService(Context.DOWNLOAD_SERVICE);
        long own=dm.enqueue(new DownloadManager.Request(Uri.parse(URL))),other=dm.enqueue(new DownloadManager.Request(Uri.parse("https://example.com/unrelated.mp4")));
        app.getSharedPreferences("app_updates",0).edit().putString("offer",offer().json().toString()).putLong("download_id",own).commit();
        AppUpdates updates=manager(app);ExecutorService worker=worker(updates);
        try{
            assertEquals(AppUpdates.Phase.DOWNLOADING,updates.snapshot().phase);updates.cancel();worker.submit(()->{}).get(5,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(AppUpdates.Phase.IDLE,updates.snapshot().phase);
            try(android.database.Cursor c=dm.query(new DownloadManager.Query().setFilterById(own))){assertFalse(c.moveToFirst());}
            try(android.database.Cursor c=dm.query(new DownloadManager.Query().setFilterById(other))){assertTrue("Never remove another download",c.moveToFirst());}
            assertFalse(app.getSharedPreferences("app_updates",0).contains("offer"));
        }finally{worker.shutdown();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test public void automaticCheckCanBeDisabledAndNoStatePretendsAReleaseExists()throws Exception{
        Application app=RuntimeEnvironment.getApplication();app.getSharedPreferences("app_updates",0).edit().clear().putBoolean("automatic",false).commit();AppUpdates updates=manager(app);
        try{updates.checkIfDue();assertEquals(AppUpdates.Phase.IDLE,updates.snapshot().phase);assertNull(updates.snapshot().offer);}
        finally{worker(updates).shutdown();}
    }
    private static AppUpdates manager(Context context)throws Exception{Constructor<AppUpdates> c=AppUpdates.class.getDeclaredConstructor(Context.class);c.setAccessible(true);return c.newInstance(context);}
    private static ExecutorService worker(AppUpdates updates)throws Exception{Field f=AppUpdates.class.getDeclaredField("worker");f.setAccessible(true);return(ExecutorService)f.get(updates);}
}
