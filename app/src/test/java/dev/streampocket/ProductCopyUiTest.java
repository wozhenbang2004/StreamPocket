package dev.streampocket;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
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
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;

/** Offline native View fixtures. No website login or video download is simulated as real. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ProductCopyUiTest {
    @Test public void updatePageKeepsImplementationDetailsInsideHelp() throws Exception {
        RuntimeEnvironment.getApplication().getSharedPreferences("app_updates",0).edit().clear().putBoolean("automatic",false).commit();
        Field singleton=AppUpdates.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,null);
        AppUpdates updates=AppUpdates.get(RuntimeEnvironment.getApplication());
        try(ActivityController<UpdateActivity> controller=Robolectric.buildActivity(UpdateActivity.class).create().start().resume()){
            UpdateActivity activity=controller.get();Shadows.shadowOf(Looper.getMainLooper()).idle();
            View decor=activity.getWindow().getDecorView();String text=allText(decor);
            assertEquals(1,count(decor,"检查更新"));assertFalse(text.contains("GitHub"));assertFalse(text.contains("SHA"));assertFalse(text.contains("versionCode"));
            assertFalse(text.contains("复制问题信息"));capture(decor,"update.png");
            find(decor,"更新帮助").performClick();Dialog help=ShadowDialog.getLatestDialog();
            assertNotNull(find(help.getWindow().getDecorView(),"复制问题信息"));help.dismiss();
        } finally {
            ExecutorService worker=(ExecutorService)field(updates,"worker");worker.shutdown();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));singleton.set(null,null);
        }
    }

    @Test public void freshYouTubeAccountOffersLoginAndScopedImportAndRetainsPickerScope() throws Exception {
        RuntimeEnvironment.getApplication().getSharedPreferences("app_updates",0).edit().putBoolean("automatic",false).commit();
        Bundle saved=new Bundle();
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).create().start().resume()){
            MainActivity activity=controller.get();invoke(activity,"showLoginSettings");drain(activity);
            Dialog manager=ShadowDialog.getLatestDialog();View youtube=find(manager.getWindow().getDecorView(),"YouTube");assertNotNull(youtube);
            while(!youtube.isClickable()&&youtube.getParent() instanceof View)youtube=(View)youtube.getParent();assertTrue(youtube.isClickable());youtube.performClick();drain(activity);
            Dialog details=ShadowDialog.getLatestDialog();View decor=details.getWindow().getDecorView();
            assertNotNull(find(decor,"登录YouTube"));assertNotNull(find(decor,"导入登录信息"));
            assertTrue(allText(decor).contains("公开视频通常无需登录"));capture(decor,"youtube-account.png");
            find(decor,"导入登录信息").performClick();PocketDialog help=(PocketDialog)ShadowDialog.getLatestDialog();help.getButton(PocketDialog.BUTTON_POSITIVE).performClick();
            org.robolectric.shadows.ShadowActivity.IntentForResult picker=Shadows.shadowOf(activity).getNextStartedActivityForResult();
            assertNotNull(picker);assertEquals(51,picker.requestCode);assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT,picker.intent.getAction());
            activity.onSaveInstanceState(saved);assertEquals("youtube",saved.getString("cookie_import_platform"));
        }
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).create(saved).start().resume()){
            MainActivity restored=controller.get();assertEquals("youtube",field(restored,"cookieImportPlatform"));
            restored.onActivityResult(51,Activity.RESULT_CANCELED,null);assertNull(field(restored,"cookieImportPlatform"));
            assertTrue(TaskStore.all(restored).isEmpty());
        }
    }

    @Test public void appReleaseNotesKeepChangesAndUsageHintsButExcludeBuildReports(){
        String notes=ReleaseNotes.format("## 更新内容\n- YouTube 账号入口\n### 使用提示\n登录仍受账号权限限制。\n## 安装与验证\nSHA256: fixture\n274 tests passed\n## 源码\nGitHub build report").toString();
        assertTrue(notes.contains("YouTube 账号入口"));assertTrue(notes.contains("账号权限限制"));
        assertFalse(notes.contains("SHA256"));assertFalse(notes.contains("tests passed"));assertFalse(notes.contains("GitHub"));
    }

    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void invoke(Object target,String name)throws Exception{Method m=target.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(target);}
    private static void drain(MainActivity activity)throws Exception{((ExecutorService)field(activity,"io")).submit(()->{}).get(15,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static TextView find(View view,String exact){if(view instanceof TextView&&((TextView)view).getText().toString().equals(exact))return(TextView)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){TextView result=find(group.getChildAt(i),exact);if(result!=null)return result;}}return null;}
    private static int count(View view,String text){int n=view.getVisibility()==View.VISIBLE&&view instanceof TextView&&((TextView)view).getText().toString().equals(text)?1:0;if(view instanceof ViewGroup&&view.getVisibility()==View.VISIBLE){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++)n+=count(g.getChildAt(i),text);}return n;}
    private static String allText(View view){if(view.getVisibility()!=View.VISIBLE)return "";String s=view instanceof TextView?((TextView)view).getText().toString()+"\n":"";if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++)s+=allText(g.getChildAt(i));}return s;}
    private static void capture(View view,String name)throws Exception{
        int width=824,height=1830;ViewGroup.LayoutParams params=view.getLayoutParams();boolean dialog=params!=null&&params.height==ViewGroup.LayoutParams.WRAP_CONTENT;
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,dialog?View.MeasureSpec.AT_MOST:View.MeasureSpec.EXACTLY));height=view.getMeasuredHeight();view.layout(0,0,width,height);
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));File root=new File(System.getProperty("user.dir"));while(root!=null&&!new File(root,"settings.gradle").isFile())root=root.getParentFile();assertNotNull(root);
        File folder=new File(root,"app/build/reports/view-fixtures/product-copy-035");assertTrue(folder.isDirectory()||folder.mkdirs());try(FileOutputStream output=new FileOutputStream(new File(folder,name))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}finally{bitmap.recycle();}
    }
}
