package dev.streampocket;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Looper;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Application lifecycle integration; metadata is fake and neither Activity is MainActivity. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=PocketApp.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class StartupUpdateLifecycleTest {
    public static class FirstActivity extends Activity {}
    public static class SecondActivity extends Activity {}
    private final AtomicInteger requests=new AtomicInteger();
    private AppUpdates updates;
    private ExecutorService worker;

    @Before public void installFakeMetadata()throws Exception{
        Context app=RuntimeEnvironment.getApplication();app.getSharedPreferences("app_updates",0).edit().clear().commit();
        updates=new AppUpdates(app,(url,limit,missing)->{
            assertEquals(UpdateOffer.API,url);requests.incrementAndGet();return null;
        });
        Field instance=AppUpdates.class.getDeclaredField("instance");instance.setAccessible(true);instance.set(null,updates);
        Field executor=AppUpdates.class.getDeclaredField("worker");executor.setAccessible(true);worker=(ExecutorService)executor.get(updates);
    }

    @After public void closeWorker()throws Exception{
        worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));
        Field instance=AppUpdates.class.getDeclaredField("instance");instance.setAccessible(true);instance.set(null,null);
    }

    @Test public void openingAnotherPageBeforeTheDelayStillChecksOnceAndARealReopenChecksAgain()throws Exception{
        try(ActivityController<FirstActivity> first=Robolectric.buildActivity(FirstActivity.class).create().start().resume();
            ActivityController<SecondActivity> second=Robolectric.buildActivity(SecondActivity.class).create()){
            first.pause();second.start().resume();first.stop();
            advanceAndSettle();
            assertEquals("Internal navigation must not cancel the app's startup check",1,requests.get());
            assertEquals(AppUpdates.Phase.NONE,updates.snapshot().phase);
            advanceAndSettle();assertEquals("The visible page must not start a polling loop",1,requests.get());
        }
        try(ActivityController<SecondActivity> reopened=Robolectric.buildActivity(SecondActivity.class).create().start().resume()){
            advanceAndSettle();assertEquals("Reopening on a non-main page still checks",2,requests.get());
        }
    }

    @Test public void leavingBeforeTheDelayCancelsBackgroundWorkButKeepsTheNextLaunchEligible()throws Exception{
        try(ActivityController<FirstActivity> first=Robolectric.buildActivity(FirstActivity.class).create().start().resume()){
            assertEquals(0,requests.get());
        }
        advanceAndSettle();assertEquals("Closed applications must not start the delayed request",0,requests.get());
        try(ActivityController<FirstActivity> reopened=Robolectric.buildActivity(FirstActivity.class).create().start().resume()){
            advanceAndSettle();assertEquals(1,requests.get());
        }
    }

    @Test public void rotationDoesNotCreateANewForegroundSession()throws Exception{
        try(ActivityController<FirstActivity> controller=Robolectric.buildActivity(FirstActivity.class).create().start().resume()){
            advanceAndSettle();assertEquals(1,requests.get());
            FirstActivity previous=controller.get();
            Configuration rotated=new Configuration(previous.getResources().getConfiguration());
            rotated.orientation=rotated.orientation==Configuration.ORIENTATION_LANDSCAPE
                    ?Configuration.ORIENTATION_PORTRAIT:Configuration.ORIENTATION_LANDSCAPE;
            controller.configurationChange(rotated,previous.getResources().getDisplayMetrics());
            assertNotSame("The fixture must actually recreate the Activity",previous,controller.get());
            advanceAndSettle();assertEquals("Configuration recreation is not a new application opening",1,requests.get());
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void mainShowsAnAvailableReleaseHonorsLaterAndStopsPromptingWhenAutomaticIsDisabled()throws Exception{
        Context app=RuntimeEnvironment.getApplication();
        app.getSharedPreferences("config",0).edit().putBoolean("motion_enabled",false).commit();
        JSONObject manifest=new JSONObject().put("schemaVersion",1).put("versionCode",900).put("versionName","9.0-fixture")
                .put("packageName",app.getPackageName()).put("abi","arm64-v8a").put("minSdk",24)
                .put("apkAssetName",UpdateOffer.APK).put("apkSize",4096).put("apkSha256","a".repeat(64))
                .put("signingCertificateSha256","b".repeat(64));
        UpdateOffer offer=new UpdateOffer(manifest,"v9.0","Notification fixture, not an installable release.",
                UpdateOffer.REPO+"/releases/download/v9.0/"+UpdateOffer.APK);
        Field state=AppUpdates.class.getDeclaredField("state");state.setAccessible(true);
        state.set(updates,new AppUpdates.Snapshot(AppUpdates.Phase.AVAILABLE,offer,"发现新版本",0));
        Bundle page=new Bundle();page.putString("tab","settings");
        try(ActivityController<MainActivity> first=Robolectric.buildActivity(MainActivity.class).create(page).start().resume().visible()){
            PocketDialog prompt=updateDialog(first.get());
            assertTrue("The real main page must display the discovered release",prompt!=null&&prompt.isShowing());
            prompt.getButton(PocketDialog.BUTTON_NEGATIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();
            first.pause().resume();Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull("A temporary interruption must not undo Later in the same foreground",updateDialog(first.get()));
        }
        try(ActivityController<MainActivity> reopened=Robolectric.buildActivity(MainActivity.class).create(page).start().resume().visible()){
            PocketDialog prompt=updateDialog(reopened.get());
            assertTrue("The next application opening may remind about that same release",prompt!=null&&prompt.isShowing());
            prompt.getButton(PocketDialog.BUTTON_NEGATIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();
            updates.automatic(false);
        }
        try(ActivityController<MainActivity> disabled=Robolectric.buildActivity(MainActivity.class).create(page).start().resume().visible()){
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull("Disabling automatic checks also suppresses the cached release prompt",updateDialog(disabled.get()));
        }
    }

    private static PocketDialog updateDialog(MainActivity activity)throws Exception{
        Field dialog=MainActivity.class.getDeclaredField("appUpdateDialog");dialog.setAccessible(true);return(PocketDialog)dialog.get(activity);
    }

    private void advanceAndSettle()throws Exception{
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500));
        for(int i=0;i<4;i++){worker.submit(()->{}).get(5,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    }
}
