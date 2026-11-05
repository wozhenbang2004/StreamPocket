package dev.streampocket;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import java.time.Duration;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w320dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public final class RelayControlsTest {
    @Test public void disablingAHeldButtonCancelsItsPendingClickAndRestoresItsFace(){
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup()){
            PocketButton button=PocketButton.of(c.get(),"解析链接",PocketButton.Role.PRIMARY);int[] clicks={0};
            button.setOnClickListener(v->clicks[0]++);c.get().setContentView(button);layout(button,280,48);
            touch(button,MotionEvent.ACTION_DOWN,100,24);idle(200);
            button.setEnabled(false);
            assertFalse(button.isPressed());assertEquals(1,button.getScaleX(),.001f);assertEquals(1,button.getScaleY(),.001f);assertEquals(0,button.getTranslationY(),.001f);
            touch(button,MotionEvent.ACTION_UP,100,24);idle(200);assertEquals(0,clicks[0]);
            button.setEnabled(true);tap(button,100,24);assertEquals("Re-enabling must not leave the button inert",1,clicks[0]);
            touch(button,MotionEvent.ACTION_DOWN,100,24);idle(200);touch(button,MotionEvent.ACTION_CANCEL,100,24);idle(200);
            assertEquals(1,clicks[0]);assertEquals(1,button.getScaleX(),.001f);assertEquals(1,button.getScaleY(),.001f);
        }
    }
    @Test public void disablingTabsDuringADragRestoresSelectionAndDisablesChildActions(){
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup()){
            c.get().getSharedPreferences("config",0).edit().putBoolean("motion_enabled",false).commit();
            int[] calls={0};PocketTabs tabs=new PocketTabs(c.get(),new String[]{"全部","进行中","已完成","失败"},0,index->calls[0]++);
            c.get().setContentView(tabs);layout(tabs,288,48);
            touch(tabs,MotionEvent.ACTION_DOWN,35,24);touch(tabs,MotionEvent.ACTION_MOVE,115,24);tabs.setEnabled(false);touch(tabs,MotionEvent.ACTION_UP,115,24);
            assertEquals(0,calls[0]);assertTrue(label(tabs,0).isSelected());assertFaceAligned(tabs,0);
            assertFalse(label(tabs,1).isEnabled());assertFalse(label(tabs,1).performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,null));
            assertEquals(0,calls[0]);
            tabs.setEnabled(true);tap(tabs,108,24);assertTrue(label(tabs,1).isSelected());assertEquals(1,calls[0]);assertFaceAligned(tabs,1);
        }
    }
    @Test public void selectionFaceFollowsTheActualRtlLabelBoundsAfterResize(){
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup()){
            c.get().getSharedPreferences("config",0).edit().putBoolean("motion_enabled",false).commit();
            int[] calls={0};PocketTabs tabs=new PocketTabs(c.get(),new String[]{"全部","进行中","已完成","失败"},0,index->calls[0]++);
            tabs.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);c.get().setContentView(tabs);layout(tabs,288,48);
            assertTrue("Logical first is at the visual right",label(tabs,0).getLeft()>label(tabs,3).getLeft());assertFaceAligned(tabs,0);
            tabs.selection(2);assertFaceAligned(tabs,2);layout(tabs,352,48);assertFaceAligned(tabs,2);
            assertEquals("Model-driven selection must not echo back as a user action",0,calls[0]);
            tabs.selection(99);assertTrue(label(tabs,2).isSelected());assertFaceAligned(tabs,2);
        }
    }
    @Test public void keyboardMovesVisuallyInRtlAndSelectingTheSameTabIsIdempotent(){
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup()){
            c.get().getSharedPreferences("config",0).edit().putBoolean("motion_enabled",false).commit();
            int[] calls={0};PocketTabs tabs=new PocketTabs(c.get(),new String[]{"全部","进行中","已完成","失败"},1,index->calls[0]++);
            tabs.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);c.get().setContentView(tabs);layout(tabs,288,48);
            assertTrue(label(tabs,1).requestFocus());key(label(tabs,1),KeyEvent.KEYCODE_DPAD_LEFT);
            assertTrue(label(tabs,2).isSelected());assertTrue(label(tabs,2).hasFocus());assertEquals(1,calls[0]);assertFaceAligned(tabs,2);
            key(label(tabs,2),KeyEvent.KEYCODE_MOVE_HOME);assertTrue(label(tabs,0).isSelected());assertEquals(2,calls[0]);
            key(label(tabs,0),KeyEvent.KEYCODE_MOVE_HOME);assertEquals(2,calls[0]);
        }
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void disabledSwitchDrawsADimmedTrackAndCancelsAHeldToggle(){
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup()){
            PocketSwitch toggle=new PocketSwitch(c.get());int[] changes={0};toggle.setOnCheckedChangeListener((view,checked)->changes[0]++);
            c.get().setContentView(toggle);layout(toggle,280,56);toggle.clearFocus();
            int enabledAlpha=trackAlpha(toggle);
            touch(toggle,MotionEvent.ACTION_DOWN,258,28);toggle.setEnabled(false);touch(toggle,MotionEvent.ACTION_UP,258,28);idle(200);
            assertFalse(toggle.isPressed());assertFalse(toggle.isChecked());assertEquals(0,changes[0]);
            layout(toggle,280,56);int disabledAlpha=trackAlpha(toggle);assertTrue("Disabled paint must actually be translucent",disabledAlpha>0&&disabledAlpha<enabledAlpha);
            toggle.setEnabled(true);tap(toggle,258,28);assertTrue(toggle.isChecked());assertEquals(1,changes[0]);
        }
    }
    @Test public void taskChoiceMarkTogglesOnceAndHonorsCancellationAndDisabledState(){
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup()){
            PocketCheckBox choice=new PocketCheckBox(c.get());int[] changes={0};choice.setOnCheckedChangeListener((view,checked)->changes[0]++);
            c.get().setContentView(choice);layout(choice,56,48);
            tap(choice,24,24);assertTrue(choice.isChecked());assertEquals(1,changes[0]);
            touch(choice,MotionEvent.ACTION_DOWN,24,24);touch(choice,MotionEvent.ACTION_CANCEL,24,24);idle(200);
            assertTrue(choice.isChecked());assertEquals(1,changes[0]);
            choice.setEnabled(false);tap(choice,24,24);assertFalse(choice.performClick());
            assertFalse(choice.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,null));assertEquals(1,changes[0]);
            choice.setEnabled(true);tap(choice,24,24);assertFalse(choice.isChecked());assertEquals(2,changes[0]);
        }
    }
    private static int trackAlpha(PocketSwitch toggle){
        Bitmap image=Bitmap.createBitmap(280,56,Bitmap.Config.ARGB_8888);
        try{toggle.draw(new Canvas(image));return Color.alpha(image.getPixel(272,28));}finally{image.recycle();}
    }
    private static View label(PocketTabs tabs,int index){return ((ViewGroup)tabs.getChildAt(1)).getChildAt(index);}
    private static void assertFaceAligned(PocketTabs tabs,int index){
        View face=tabs.getChildAt(0),label=label(tabs,index);
        assertTrue("Selection must be visible on the current frame",face.getWidth()>0);
        assertEquals((label.getLeft()+label.getRight())/2f,face.getLeft()+face.getTranslationX()+face.getWidth()/2f,1f);
        assertTrue(face.getLeft()>=label.getLeft());assertTrue(face.getRight()<=label.getRight());
    }
    private static void layout(View view,int w,int h){view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h);}
    private static void touch(View view,int action,float x,float y){
        long now=SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(now,now,action,x,y,0);
        try{view.dispatchTouchEvent(event);}finally{event.recycle();}
    }
    private static void tap(View view,float x,float y){touch(view,MotionEvent.ACTION_DOWN,x,y);touch(view,MotionEvent.ACTION_UP,x,y);idle(200);}
    private static void key(View view,int key){assertTrue(view.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,key)));assertTrue(view.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,key)));}
    private static void idle(long millis){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));}
}
