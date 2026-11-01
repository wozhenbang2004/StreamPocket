package dev.streampocket;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w320dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public final class PocketComponentsTest {
    @Test public void unstyledFieldAcceptsTouchFocusAndImeTyping(){
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup()){
            PocketEditText field=new PocketEditText(c.get());c.get().setContentView(field);layout(field,280,100);
            assertTrue("Typing must work without the paste button",field.isFocusableInTouchMode());
            assertTrue(field.requestFocus());
            android.view.inputmethod.InputConnection connection=field.onCreateInputConnection(new android.view.inputmethod.EditorInfo());
            assertNotNull(connection);assertTrue(connection.commitText("https://x.com/test/status/123",1));
            assertEquals("https://x.com/test/status/123",field.getText().toString());
        }
    }
    @Test public void draggingTabsCanCancelAndCommitWithoutMovingLabels(){
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup()){
            int[] selected={0},calls={0};PocketTabs tabs=new PocketTabs(c.get(),new String[]{"全部","进行中","已完成","失败"},0,index->{selected[0]=index;calls[0]++;});c.get().setContentView(tabs);layout(tabs,288,48);
            View label=((ViewGroup)tabs.getChildAt(1)).getChildAt(0);int left=label.getLeft();
            touch(tabs,MotionEvent.ACTION_DOWN,35,24);touch(tabs,MotionEvent.ACTION_MOVE,115,24);touch(tabs,MotionEvent.ACTION_CANCEL,115,24);
            assertEquals(0,calls[0]);assertEquals(left,label.getLeft());
            touch(tabs,MotionEvent.ACTION_DOWN,35,24);touch(tabs,MotionEvent.ACTION_MOVE,115,24);touch(tabs,MotionEvent.ACTION_UP,115,24);
            assertEquals(1,selected[0]);assertEquals(1,calls[0]);assertEquals(left,label.getLeft());assertTrue(((ViewGroup)tabs.getChildAt(1)).getChildAt(1).isSelected());
            assertTrue("The selection face must have real bounds on the first frame",tabs.getChildAt(0).getWidth()>0);
        }
    }
    @Test public void sheetLongListKeepsActionsVisibleAndMultiChoiceTogglesOnce(){
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup()){
            Context app=c.get();String[] rows=new String[30];for(int n=0;n<rows.length;n++)rows[n]="较长的视频名称与画质选项 · 第 "+n+" 项";
            boolean[] checked=new boolean[30];int[] callbacks={0};PocketDialog d=new PocketDialog.Builder(app).setTitle("批量选择需要保存的视频")
                    .setMultiChoiceItems(rows,checked,(dialog,at,value)->{assertTrue(value);callbacks[0]++;}).setPositiveButton("加入队列",null).setNegativeButton("返回",null).show();
            View decor=d.getWindow().getDecorView();layout(decor,300,620);Shadows.shadowOf(Looper.getMainLooper()).idle();
            d.getListView().performItemClick(d.getListView().getChildAt(0),0,0);assertTrue(checked[0]);assertEquals(1,callbacks[0]);
            Button action=d.getButton(PocketDialog.BUTTON_POSITIVE);int[] point=new int[2];action.getLocationInWindow(point);
            assertTrue(action.getWidth()>0);assertTrue("The footer must remain within the visible sheet",point[1]+action.getHeight()<=decor.getHeight());
            layout(decor,300,300);action.getLocationInWindow(point);assertTrue("Keyboard/landscape height must keep the footer reachable",point[1]+action.getHeight()<=300);assertTrue(d.getListView().getHeight()>0);d.dismiss();
        }
    }
    @Test public void largeTextSheetKeepsLongActionsAndTitleInsideNarrowScreen(){
        org.robolectric.RuntimeEnvironment.setFontScale(1.5f);
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup()){
            PocketDialog dialog=new PocketDialog.Builder(c.get()).setTitle("确认保存这个视频片段到手机")
                    .setMessage("较长说明文字需要在窄屏下正常换行，用户应能继续阅读并找到底部的取消和确认操作。".repeat(5))
                    .setNegativeButton("暂不保存",null).setPositiveButton("确认保存片段",null).show();
            View decor=dialog.getWindow().getDecorView();layout(decor,300,420);Button action=dialog.getButton(PocketDialog.BUTTON_POSITIVE);int[] point=new int[2];action.getLocationInWindow(point);
            assertTrue(point[1]+action.getHeight()<=420);assertTrue(action.getLayout().getHeight()<=action.getHeight());dialog.dismiss();
        }finally{org.robolectric.RuntimeEnvironment.setFontScale(1f);}
    }
    @Test public void inlineErrorsStayOwnedByFieldAndClearRestoresFixedHeight(){
        Context app=org.robolectric.RuntimeEnvironment.getApplication();PocketEditText field=new PocketEditText(app);field.setLayoutParams(new LinearLayout.LayoutParams(260,48));
        field.setError("结束时间必须晚于开始时间，请调整选区后重试。");assertEquals(84,field.getLayoutParams().height);assertNotNull(field.getError());
        field.setError("仍然不合法");assertEquals(84,field.getLayoutParams().height);field.setError(null);assertEquals(48,field.getLayoutParams().height);assertNull(field.getError());
    }
    @Test public void releaseNotesPresentHeadingsAndBulletsWithoutMarkdownSource(){
        String notes=ReleaseNotes.format("## 更新内容\n\n- **解析**按顺序处理\n### 播放\n- 保留 `FIT`\n[发布说明](https://example.org)").toString();
        assertTrue(notes.contains("•  解析按顺序处理"));assertTrue(notes.contains("播放"));assertFalse(notes.contains("##"));assertFalse(notes.contains("**"));assertFalse(notes.contains("https://"));
    }
    private static void layout(View view,int w,int h){view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h);}
    private static void touch(View view,int action,float x,float y){MotionEvent event=MotionEvent.obtain(0,20,action,x,y,0);view.dispatchTouchEvent(event);event.recycle();}
}
