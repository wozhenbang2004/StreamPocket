package dev.streampocket;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class UpdateActivityTest {
    @After public void closeWorker()throws Exception{AppUpdates u=AppUpdates.get(RuntimeEnvironment.getApplication());ExecutorService worker=(ExecutorService)field(u,"worker");worker.shutdown();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    @Test public void noReleaseShowsHonestStateAndNeverOffersInstallation()throws Exception{
        Context app=RuntimeEnvironment.getApplication();app.getSharedPreferences("app_updates",0).edit().clear().commit();
        AppUpdates updates=AppUpdates.get(app);set(updates,"state",new AppUpdates.Snapshot(AppUpdates.Phase.NONE,null,"暂未发布可安装的正式更新",0));
        try(ActivityController<UpdateActivity> controller=Robolectric.buildActivity(UpdateActivity.class).create().start().resume()){
            UpdateActivity activity=controller.get();Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("检查更新",((Button)field(activity,"primary")).getText().toString());
            assertEquals(View.GONE,((Button)field(activity,"cancel")).getVisibility());
            assertTrue(((TextView)field(activity,"status")).getText().toString().contains("暂未发布"));
            render(activity,"027-update-no-release-native-view.png");
        }
    }
    @Test public void downloadRequiresConfirmationAndProviderCannotExposeOtherPrivateFiles()throws Exception{
        Context app=RuntimeEnvironment.getApplication();app.getSharedPreferences("app_updates",0).edit().clear().putBoolean("automatic",false).commit();
        JSONObject manifest=new JSONObject().put("schemaVersion",1).put("versionCode",900).put("versionName","9.0-fixture").put("packageName",BuildConfig.APPLICATION_ID)
                .put("abi","arm64-v8a").put("minSdk",24).put("apkAssetName",UpdateOffer.APK).put("apkSize",67000000).put("apkSha256","a".repeat(64)).put("signingCertificateSha256","b".repeat(64));
        UpdateOffer offer=new UpdateOffer(manifest,"v9.0","仅测试界面状态；不是已发布更新。",UpdateOffer.REPO+"/releases/download/v9.0/"+UpdateOffer.APK);
        AppUpdates updates=AppUpdates.get(app);set(updates,"state",new AppUpdates.Snapshot(AppUpdates.Phase.AVAILABLE,offer,"发现新版本，下载需你确认",0));
        try(ActivityController<UpdateActivity> controller=Robolectric.buildActivity(UpdateActivity.class).create().start().resume()){
            UpdateActivity activity=controller.get();Shadows.shadowOf(Looper.getMainLooper()).idle();
            ((Button)field(activity,"primary")).performClick();AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);
            assertEquals("No download before consent",AppUpdates.Phase.AVAILABLE,updates.snapshot().phase);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertEquals(AppUpdates.Phase.AVAILABLE,updates.snapshot().phase);
            File only=new File(app.getFilesDir(),"app-updates/ready/update.apk");assertEquals("content",FileProvider.getUriForFile(app,app.getPackageName()+".updates",only).getScheme());
            try{FileProvider.getUriForFile(app,app.getPackageName()+".updates",new File(app.getFilesDir(),"accounts/private-fixture"));fail("Private accounts must never be shared");}catch(IllegalArgumentException expected){}
        }
    }
    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private static void render(UpdateActivity activity,String name)throws Exception{
        View view=activity.getWindow().getDecorView();int width=824,height=1830;
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));File root=new File(System.getProperty("user.dir"));while(root!=null&&!new File(root,"settings.gradle").isFile())root=root.getParentFile();
        if(root==null)throw new java.io.IOException("Project root unavailable");File dir=new File(root,"docs/evidence/028/regression/027-update");dir.mkdirs();
        try(FileOutputStream output=new FileOutputStream(new File(dir,name))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}finally{bitmap.recycle();}
    }
}
