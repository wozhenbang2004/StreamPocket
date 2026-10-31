package dev.streampocket;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Platform identity/file fixtures exercise the real updater and Activity; no phone installer runs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w412dp-h915dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public final class UpdateFlow030Test {
    private static final byte[] DATA="isolated update-flow fixture, not an installable APK".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private AppUpdates updates;
    private ExecutorService worker;
    private UpdateOffer offer;

    private void ready(boolean permission)throws Exception{
        Field singleton=AppUpdates.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,null);
        Field providerCache=androidx.core.content.FileProvider.class.getDeclaredField("sCache");providerCache.setAccessible(true);((java.util.Map<?,?>)providerCache.get(null)).clear();
        Context app=RuntimeEnvironment.getApplication();app.getSharedPreferences("app_updates",0).edit().clear().commit();
        ReflectionHelpers.setStaticField(Build.class,"SUPPORTED_ABIS",new String[]{"arm64-v8a"});
        Signature signature=new Signature(new byte[]{1,2,3,4});String cert=AppUpdates.hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()));
        PackageInfo own=app.getPackageManager().getPackageInfo(app.getPackageName(),android.content.pm.PackageManager.GET_PROVIDERS|android.content.pm.PackageManager.GET_RECEIVERS|android.content.pm.PackageManager.GET_META_DATA);own.signatures=new Signature[]{signature};own.signingInfo=null;
        Shadows.shadowOf(app.getPackageManager()).addPackage(own);Shadows.shadowOf(app.getPackageManager()).setCanRequestPackageInstalls(permission);
        assertEquals(permission,app.getPackageManager().canRequestPackageInstalls());
        JSONObject manifest=new JSONObject().put("schemaVersion",1).put("versionCode",900).put("versionName","9.0-fixture").put("packageName",app.getPackageName())
                .put("abi","arm64-v8a").put("minSdk",24).put("apkAssetName",UpdateOffer.APK).put("apkSize",DATA.length)
                .put("apkSha256",AppUpdates.hex(MessageDigest.getInstance("SHA-256").digest(DATA))).put("signingCertificateSha256",cert);
        offer=new UpdateOffer(manifest,"v9.0","更新流程测试数据",UpdateOffer.REPO+"/releases/download/v9.0/"+UpdateOffer.APK);
        File file=AppUpdates.readyFile(app,offer);file.getParentFile().mkdirs();try(FileOutputStream out=new FileOutputStream(file)){out.write(DATA);}
        PackageInfo archive=new PackageInfo();archive.packageName=app.getPackageName();archive.versionCode=900;archive.versionName=offer.version;
        archive.signatures=new Signature[]{signature};archive.applicationInfo=new ApplicationInfo();archive.applicationInfo.minSdkVersion=24;
        Shadows.shadowOf(app.getPackageManager()).setPackageArchiveInfo(file.getPath(),archive);
        updates=AppUpdates.get(app);worker=(ExecutorService)field(updates,"worker");
        set(updates,"state",new AppUpdates.Snapshot(AppUpdates.Phase.READY,offer,"已下载，等待交给系统",DATA.length));
    }
    private void settle()throws Exception{worker.submit(()->{}).get(5,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @After public void closeWorker()throws Exception{if(worker!=null){worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}}

    @Test public void separateReleasesNeverReuseInstallerUri()throws Exception{
        ready(true);Context app=RuntimeEnvironment.getApplication();
        java.lang.reflect.Method location=AppUpdates.class.getDeclaredMethod("readyFile");location.setAccessible(true);
        File first=(File)location.invoke(updates);
        Uri firstUri=androidx.core.content.FileProvider.getUriForFile(app,app.getPackageName()+".updates",first);
        UpdateOffer next=new UpdateOffer(offer.json().put("versionCode",901).put("versionName","9.1-fixture").put("apkSha256","b".repeat(64)),"v9.1","",UpdateOffer.REPO+"/releases/download/v9.1/"+UpdateOffer.APK);
        set(updates,"state",new AppUpdates.Snapshot(AppUpdates.Phase.READY,next,"已校验",next.size));
        File second=(File)location.invoke(updates);
        Uri secondUri=androidx.core.content.FileProvider.getUriForFile(app,app.getPackageName()+".updates",second);
        assertNotEquals("Two releases must never share a mutable installer address",firstUri,secondUri);
    }

    @Test public void visibleReadyPackageAutomaticallyRechecksAndOpensSystemInstallerOnce()throws Exception{
        ready(true);
        try(ActivityController<UpdateActivity> c=Robolectric.buildActivity(UpdateActivity.class).create().start().resume()){
            settle();ShadowActivity shadow=Shadows.shadowOf(c.get());ShadowActivity.IntentForResult started=shadow.getNextStartedActivityForResult();
            assertNotNull("Verified READY must not require an extra install button: "+updates.snapshot().message,started);assertEquals(Intent.ACTION_INSTALL_PACKAGE,started.intent.getAction());
            assertEquals("content",started.intent.getData().getScheme());assertTrue((started.intent.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0);
            assertTrue(started.intent.getData().getLastPathSegment().contains(offer.sha256));
            try(java.io.InputStream input=RuntimeEnvironment.getApplication().getContentResolver().openInputStream(started.intent.getData())){
                assertNotNull(input);assertArrayEquals("The installer URI must expose exactly the verified payload",DATA,input.readAllBytes());
            }
            c.pause().resume();settle();assertNull("Polling/resume must not repeat the system dialog",shadow.getNextStartedActivityForResult());
            shadow.callOnActivityResult(started.requestCode,Activity.RESULT_CANCELED,null);settle();assertNull("Declining system install must not reopen it",shadow.getNextStartedActivityForResult());
        }
    }
    @Test public void returningFromInstallerWithoutAnUpgradeShowsTheActualInstalledVersion()throws Exception{
        ready(true);
        try(ActivityController<UpdateActivity> c=Robolectric.buildActivity(UpdateActivity.class).create().start().resume()){
            settle();ShadowActivity shadow=Shadows.shadowOf(c.get());ShadowActivity.IntentForResult started=shadow.getNextStartedActivityForResult();assertNotNull(started);
            shadow.callOnActivityResult(started.requestCode,Activity.RESULT_OK,null);settle();
            assertEquals(AppUpdates.Phase.READY,updates.snapshot().phase);
            assertTrue(updates.installationMessage(offer).contains(BuildConfig.VERSION_NAME));
            assertTrue(updates.installationMessage(offer).contains("尚未升级"));
            assertNull("A failed version check must not reopen the installer automatically",shadow.getNextStartedActivityForResult());
        }
    }
    @Test public void allowingUnknownSourceReturnsDirectlyToVerificationAndSystemConfirmation()throws Exception{
        ready(false);
        try(ActivityController<UpdateActivity> c=Robolectric.buildActivity(UpdateActivity.class).create().start().resume()){
            Shadows.shadowOf(Looper.getMainLooper()).idle();AlertDialog dialog=(AlertDialog)field(c.get(),"sourceDialog");assertNotNull(dialog);assertTrue(dialog.isShowing());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();ShadowActivity shadow=Shadows.shadowOf(c.get());ShadowActivity.IntentForResult settings=shadow.getNextStartedActivityForResult();
            assertNotNull(settings);assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,settings.intent.getAction());
            c.pause();Shadows.shadowOf(c.get().getPackageManager()).setCanRequestPackageInstalls(true);
            shadow.callOnActivityResult(settings.requestCode,Activity.RESULT_OK,null);c.resume();settle();
            assertEquals(Intent.ACTION_INSTALL_PACKAGE,shadow.getNextStartedActivityForResult().intent.getAction());
        }
    }
    @Test public void backgroundVerificationCannotLaunchUntilTheUserReturns()throws Exception{
        ready(true);CountDownLatch release=new CountDownLatch(1);worker.submit(()->{try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        try(ActivityController<UpdateActivity> c=Robolectric.buildActivity(UpdateActivity.class).create().start().resume()){
            assertEquals(AppUpdates.Phase.VERIFYING,updates.snapshot().phase);c.pause();release.countDown();settle();
            assertNull(Shadows.shadowOf(c.get()).getNextStartedActivityForResult());assertFalse(updates.installerLaunched(offer));
            c.resume();settle();ShadowActivity.IntentForResult started=Shadows.shadowOf(c.get()).getNextStartedActivityForResult();assertNotNull(updates.snapshot().message,started);assertEquals(Intent.ACTION_INSTALL_PACKAGE,started.intent.getAction());
        }finally{release.countDown();}
    }
    @Test public void cancelAndReplacementOfferInvalidateThePendingInstallerHandoff()throws Exception{
        ready(true);CountDownLatch release=new CountDownLatch(1);worker.submit(()->{try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        try(ActivityController<UpdateActivity> c=Robolectric.buildActivity(UpdateActivity.class).create().start().resume()){
            updates.cancel();UpdateOffer replacement=new UpdateOffer(offer.json().put("versionCode",901).put("versionName","9.1-fixture"),"v9.1","新版本测试",UpdateOffer.REPO+"/releases/download/v9.1/"+UpdateOffer.APK);
            set(updates,"state",new AppUpdates.Snapshot(AppUpdates.Phase.AVAILABLE,replacement,"新版本需确认下载",0));release.countDown();settle();
            assertSame(replacement,updates.snapshot().offer);assertNull(Shadows.shadowOf(c.get()).getNextStartedActivityForResult());assertFalse(updates.installerLaunched(offer));
        }finally{release.countDown();}
    }
    @Test public void tamperingAfterDownloadBlocksAutomaticHandoff()throws Exception{
        ready(true);try(FileOutputStream out=new FileOutputStream(AppUpdates.readyFile(RuntimeEnvironment.getApplication(),offer))){out.write(new byte[DATA.length]);}
        try(ActivityController<UpdateActivity> c=Robolectric.buildActivity(UpdateActivity.class).create().start().resume()){
            settle();assertEquals(AppUpdates.Phase.ERROR,updates.snapshot().phase);assertNull(Shadows.shadowOf(c.get()).getNextStartedActivityForResult());assertFalse(updates.installerLaunched(offer));
        }
    }
    @Test public void decliningSourcePermissionDoesNotRepeatedlyPromptOrOpenSettings()throws Exception{
        ready(false);
        try(ActivityController<UpdateActivity> c=Robolectric.buildActivity(UpdateActivity.class).create().start().resume()){
            AlertDialog dialog=(AlertDialog)field(c.get(),"sourceDialog");assertNotNull(dialog);dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            c.pause().resume();settle();assertNull(field(c.get(),"sourceDialog"));assertNull(Shadows.shadowOf(c.get()).getNextStartedActivityForResult());assertEquals(AppUpdates.Phase.READY,updates.snapshot().phase);
        }
    }
    @Test public void completedDownloadAutomaticallyCopiesVerifiesAndOpensTheSystemConfirmation()throws Exception{
        ready(true);Context app=RuntimeEnvironment.getApplication();File ready=AppUpdates.readyFile(app,offer);
        PackageInfo archive=app.getPackageManager().getPackageArchiveInfo(ready.getPath(),0);assertTrue(ready.delete());
        Shadows.shadowOf(app.getPackageManager()).setPackageArchiveInfo(new File(app.getFilesDir(),"app-updates/staged.apk").getPath(),archive);
        File source=new File(app.getCacheDir(),"downloaded-update-fixture");try(FileOutputStream out=new FileOutputStream(source)){out.write(DATA);}
        DownloadFixture provider=new DownloadFixture(source);ProviderInfo info=new ProviderInfo();info.authority="downloads";provider.attachInfo(app,info);ShadowContentResolver.registerProviderInternal("downloads",provider);
        ownedDownload(app,DownloadManager.STATUS_SUCCESSFUL);
        try(ActivityController<UpdateActivity> c=Robolectric.buildActivity(UpdateActivity.class).create().start().resume()){
            Shadows.shadowOf(Looper.getMainLooper()).idle();settle();settle();
            ShadowActivity.IntentForResult started=Shadows.shadowOf(c.get()).getNextStartedActivityForResult();assertNotNull(updates.snapshot().message,started);
            assertEquals(Intent.ACTION_INSTALL_PACKAGE,started.intent.getAction());assertArrayEquals(DATA,java.nio.file.Files.readAllBytes(ready.toPath()));
        }
    }
    @Test public void systemDownloadNotificationIsOwnedProtectedAndNeverLaunchesOnCompletion()throws Exception{
        ready(true);Context app=RuntimeEnvironment.getApplication();long id=ownedDownload(app,DownloadManager.STATUS_SUCCESSFUL);
        android.content.pm.ActivityInfo receiver=app.getPackageManager().getReceiverInfo(new android.content.ComponentName(app,UpdateDownloadReceiver.class),0);
        assertEquals("android.permission.SEND_DOWNLOAD_COMPLETED_INTENTS",receiver.permission);assertTrue(receiver.exported);
        UpdateDownloadReceiver listener=new UpdateDownloadReceiver();NotificationManager notices=(NotificationManager)app.getSystemService(Context.NOTIFICATION_SERVICE);
        listener.onReceive(app,new Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE).putExtra(DownloadManager.EXTRA_DOWNLOAD_ID,id+1));assertEquals(0,Shadows.shadowOf(notices).getAllNotifications().size());
        listener.onReceive(app,new Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE).putExtra(DownloadManager.EXTRA_DOWNLOAD_ID,id));
        assertNull("Completion may notify but cannot bring an Activity forward",Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
        assertEquals(1,Shadows.shadowOf(notices).getAllNotifications().size());Notification notice=Shadows.shadowOf(notices).getAllNotifications().get(0);
        assertTrue(Shadows.shadowOf(notice.contentIntent).isActivity());Intent page=Shadows.shadowOf(notice.contentIntent).getSavedIntent();assertEquals(UpdateActivity.class.getName(),page.getComponent().getClassName());assertNull(page.getData());
        listener.onReceive(app,new Intent(DownloadManager.ACTION_NOTIFICATION_CLICKED).putExtra(DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS,new long[]{id+1}));assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
        listener.onReceive(app,new Intent(DownloadManager.ACTION_NOTIFICATION_CLICKED).putExtra(DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS,new long[]{id}));
        assertEquals(UpdateActivity.class.getName(),Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity().getComponent().getClassName());
        updates.cancel();settle();assertEquals(0,Shadows.shadowOf(notices).getAllNotifications().size());
    }
    private long ownedDownload(Context app,int status)throws Exception{
        DownloadManager dm=(DownloadManager)app.getSystemService(Context.DOWNLOAD_SERVICE);long id=dm.enqueue(new DownloadManager.Request(Uri.parse(offer.url)));
        Shadows.shadowOf(Shadows.shadowOf(dm).getRequest(id)).setStatus(status);Shadows.shadowOf(Shadows.shadowOf(dm).getRequest(id)).setBytesSoFar(DATA.length);
        set(updates,"downloadId",id);set(updates,"state",new AppUpdates.Snapshot(AppUpdates.Phase.DOWNLOADING,offer,"系统下载中",0));
        app.getSharedPreferences("app_updates",0).edit().putString("offer",offer.json().toString()).putLong("download_id",id).commit();return id;
    }
    public static final class DownloadFixture extends ContentProvider {
        private final File source;DownloadFixture(File source){this.source=source;}
        @Override public boolean onCreate(){return true;}
        @Override public android.os.ParcelFileDescriptor openFile(Uri uri,String mode)throws java.io.FileNotFoundException{return android.os.ParcelFileDescriptor.open(source,android.os.ParcelFileDescriptor.MODE_READ_ONLY);}
        @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){return null;}
        @Override public String getType(Uri uri){return "application/octet-stream";}
        @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
        @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
        @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    }
    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
}
