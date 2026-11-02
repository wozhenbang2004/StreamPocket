package dev.streampocket;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
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

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class StorageSettingsUiTest {
    @Test public void folderPickerIsExplicitAndCancellationPreservesTheCurrentLocation() throws Exception {
        Context app=RuntimeEnvironment.getApplication();app.getSharedPreferences("config",0).edit().clear().putBoolean("app_update_auto_check",false).putBoolean("motion_enabled",false).commit();DownloadLocation.reset(app);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible()){
            MainActivity a=controller.get();Method settings=MainActivity.class.getDeclaredMethod("showStorageSettings");settings.setAccessible(true);settings.invoke(a);
            PocketDialog panel=(PocketDialog)ShadowDialog.getLatestDialog();View content=panel.getWindow().getDecorView();
            assertNotNull(find(content,"一键剪切已有下载"));assertNotNull(find(content,DownloadLocation.label(app)));capture(content);
            find(content,"更改保存位置").performClick();org.robolectric.shadows.ShadowActivity.IntentForResult picker=Shadows.shadowOf(a).getNextStartedActivityForResult();
            assertNotNull(picker);assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE,picker.intent.getAction());assertEquals(55,picker.requestCode);
            assertTrue((picker.intent.getFlags()&Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)!=0);assertTrue((picker.intent.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=0);
            assertNull(DownloadLocation.tree(app));a.onActivityResult(55,Activity.RESULT_CANCELED,null);assertNull(DownloadLocation.tree(app));assertTrue(TaskStore.all(a).isEmpty());
        }
    }

    private static TextView find(View root,String value){if(root instanceof TextView&&value.contentEquals(((TextView)root).getText()))return(TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView child=find(((ViewGroup)root).getChildAt(i),value);if(child!=null)return child;}return null;}
    private static void capture(View view) throws Exception {Shadows.shadowOf(Looper.getMainLooper()).idle();int w=view.getResources().getDisplayMetrics().widthPixels,h=view.getResources().getDisplayMetrics().heightPixels;view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.AT_MOST));view.layout(0,0,w,view.getMeasuredHeight());Bitmap bitmap=Bitmap.createBitmap(w,view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));File dir=new File("build/reports/view-fixtures/041-storage");dir.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(dir,"storage-settings.png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}bitmap.recycle();}
}
