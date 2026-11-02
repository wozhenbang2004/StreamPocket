package dev.streampocket;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.lang.reflect.Field;
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

/** Presentation contracts only; these checks do not download or decode media. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w320dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public final class UiRefinementTest {
    @Test public void neutralSurfacesAndAccentsInBothThemes(){
        Context app=RuntimeEnvironment.getApplication();
        for(String theme:new String[]{"light","dark"}){
            app.getSharedPreferences("config",0).edit().putString("appearance_theme",theme).commit();
            UiTheme.Palette p=UiTheme.get(app);
            for(int color:new int[]{p.bg,p.surface,p.ink,p.muted,p.line,p.accent,p.accentSoft,p.control}){
                assertEquals("Neutral UI must not reintroduce a green tint",Color.red(color),Color.green(color));
                assertEquals(Color.red(color),Color.blue(color));
            }
            assertNotEquals("Semantic errors remain distinct from selection",p.danger,p.accent);
        }
        app.getSharedPreferences("config",0).edit().clear().commit();
    }

    @Test public void buttonPressAndCancelKeepTheHitTargetStationary(){
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup()){
            PocketButton button=PocketButton.of(c.get(),"解析链接",PocketButton.Role.PRIMARY);
            int[] clicks={0};button.setOnClickListener(v->clicks[0]++);c.get().setContentView(button);layout(button,200,48);
            touch(button,MotionEvent.ACTION_DOWN);touch(button,MotionEvent.ACTION_CANCEL);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(0,clicks[0]);assertFalse(button.isPressed());
            assertEquals(0f,button.getTranslationY(),0.001f);assertEquals(1f,button.getScaleX(),0.001f);
            touch(button,MotionEvent.ACTION_DOWN);touch(button,MotionEvent.ACTION_UP);
            // View posts performClick after UP; PAUSED mode must dispatch that callback.
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1,clicks[0]);assertTrue(button.getHeight()>=48);
        }
    }

    @Test public void narrowLargeTextStacksImportActionsWithoutLosingTheDraft() throws Exception {
        Context app=RuntimeEnvironment.getApplication();
        app.getSharedPreferences("config",0).edit().clear().putBoolean("motion_enabled",false).commit();
        RuntimeEnvironment.setFontScale(1.5f);
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class);
        try{
            Bundle saved=new Bundle();saved.putString("tab","new");saved.putString("draft","https://www.bilibili.com/video/BV1nx411u79K");
            MainActivity activity=c.create(saved).start().resume().visible().get();
            Shadows.shadowOf(Looper.getMainLooper()).idle();layout(activity.getWindow().getDecorView(),320,640);
            Button parse=field(activity,"parse",Button.class);
            assertEquals(LinearLayout.VERTICAL,((LinearLayout)parse.getParent()).getOrientation());
            assertTrue("Primary label must remain fully measured",parse.getLayout().getHeight()<=parse.getHeight()-parse.getCompoundPaddingTop()-parse.getCompoundPaddingBottom());
            assertTrue(parse.getWidth()>=240);assertTrue(parse.isEnabled());
            assertEquals(saved.getString("draft"),field(activity,"input",EditText.class).getText().toString());
            assertTrue("Layout must never start a download",TaskStore.all(activity).isEmpty());
        }finally{c.pause().stop().destroy();RuntimeEnvironment.setFontScale(1f);app.getSharedPreferences("config",0).edit().clear().commit();}
    }
    private static <T>T field(Object object,String name,Class<T> type)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(object));}
    private static void layout(View v,int w,int h){v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);}
    private static void touch(View v,int action){MotionEvent e=MotionEvent.obtain(0,20,action,50,24,0);v.dispatchTouchEvent(e);e.recycle();}
}
