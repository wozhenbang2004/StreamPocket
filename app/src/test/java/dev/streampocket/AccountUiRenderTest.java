package dev.streampocket;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;

/** Account UI fixtures only: no platform login, credentials or network are used. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class AccountUiRenderTest {
    @Test public void accountsDiscloseOfficialLoginAndPreserveLink() throws Exception {
        Bundle state=new Bundle();String draft="https://www.bilibili.com/video/BV1nx411u79K";
        state.putString("draft",draft);state.putString("tab","new");
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class);
        try {
            MainActivity activity=controller.create(state).start().resume().visible().get();
            invoke(activity,"showTab",new Class<?>[]{String.class},"settings");drain(activity);
            assertNotNull(findText(activity.getWindow().getDecorView(),"账号管理"));
            invoke(activity,"showLoginSettings",new Class<?>[0]);drain(activity);
            Dialog manager=ShadowDialog.getLatestDialog();assertTrue(manager.isShowing());
            View content=manager.getWindow().getDecorView();
            for(LoginPlatform platform:LoginPlatform.values())assertNotNull(findText(content,platform.title));
            assertNotNull(findText(content,"从 Cookie 文件导入"));
            assertNotNull("Fresh accounts should expose login directly",findText(content,"登录"));
            capture(content,"013-account-manager-empty");

            View target=findText(content,LoginPlatform.BILIBILI.title);
            while(target!=null&&!target.isClickable())target=target.getParent() instanceof View?(View)target.getParent():null;
            assertNotNull("Platform row must have one usable hit area",target);target.performClick();drain(activity);
            assertFalse("Fresh row should go straight to login without another dialog",manager.isShowing());
            target.performClick();
            ShadowActivity.IntentForResult started=Shadows.shadowOf(activity).getNextStartedActivityForResult();
            assertNotNull(started);assertEquals(54,started.requestCode);
            Intent intent=started.intent;
            assertEquals(LoginActivity.class.getName(),intent.getComponent().getClassName());
            assertEquals(LoginPlatform.BILIBILI.id,intent.getStringExtra("platform"));
            assertNull("Repeated taps must not open a second login WebView",Shadows.shadowOf(activity).getNextStartedActivityForResult());
            activity.onActivityResult(54,Activity.RESULT_CANCELED,null);drain(activity);
            Dialog restored=ShadowDialog.getLatestDialog();assertTrue(restored.isShowing());
            assertNotNull("Cancelling login should restore account management",findText(restored.getWindow().getDecorView(),"账号管理"));
            restored.dismiss();

            // Metadata-only layout fixture, intentionally never written as a real session.
            Class<?> type=Class.forName("dev.streampocket.MainActivity$AccountState");
            Constructor<?> constructor=type.getDeclaredConstructor(LoginPlatform.class,boolean.class,boolean.class,long.class,String.class);
            constructor.setAccessible(true);
            Object fixture=constructor.newInstance(LoginPlatform.X,true,true,1793385000000L,"网页登录");
            invoke(activity,"showAccountDetails",new Class<?>[]{type},fixture);drain(activity);
            Dialog saved=ShadowDialog.getLatestDialog();View savedContent=saved.getWindow().getDecorView();
            assertNotNull(findButton(savedContent,"打开官方登录页"));
            assertNotNull(findButton(savedContent,"清除本机登录信息"));
            assertNotNull(findText(savedContent,"是否仍然有效，以源站实际响应为准。"));
            Switch enabled=findSwitch(savedContent);assertNotNull(enabled);assertTrue(enabled.isChecked());
            capture(savedContent,"013-account-saved-metadata-fixture");saved.dismiss();
            invoke(activity,"showTab",new Class<?>[]{String.class},"new");drain(activity);
            assertEquals("Account navigation must preserve the pending link",draft,field(activity,"input",EditText.class).getText().toString());
            assertTrue("Metadata fixture must not create a download",TaskStore.all(activity).isEmpty());
        } finally { controller.pause().stop().destroy(); }
    }

    private static void drain(MainActivity activity) throws Exception {
        field(activity,"io",ExecutorService.class).submit(()->{}).get(15,TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private static Object invoke(Object object,String name,Class<?>[] types,Object... arguments) throws Exception {
        Method method=object.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(object,arguments);
    }
    private static <T>T field(Object object,String name,Class<T> type) throws Exception {
        Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return type.cast(field.get(object));
    }
    private static TextView findText(View view,String exact) {
        if(view instanceof TextView&&((TextView)view).getText().toString().equals(exact))return (TextView)view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){TextView found=findText(group.getChildAt(i),exact);if(found!=null)return found;}}
        return null;
    }
    private static Button findButton(View view,String exact) {TextView found=findText(view,exact);return found instanceof Button?(Button)found:null;}
    private static Switch findSwitch(View view) {
        if(view instanceof Switch)return (Switch)view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){Switch found=findSwitch(group.getChildAt(i));if(found!=null)return found;}}
        return null;
    }
    private static void capture(View decor,String name) throws Exception {
        float density=decor.getResources().getDisplayMetrics().density;
        int width=Math.round(412*density),height=Math.round(915*density);
        ViewGroup.LayoutParams params=decor.getLayoutParams();
        if(params!=null&&params.width>0)width=Math.min(width,params.width);
        if(params!=null&&params.height>0)height=Math.min(height,params.height);
        decor.measure(View.MeasureSpec.makeMeasureSpec(width,params!=null&&params.width==ViewGroup.LayoutParams.WRAP_CONTENT?View.MeasureSpec.AT_MOST:View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height,params!=null&&params.height==ViewGroup.LayoutParams.WRAP_CONTENT?View.MeasureSpec.AT_MOST:View.MeasureSpec.EXACTLY));
        width=decor.getMeasuredWidth();height=decor.getMeasuredHeight();decor.layout(0,0,width,height);
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.TRANSPARENT);decor.draw(new Canvas(bitmap));
        File output=new File("build/ui-render");if(!output.isDirectory()&&!output.mkdirs())throw new java.io.IOException("Cannot create UI render directory");
        try(FileOutputStream file=new FileOutputStream(new File(output,name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,file));}finally{bitmap.recycle();}
    }
}
