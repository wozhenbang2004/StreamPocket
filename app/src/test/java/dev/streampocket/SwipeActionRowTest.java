package dev.streampocket;
import android.app.Activity;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public final class SwipeActionRowTest {
    @Test public void swipingOnlyRevealsAndExplicitActionTapExecutes(){
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();a.getSharedPreferences("config",0).edit().putBoolean("motion_enabled",false).commit();
        Button front=new Button(a),action=new Button(a);AtomicInteger calls=new AtomicInteger();action.setOnClickListener(v->calls.incrementAndGet());
        SwipeActionRow row=new SwipeActionRow(a,front,action);a.setContentView(row);layout(row);
        touch(row,0,250,30);touch(row,2,80,30);touch(row,1,80,30);
        assertTrue(row.isRevealed());assertEquals(0,calls.get());touch(row,0,375,30);touch(row,1,375,30);org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals("Only tapping the exposed action executes it",1,calls.get());
        row.close(false);assertFalse(row.isRevealed());assertEquals(0f,front.getTranslationX(),.01f);a.finish();
    }
    @Test public void cancelAndVerticalScrollDoNotLeaveRevealedActionsOrExecuteThem(){
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();a.getSharedPreferences("config",0).edit().putBoolean("motion_enabled",false).commit();
        Button front=new Button(a),action=new Button(a);AtomicInteger calls=new AtomicInteger();action.setOnClickListener(v->calls.incrementAndGet());
        SwipeActionRow row=new SwipeActionRow(a,front,action);FrameLayout parent=new FrameLayout(a);parent.addView(row);a.setContentView(parent);layout(row);
        touch(row,0,250,30);touch(row,2,100,30);touch(row,3,100,30);assertFalse(row.isRevealed());
        touch(row,0,250,30);touch(row,2,240,90);touch(row,1,240,90);assertFalse(row.isRevealed());assertEquals(0,calls.get());a.finish();
    }
    private static void layout(View v){v.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(120,View.MeasureSpec.EXACTLY));v.layout(0,0,400,120);}
    private static void touch(View v,int action,float x,float y){long t=SystemClock.uptimeMillis();MotionEvent e=MotionEvent.obtain(t,t,action,x,y,0);v.dispatchTouchEvent(e);e.recycle();}
}
