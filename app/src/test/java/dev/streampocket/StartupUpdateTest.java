package dev.streampocket;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Looper;
import java.io.IOException;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Real update-state transitions with isolated metadata and package-identity fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class StartupUpdateTest {
    private static final String TAG="v9.0";
    private static final String MANIFEST_URL=UpdateOffer.REPO+"/releases/download/"+TAG+"/"+UpdateOffer.MANIFEST;
    private final List<AppUpdates> managers=new ArrayList<>();
    private Context app;
    private SharedPreferences prefs;
    private UpdateOffer offer;

    @Before public void prepareIdentity()throws Exception{
        app=RuntimeEnvironment.getApplication();prefs=app.getSharedPreferences("app_updates",0);
        assertTrue(prefs.edit().clear().commit());
        ReflectionHelpers.setStaticField(Build.class,"SUPPORTED_ABIS",new String[]{"arm64-v8a"});
        Signature signature=new Signature(new byte[]{5,1,0,7});
        String certificate=AppUpdates.hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()));
        PackageInfo installed=app.getPackageManager().getPackageInfo(app.getPackageName(),0);
        installed.setLongVersionCode(BuildConfig.VERSION_CODE);installed.versionName=BuildConfig.VERSION_NAME;
        installed.signatures=new Signature[]{signature};installed.signingInfo=null;
        Shadows.shadowOf(app.getPackageManager()).addPackage(installed);
        JSONObject manifest=new JSONObject().put("schemaVersion",1).put("versionCode",900).put("versionName","9.0-fixture")
                .put("packageName",app.getPackageName()).put("abi","arm64-v8a").put("minSdk",24)
                .put("apkAssetName",UpdateOffer.APK).put("apkSize",4096).put("apkSha256","a".repeat(64))
                .put("signingCertificateSha256",certificate);
        offer=new UpdateOffer(manifest,TAG,"Synthetic release metadata; no APK is downloaded.",
                UpdateOffer.REPO+"/releases/download/"+TAG+"/"+UpdateOffer.APK);
    }

    @After public void closeWorkers()throws Exception{
        for(AppUpdates updates:managers){
            ExecutorService executor=worker(updates);executor.shutdownNow();
            assertTrue("Fixture worker must terminate",executor.awaitTermination(5,TimeUnit.SECONDS));
        }
        prefs.edit().clear().commit();
    }

    @Test public void legacyDailyTimestampCannotSuppressAColdStartAndEachForegroundChecksOnce()throws Exception{
        prefs.edit().putLong("last_check",System.currentTimeMillis()).commit();
        AtomicInteger requests=new AtomicInteger();AppUpdates updates=manager(availableReader(requests));
        updates.enteredForeground();updates.checkIfDue();settle(updates);
        assertEquals(1,requests.get());assertEquals(AppUpdates.Phase.AVAILABLE,updates.snapshot().phase);
        updates.checkIfDue();updates.checkIfDue();settle(updates);
        assertEquals("Returning between pages must not repeat this session's check",1,requests.get());
        updates.leftForeground();updates.enteredForeground();updates.checkIfDue();settle(updates);
        assertEquals("A genuine foreground entry must discover a newly published release",2,requests.get());
    }

    @Test public void failedCheckRetriesOnTheNextForegroundWithoutLoopingInTheCurrentOne()throws Exception{
        AtomicInteger requests=new AtomicInteger();AtomicBoolean offline=new AtomicBoolean(true);
        AppUpdates.JsonReader available=availableReader(requests);
        AppUpdates updates=manager((url,limit,missing)->{
            if(UpdateOffer.API.equals(url)&&offline.get()){requests.incrementAndGet();throw new IOException("fixture offline");}
            return available.read(url,limit,missing);
        });
        updates.enteredForeground();updates.checkIfDue();settle(updates);
        assertEquals(AppUpdates.Phase.ERROR,updates.snapshot().phase);assertEquals(1,requests.get());
        updates.checkIfDue();settle(updates);assertEquals("No automatic retry loop",1,requests.get());
        offline.set(false);updates.leftForeground();updates.enteredForeground();updates.checkIfDue();settle(updates);
        assertEquals(2,requests.get());assertEquals(AppUpdates.Phase.AVAILABLE,updates.snapshot().phase);
    }

    @Test public void anOlderInFlightFailureDoesNotConsumeTheNewForegroundRequest()throws Exception{
        CountDownLatch started=new CountDownLatch(1),finishOldRequest=new CountDownLatch(1);
        AtomicInteger requests=new AtomicInteger();String release=release(),manifest=offer.json().toString();
        AppUpdates updates=manager((url,limit,missing)->{
            if(UpdateOffer.API.equals(url)){
                if(requests.incrementAndGet()==1){
                    started.countDown();
                    if(!finishOldRequest.await(5,TimeUnit.SECONDS))throw new AssertionError("Old request was not released");
                    throw new IOException("fixture connection lost while backgrounded");
                }
                return release;
            }
            assertEquals(MANIFEST_URL,url);return manifest;
        });
        try{
            updates.enteredForeground();updates.checkIfDue();
            assertTrue("First request must be in flight",started.await(5,TimeUnit.SECONDS));
            updates.leftForeground();updates.enteredForeground();updates.checkIfDue();
            assertEquals("In-flight checks must be merged",1,requests.get());
            finishOldRequest.countDown();settle(updates);
            assertEquals("The newer foreground gets its check after the old request fails",2,requests.get());
            assertEquals(AppUpdates.Phase.AVAILABLE,updates.snapshot().phase);
        }finally{finishOldRequest.countDown();}
    }

    @Test public void disablingAutomaticKeepsManualChecksAndReenablingChecksImmediately()throws Exception{
        prefs.edit().putBoolean("automatic",false).commit();
        AtomicInteger requests=new AtomicInteger();AppUpdates updates=manager(availableReader(requests));
        updates.enteredForeground();updates.checkIfDue();settle(updates);
        assertEquals(0,requests.get());assertEquals(AppUpdates.Phase.IDLE,updates.snapshot().phase);
        updates.check();settle(updates);
        assertEquals("Manual checks remain available with the switch off",1,requests.get());
        assertEquals(AppUpdates.Phase.AVAILABLE,updates.snapshot().phase);
        updates.automatic(true);settle(updates);
        assertTrue(updates.automatic());assertEquals("Enabling must not wait for another launch",2,requests.get());
        updates.automatic(true);settle(updates);assertEquals("Writing the same setting is not another check",2,requests.get());
    }

    @Test public void aVerifiedOfferSurvivesProcessRecreationAndAnOfflineRefresh()throws Exception{
        AtomicInteger requests=new AtomicInteger();AppUpdates first=manager(availableReader(requests));
        first.enteredForeground();first.checkIfDue();settle(first);
        assertTrue("Discovery must be persisted before the user starts a download",prefs.contains("offer"));
        assertEquals(offer.code,UpdateOffer.saved(prefs.getString("offer","")).code);
        AppUpdates restored=manager((url,limit,missing)->{throw new IOException("fixture offline");});
        assertEquals(AppUpdates.Phase.AVAILABLE,restored.snapshot().phase);
        assertEquals(offer.sha256,restored.snapshot().offer.sha256);
        restored.enteredForeground();restored.checkIfDue();settle(restored);
        assertEquals("A transient failure must not hide the already verified release",AppUpdates.Phase.AVAILABLE,restored.snapshot().phase);
        assertEquals(offer.code,restored.snapshot().offer.code);
        assertTrue(prefs.contains("offer"));
    }

    @Test public void confirmedNoUpdateClearsCachedOffersForMissingAndAlreadyInstalledReleases()throws Exception{
        String installedManifest=offer.json().put("versionCode",BuildConfig.VERSION_CODE)
                .put("versionName",BuildConfig.VERSION_NAME).toString();
        for(String latest:new String[]{null,release()}){
            prefs.edit().putString("offer",offer.json().toString()).commit();
            AtomicInteger requests=new AtomicInteger();AppUpdates updates=manager(reader(requests,latest,installedManifest));
            assertEquals(AppUpdates.Phase.AVAILABLE,updates.snapshot().phase);
            updates.enteredForeground();updates.checkIfDue();settle(updates);
            assertEquals(1,requests.get());assertEquals(AppUpdates.Phase.NONE,updates.snapshot().phase);
            assertNull(updates.snapshot().offer);assertFalse("A retired offer must not reappear on restart",prefs.contains("offer"));
            AppUpdates restored=manager((url,limit,missing)->{throw new AssertionError("Restoring a manager must not use the network");});
            settle(restored);assertNull(restored.snapshot().offer);
        }
    }

    @Test public void restoringMetadataStillRejectsADifferentSigningIdentity()throws Exception{
        prefs.edit().putString("offer",offer.json().put("signingCertificateSha256","b".repeat(64)).toString()).commit();
        AppUpdates updates=manager((url,limit,missing)->{throw new AssertionError("Restore must remain offline");});
        settle(updates);
        assertNull("A cached record is not a reason to bypass installed-package compatibility",updates.snapshot().offer);
        assertNotEquals(AppUpdates.Phase.AVAILABLE,updates.snapshot().phase);
    }

    @Test public void laterOnlyMutesTheCurrentForegroundButReadyInstallRemindersRemainAcknowledged()throws Exception{
        prefs.edit().putLong("notified_code",offer.code).commit();
        AppUpdates updates=manager((url,limit,missing)->{throw new AssertionError("Notification bookkeeping must not fetch metadata");});
        updates.enteredForeground();
        assertTrue("An old one-time notification must not permanently mute the release",updates.shouldNotify(offer.code));
        updates.notified(offer.code);assertFalse(updates.shouldNotify(offer.code));
        updates.notifiedReady(offer.code);assertFalse(updates.shouldNotifyReady(offer.code));
        updates.leftForeground();updates.enteredForeground();
        assertTrue("Later means a later foreground visit",updates.shouldNotify(offer.code));
        assertFalse("This change must not reopen acknowledged installer reminders",updates.shouldNotifyReady(offer.code));
    }

    @Test public void anActiveDownloadDoesNotConsumeThePendingForegroundCheck()throws Exception{
        AtomicInteger requests=new AtomicInteger();AppUpdates updates=manager(availableReader(requests));
        Field state=AppUpdates.class.getDeclaredField("state");state.setAccessible(true);
        state.set(updates,new AppUpdates.Snapshot(AppUpdates.Phase.DOWNLOADING,offer,"fixture download",0));
        updates.enteredForeground();updates.checkIfDue();settle(updates);
        assertEquals(0,requests.get());assertEquals(AppUpdates.Phase.DOWNLOADING,updates.snapshot().phase);
        updates.cancel();settle(updates);updates.checkIfDue();settle(updates);
        assertEquals("Finishing the active flow must leave this session eligible to check",1,requests.get());
        assertEquals(AppUpdates.Phase.AVAILABLE,updates.snapshot().phase);
    }

    private AppUpdates manager(AppUpdates.JsonReader source){
        AppUpdates updates=new AppUpdates(app,source);managers.add(updates);return updates;
    }
    private AppUpdates.JsonReader availableReader(AtomicInteger requests)throws Exception{
        return reader(requests,release(),offer.json().toString());
    }
    private AppUpdates.JsonReader reader(AtomicInteger requests,String latest,String manifest){
        return (url,limit,missing)->{
            if(UpdateOffer.API.equals(url)){requests.incrementAndGet();return latest;}
            assertEquals("Only the pinned manifest should be read",MANIFEST_URL,url);return manifest;
        };
    }
    private String release()throws Exception{
        JSONArray assets=new JSONArray()
                .put(new JSONObject().put("name",UpdateOffer.APK).put("state","uploaded").put("browser_download_url",offer.url)
                        .put("size",offer.size).put("digest","sha256:"+offer.sha256))
                .put(new JSONObject().put("name",UpdateOffer.MANIFEST).put("state","uploaded").put("browser_download_url",MANIFEST_URL));
        return new JSONObject().put("draft",false).put("prerelease",false).put("tag_name",TAG)
                .put("body",offer.notes).put("assets",assets).toString();
    }
    private static ExecutorService worker(AppUpdates updates)throws Exception{
        Field field=AppUpdates.class.getDeclaredField("worker");field.setAccessible(true);return(ExecutorService)field.get(updates);
    }
    private static void settle(AppUpdates updates)throws Exception{
        for(int i=0;i<4;i++){worker(updates).submit(()->{}).get(5,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    }
}
