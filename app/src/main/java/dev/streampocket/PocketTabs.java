package dev.streampocket;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.util.function.IntConsumer;

/** A single moving selection surface; labels and their touch targets stay still. */
final class PocketTabs extends FrameLayout {
    private final View indicator;private final Button[] labels;private final IntConsumer changed;
    private int selected;private float downX,downY,startX;private boolean dragging;
    PocketTabs(Context context,String[] titles,int selected,IntConsumer changed){
        super(context);this.selected=selected;this.changed=changed;UiTheme.Palette p=UiTheme.get(context);
        GradientDrawable track=new GradientDrawable();track.setColor(p.control);track.setCornerRadius(dp(14));setBackground(track);
        indicator=new View(context);GradientDrawable face=new GradientDrawable();face.setColor(p.surface);face.setCornerRadius(dp(10));face.setStroke(dp(1),p.line);indicator.setBackground(face);addView(indicator,new FrameLayout.LayoutParams(0,dp(40),Gravity.TOP|Gravity.START));
        LinearLayout row=new LinearLayout(context);labels=new Button[titles.length];for(int n=0;n<titles.length;n++){final int index=n;Button b=PocketButton.of(context,titles[n],PocketButton.Role.QUIET);b.setBackgroundColor(android.graphics.Color.TRANSPARENT);b.setPadding(0,0,0,0);b.setTextSize(13);b.setOnClickListener(v->select(index,true));labels[n]=b;row.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));}addView(row,new FrameLayout.LayoutParams(-1,dp(48)));refreshLabels();
    }
    void selection(int index){if(selected!=index)select(index,false);}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();post(()->position(true));}
    private void refreshLabels(){UiTheme.Palette p=UiTheme.get(getContext());for(int n=0;n<labels.length;n++){labels[n].setSelected(n==selected);labels[n].setTextColor(n==selected?p.ink:p.muted);labels[n].setTypeface(null,n==selected?android.graphics.Typeface.BOLD:android.graphics.Typeface.NORMAL);labels[n].setContentDescription(labels[n].getText()+(n==selected?"，当前筛选":""));}}
    private void select(int index,boolean notify){boolean different=selected!=index;selected=index;refreshLabels();position(true);if(different&&notify)changed.accept(index);}
    private void position(boolean animate){if(getWidth()<=0||labels.length==0)return;float segment=getWidth()/(float)labels.length,target=selected*segment;indicator.animate().cancel();if(animate&&UiTheme.motionEnabled(getContext()))indicator.animate().translationX(target).setDuration(200).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();else indicator.setTranslationX(target);}
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){super.onLayout(changed,l,t,r,b);int width=Math.max(0,Math.round(getWidth()/(float)labels.length)-dp(8));indicator.layout(dp(4),dp(4),dp(4)+width,getHeight()-dp(4));}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);position(false);}
    @Override public boolean onInterceptTouchEvent(MotionEvent e){
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){downX=e.getX();downY=e.getY();startX=selected*getWidth()/(float)labels.length;dragging=false;}
        if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&Math.abs(e.getX()-downX)>ViewConfiguration.get(getContext()).getScaledTouchSlop()&&Math.abs(e.getX()-downX)>Math.abs(e.getY()-downY)){dragging=true;indicator.animate().cancel();moveIndicator(e.getX());return true;}return false;
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(!dragging)return super.onTouchEvent(e);float span=getWidth()/(float)labels.length;
        if(e.getActionMasked()==MotionEvent.ACTION_MOVE){moveIndicator(e.getX());return true;}
        if(e.getActionMasked()==MotionEvent.ACTION_UP){moveIndicator(e.getX());dragging=false;select(Math.max(0,Math.min(labels.length-1,Math.round(indicator.getTranslationX()/span))),true);return true;}
        if(e.getActionMasked()==MotionEvent.ACTION_CANCEL){dragging=false;position(true);return true;}return true;
    }
    private void moveIndicator(float x){float span=getWidth()/(float)labels.length;indicator.setTranslationX(Math.max(0,Math.min((labels.length-1)*span,startX+x-downX)));}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
