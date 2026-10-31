package dev.streampocket;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import java.util.Locale;

/** A local-file range, in original milliseconds. Moving a handle never writes media. */
public final class ClipRangeView extends View {
    public static final int START=1, END=2;
    public interface Listener { void onRangeChanged(long startMs,long endMs,int handle,boolean tracking); }
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect=new RectF();
    private final AccessibilityManager accessibility;
    private final UiTheme.Palette palette;
    private long durationMs,startMs,endMs,windowStartMs,windowEndMs,downStartMs,downEndMs;
    private final Bitmap[] frames=new Bitmap[7];
    private Runnable windowListener;
    private int active=START,accessibilityFocus=-1,hovered=-1;
    private boolean dragging;
    private Listener listener;

    public ClipRangeView(Context context) { this(context,null); }
    public ClipRangeView(Context context,AttributeSet attrs) {
        super(context,attrs); palette=UiTheme.get(context);
        accessibility=(AccessibilityManager)context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        setFocusable(true); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription("片段范围，拖动左右手柄；也可展开精确时间输入");
        setMinimumHeight(dp(96));
    }
    public void setListener(Listener value) { listener=value; }
    public long getStartMs() { return startMs; }
    public long getEndMs() { return endMs; }
    public long getDurationMs() { return durationMs; }
    public void setRange(long duration,long start,long end) {
        long previous=durationMs;durationMs=Math.max(0,duration);
        if(previous!=durationMs||windowEndMs<=windowStartMs){windowStartMs=0;windowEndMs=durationMs;}
        startMs=Math.max(0,Math.min(start,Math.max(0,durationMs-1)));
        endMs=durationMs==0?0:Math.max(startMs+1,Math.min(end,durationMs)); invalidate();
    }
    long getWindowStartMs(){return windowStartMs;}
    long getWindowEndMs(){return windowEndMs;}
    void setWindowListener(Runnable listener){windowListener=listener;}
    void setFrame(int index,Bitmap bitmap){if(index>=0&&index<frames.length){frames[index]=bitmap;invalidate();}}
    void zoomToSelection(){long length=endMs-startMs,pad=Math.max(1500,length/2);setWindow(Math.max(0,startMs-pad),Math.min(durationMs,endMs+pad));}
    void showFullTimeline(){setWindow(0,durationMs);}
    private void setWindow(long start,long end){windowStartMs=start;windowEndMs=Math.max(start+1,end);java.util.Arrays.fill(frames,null);invalidate();if(windowListener!=null)windowListener.run();}
    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        setMeasuredDimension(resolveSize(dp(280),widthSpec),resolveSize(dp(96),heightSpec));
    }
    private int dp(float value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private float left() { return dp(24); }
    private float right() { return Math.max(left()+1,getWidth()-dp(24)); }
    private float x(long value) { return (float)(left()+(right()-left())*(windowEndMs>windowStartMs?(double)(value-windowStartMs)/(windowEndMs-windowStartMs):0f)); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); float top=dp(20),bottom=dp(62),from=Math.max(left(),x(startMs)),to=Math.min(right(),x(endMs));
        paint.setStyle(Paint.Style.FILL); paint.setColor(palette.control);
        rect.set(left(),top,right(),bottom); canvas.drawRoundRect(rect,dp(8),dp(8),paint);
                float tile=(right()-left())/frames.length;
        for(int i=0;i<frames.length;i++)if(frames[i]!=null&&!frames[i].isRecycled()){
            Bitmap frame=frames[i];float scale=Math.max(tile/frame.getWidth(),(bottom-top)/frame.getHeight()),width=frame.getWidth()*scale,height=frame.getHeight()*scale;
            float center=left()+tile*(i+.5f);canvas.save();canvas.clipRect(left()+tile*i,top,left()+tile*(i+1),bottom);
            rect.set(center-width/2,(top+bottom-height)/2,center+width/2,(top+bottom+height)/2);paint.setAlpha(255);canvas.drawBitmap(frame,null,rect,paint);canvas.restore();
        }
        paint.setColor(palette.dark?0xA00A0C0F:0xA0F6F7F5);canvas.drawRect(left(),top,from,bottom,paint);canvas.drawRect(to,top,right(),bottom,paint);
        paint.setColor(palette.accent); canvas.drawRect(from,top,to,top+dp(2),paint); canvas.drawRect(from,bottom-dp(2),to,bottom,paint);
        drawHandle(canvas,from,START,top,bottom); drawHandle(canvas,to,END,top,bottom);
        paint.setColor(palette.muted); paint.setTextSize(11*getResources().getDisplayMetrics().scaledDensity); paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(shortTime(windowStartMs),left(),dp(84),paint); paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(shortTime(windowEndMs),right(),dp(84),paint); paint.setTextAlign(Paint.Align.LEFT);
    }
    private void drawHandle(Canvas canvas,float center,int handle,float top,float bottom) {
        paint.setColor(palette.accent); rect.set(center-dp(8),top-dp(2),center+dp(8),bottom+dp(2));
        canvas.drawRoundRect(rect,dp(5),dp(5),paint);
        paint.setColor(palette.dark?0xFF102720:0xFFFFFFFF); rect.set(center-dp(1),top+dp(11),center+dp(1),bottom-dp(11));
        canvas.drawRoundRect(rect,dp(1),dp(1),paint);
        if ((isFocused()&&active==handle)||accessibilityFocus==handle) {
            paint.setColor(palette.accent); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2));
            rect.set(center-dp(12),top-dp(6),center+dp(12),bottom+dp(6)); canvas.drawRoundRect(rect,dp(8),dp(8),paint); paint.setStyle(Paint.Style.FILL);
        }
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()||durationMs<=0) return false;
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                active=nearest(event.getX()); dragging=true;downStartMs=startMs;downEndMs=endMs;requestFocus();
                if (getParent()!=null) getParent().requestDisallowInterceptTouchEvent(true);
                updateTouch(event.getX(),true); return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return false; updateTouch(event.getX(),true); return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) return false;
                if (event.getActionMasked()==MotionEvent.ACTION_UP) updateTouch(event.getX(),true);
                else{startMs=downStartMs;endMs=downEndMs;invalidate();}
                dragging=false; if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
                if(listener!=null)listener.onRangeChanged(startMs,endMs,active,false);
                if(event.getActionMasked()==MotionEvent.ACTION_UP)performClick();
                notifyHandle(active,AccessibilityEvent.TYPE_VIEW_SELECTED); return true;
            default:return super.onTouchEvent(event);
        }
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    private int nearest(float point) { return Math.abs(point-x(startMs))<=Math.abs(point-x(endMs))?START:END; }
    private void updateTouch(float point,boolean tracking) {
        double ratio=Math.max(0,Math.min(1,(point-left())/(right()-left())));
        long value=windowStartMs+Math.round(ratio*(windowEndMs-windowStartMs));
        // The slider is coarse selection; the disclosed fields retain exact millisecond input.
        if(value>0&&value<durationMs)value=Math.round(value/100d)*100;
        change(active,value,tracking);
    }
    private void change(int handle,long value,boolean tracking) {
        active=handle;
        if(handle==START)startMs=Math.max(0,Math.min(value,endMs-1));
        else endMs=Math.max(startMs+1,Math.min(value,durationMs));
        invalidate(); if(listener!=null)listener.onRangeChanged(startMs,endMs,handle,tracking);
    }
    @Override public boolean onKeyDown(int keyCode,KeyEvent event) {
        if (!isEnabled()||durationMs<=0) return super.onKeyDown(keyCode,event);
        if(keyCode==KeyEvent.KEYCODE_DPAD_UP||keyCode==KeyEvent.KEYCODE_DPAD_DOWN||keyCode==KeyEvent.KEYCODE_SPACE) {
            active=active==START?END:START; invalidate(); notifyHandle(active,AccessibilityEvent.TYPE_VIEW_SELECTED); return true;
        }
        if(keyCode==KeyEvent.KEYCODE_TAB&&((!event.isShiftPressed()&&active==START)||(event.isShiftPressed()&&active==END))) { active=active==START?END:START;invalidate();return true; }
        if(keyCode==KeyEvent.KEYCODE_DPAD_LEFT||keyCode==KeyEvent.KEYCODE_DPAD_RIGHT) {
            change(active,(active==START?startMs:endMs)+(keyCode==KeyEvent.KEYCODE_DPAD_LEFT?-step():step()),false);
            notifyHandle(active,AccessibilityEvent.TYPE_VIEW_SELECTED); return true;
        }
        return super.onKeyDown(keyCode,event);
    }
    private long step() { return Math.max(100,Math.min(1000,durationMs/100)); }
    @Override public boolean dispatchHoverEvent(MotionEvent event) {
        if(accessibility!=null&&accessibility.isTouchExplorationEnabled()) {
            int next=event.getActionMasked()==MotionEvent.ACTION_HOVER_EXIT?-1:nearest(event.getX());
            if(next!=hovered) { if(hovered!=-1)notifyHandle(hovered,AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);hovered=next;if(next!=-1)notifyHandle(next,AccessibilityEvent.TYPE_VIEW_HOVER_ENTER); }
            return true;
        }
        return super.dispatchHoverEvent(event);
    }
    private String label(int handle) { return (handle==START?"起点 ":"终点 ")+EditorActivity.time(handle==START?startMs:endMs); }
    private void notifyHandle(int handle,int type) {
        if(accessibility==null||!accessibility.isEnabled()||getParent()==null)return;
        AccessibilityEvent event=AccessibilityEvent.obtain(type);event.setSource(this,handle);event.setPackageName(getContext().getPackageName());event.setClassName("android.widget.SeekBar");event.getText().add(label(handle));getParent().requestSendAccessibilityEvent(this,event);
    }
    private AccessibilityNodeInfo hostNode() {
        AccessibilityNodeInfo node=AccessibilityNodeInfo.obtain();super.onInitializeAccessibilityNodeInfo(node);node.setSource(this);node.addChild(this,START);node.addChild(this,END);return node;
    }
    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() { return provider; }
    private final AccessibilityNodeProvider provider=new AccessibilityNodeProvider() {
        @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
            if(id==HOST_VIEW_ID)return hostNode();if(id!=START&&id!=END)return null;
            AccessibilityNodeInfo node=AccessibilityNodeInfo.obtain();node.setSource(ClipRangeView.this,id);node.setParent(ClipRangeView.this);node.setPackageName(getContext().getPackageName());node.setClassName("android.widget.SeekBar");
            node.setContentDescription(label(id));node.setEnabled(isEnabled()&&durationMs>0);node.setFocusable(true);node.setFocused(isFocused()&&active==id);node.setAccessibilityFocused(accessibilityFocus==id);node.setVisibleToUser(isShown());
            int center=Math.round(x(id==START?startMs:endMs));Rect bounds=new Rect(Math.max(0,center-dp(24)),dp(16),Math.min(getWidth(),center+dp(24)),dp(64));node.setBoundsInParent(bounds);
            int[] location=new int[2];getLocationOnScreen(location);bounds.offset(location[0],location[1]);node.setBoundsInScreen(bounds);
            float min=id==START?0:(startMs+1)/1000f,max=id==START?Math.max(0,endMs-1)/1000f:durationMs/1000f;
            node.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,min,max,(id==START?startMs:endMs)/1000f));
            node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);node.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);node.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);node.addAction(AccessibilityNodeInfo.ACTION_CLICK);
            node.addAction(accessibilityFocus==id?AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);return node;
        }
        @Override public AccessibilityNodeInfo findFocus(int focus) { return focus==AccessibilityNodeInfo.FOCUS_ACCESSIBILITY?createAccessibilityNodeInfo(accessibilityFocus):isFocused()?createAccessibilityNodeInfo(active):null; }
        @Override public boolean performAction(int id,int action,Bundle arguments) {
            if(id==HOST_VIEW_ID)return ClipRangeView.this.performAccessibilityAction(action,arguments);
            if((id!=START&&id!=END)||!isEnabled()||durationMs<=0)return false;
            if(action==AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS){accessibilityFocus=id;invalidate();notifyHandle(id,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);return true;}
            if(action==AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS){accessibilityFocus=-1;invalidate();notifyHandle(id,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);return true;}
            if(action==AccessibilityNodeInfo.ACTION_CLICK){active=id;requestFocus();invalidate();return true;}
            long value=id==START?startMs:endMs;
            if(action==AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()) {
                if(arguments==null||!arguments.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE))return false;
                float seconds=arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE);if(Float.isNaN(seconds)||Float.isInfinite(seconds))return false;value=Math.round(seconds*1000d);
            } else if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)value+=step();
            else if(action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)value-=step();else return false;
            change(id,value,false);notifyHandle(id,AccessibilityEvent.TYPE_VIEW_SELECTED);return true;
        }
    };
    private static String shortTime(long value) { long seconds=Math.max(0,value)/1000;return String.format(Locale.ROOT,"%02d:%02d",seconds/60,seconds%60); }
}
