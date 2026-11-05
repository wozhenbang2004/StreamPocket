package dev.streampocket;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.util.function.IntConsumer;

/** Affect UI's tray/leading-edge motion, adapted to the existing Android filter API. */
final class PocketTabs extends FrameLayout {
    private static final PathInterpolator EASE=new PathInterpolator(.25f,.9f,.3f,1f);
    private final View indicator;
    private final Button[] labels;
    private final int[] labelBounds;
    private final IntConsumer changed;
    private ValueAnimator movement;
    private int selected;
    private float downX,downY,startX,dragWidth,faceLeft,faceRight;
    private boolean dragging;
    private boolean geometryReady;
    private int laidOutHeight;

    PocketTabs(Context context,String[] titles,int selected,IntConsumer changed){
        super(context);this.selected=Math.max(0,Math.min(titles.length-1,selected));this.changed=changed;
        UiTheme.Palette p=UiTheme.get(context);
        GradientDrawable track=new GradientDrawable();track.setColor(p.control);track.setCornerRadius(dp(14));setBackground(track);
        indicator=new View(context);GradientDrawable face=new GradientDrawable();face.setColor(p.raised);face.setCornerRadius(dp(10));indicator.setBackground(face);
        indicator.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);UiTheme.floatingSurface(indicator,1.5f);
        addView(indicator,new FrameLayout.LayoutParams(0,dp(40),Gravity.TOP|Gravity.LEFT));
        LinearLayout row=new LinearLayout(context);row.setElevation(dp(2));labels=new Button[titles.length];labelBounds=new int[titles.length*2];
        for(int n=0;n<titles.length;n++){
            final int index=n;Button b=PocketButton.of(context,titles[n],PocketButton.Role.QUIET);
            // Only the selection face moves. Label metrics and positions stay fixed.
            b.setOnTouchListener(null);b.setBackground(labelFocus(p));b.setPadding(0,0,0,0);b.setTextSize(13);
            b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
            b.setOnClickListener(v->select(index,true));b.setOnKeyListener((v,key,event)->navigate(index,key,event));
            labels[n]=b;row.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));
        }
        addView(row,new FrameLayout.LayoutParams(-1,dp(48)));refreshLabels();
        if(labels.length==0)indicator.setVisibility(INVISIBLE);
    }
    void selection(int index){if(index>=0&&index<labels.length&&selected!=index)select(index,false);}
    private void refreshLabels(){
        UiTheme.Palette p=UiTheme.get(getContext());
        for(int n=0;n<labels.length;n++){
            labels[n].setSelected(n==selected);labels[n].setTextColor(n==selected?p.ink:p.muted);
            labels[n].setContentDescription(labels[n].getText()+(n==selected?"，当前筛选":""));
        }
    }
    private void select(int index,boolean notify){
        if(index<0||index>=labels.length||(notify&&!isEnabled()))return;
        boolean different=selected!=index;selected=index;refreshLabels();position(true);
        if(different&&notify&&changed!=null)changed.accept(index);
    }
    private void position(boolean animate){
        if(labels.length==0||labels[selected].getWidth()==0)return;
        stopMovement();float left=targetLeft(selected),right=targetRight(selected);
        if(!animate||!UiTheme.motionEnabled(getContext())||faceRight<=faceLeft){place(left,right);return;}
        final float fromLeft=faceLeft,fromRight=faceRight;
        if(Math.abs(left-fromLeft)<.5f&&Math.abs(right-fromRight)<.5f){place(left,right);return;}
        // The forward edge arrives in 160ms; the trailing edge follows in 220ms.
        final int leftDuration=left>fromLeft?220:160,rightDuration=left>fromLeft?160:220;
        movement=ValueAnimator.ofFloat(0,220);movement.setDuration(220);movement.setInterpolator(new LinearInterpolator());
        movement.addUpdateListener(a->{
            float time=(float)a.getAnimatedValue();
            float l=EASE.getInterpolation(Math.min(1,time/leftDuration)),r=EASE.getInterpolation(Math.min(1,time/rightDuration));
            place(fromLeft+(left-fromLeft)*l,fromRight+(right-fromRight)*r);
        });movement.start();
    }
    private void place(float left,float right){
        faceLeft=left;faceRight=right;
        indicator.layout(Math.round(left),dp(4),Math.round(right),Math.max(dp(4),getHeight()-dp(4)));
    }
    private float targetLeft(int index){Button b=labels[index];return b.getLeft()+Math.min(dp(4),b.getWidth()/2f);}
    private float targetRight(int index){Button b=labels[index];return b.getRight()-Math.min(dp(4),b.getWidth()/2f);}
    private void stopMovement(){if(movement!=null){movement.cancel();movement=null;}}
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
        super.onLayout(changed,l,t,r,b);
        boolean geometryChanged=!geometryReady||laidOutHeight!=getHeight();
        for(int n=0;n<labels.length;n++){
            int left=labels[n].getLeft(),right=labels[n].getRight();
            geometryChanged|=labelBounds[n*2]!=left||labelBounds[n*2+1]!=right;
            labelBounds[n*2]=left;labelBounds[n*2+1]=right;
        }
        geometryReady=true;laidOutHeight=getHeight();
        if(geometryChanged){if(dragging)endDrag();position(false);}
        // Refreshing sibling rows must not cancel the selection that triggered it.
        else if(dragging||(movement!=null&&movement.isStarted()))place(faceLeft,faceRight);
        else position(false);
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){
        super.onSizeChanged(w,h,oldw,oldh);if(w!=oldw||h!=oldh){dragging=false;pressFace(false);}
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        boolean handled=super.dispatchTouchEvent(event);
        if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL)pressFace(false);
        return handled;
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent e){
        if(!isEnabled()||labels.length==0)return false;
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
            downX=e.getX();downY=e.getY();startX=targetLeft(selected);dragWidth=targetRight(selected)-startX;dragging=false;
            if(downX>=targetLeft(selected)&&downX<=targetRight(selected))pressFace(true);
        }
        if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&Math.abs(e.getX()-downX)>ViewConfiguration.get(getContext()).getScaledTouchSlop()&&Math.abs(e.getX()-downX)>Math.abs(e.getY()-downY)){
            dragging=true;stopMovement();pressFace(true);if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);moveIndicator(e.getX());return true;
        }
        return false;
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(!isEnabled()||!dragging)return super.onTouchEvent(e);
        if(e.getActionMasked()==MotionEvent.ACTION_MOVE){moveIndicator(e.getX());return true;}
        if(e.getActionMasked()==MotionEvent.ACTION_UP){
            moveIndicator(e.getX());float center=(faceLeft+faceRight)/2f;int nearest=selected;float distance=Float.MAX_VALUE;
            for(int n=0;n<labels.length;n++){float d=Math.abs(center-(labels[n].getLeft()+labels[n].getRight())/2f);if(d<distance){distance=d;nearest=n;}}
            endDrag();select(nearest,true);return true;
        }
        if(e.getActionMasked()==MotionEvent.ACTION_CANCEL){endDrag();position(true);return true;}
        return true;
    }
    private void moveIndicator(float x){
        float min=Math.min(targetLeft(0),targetLeft(labels.length-1));
        float max=Math.max(targetRight(0),targetRight(labels.length-1))-dragWidth;
        float left=Math.max(min,Math.min(max,startX+x-downX));place(left,left+dragWidth);
    }
    private void endDrag(){dragging=false;pressFace(false);if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);}
    private void pressFace(boolean pressed){
        if(indicator==null)return;
        indicator.animate().cancel();float scale=pressed&&isEnabled()?.97f:1;
        if(UiTheme.motionEnabled(getContext()))indicator.animate().scaleX(scale).scaleY(scale).setDuration(120).setInterpolator(EASE).start();
        else{indicator.setScaleX(scale);indicator.setScaleY(scale);}
    }
    private boolean navigate(int from,int key,KeyEvent event){
        if(!isEnabled()||labels.length==0)return false;int next;
        if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){
            int direction=key==KeyEvent.KEYCODE_DPAD_LEFT?-1:1;if(getLayoutDirection()==LAYOUT_DIRECTION_RTL)direction=-direction;
            next=(from+direction+labels.length)%labels.length;
        }else if(key==KeyEvent.KEYCODE_MOVE_HOME)next=0;else if(key==KeyEvent.KEYCODE_MOVE_END)next=labels.length-1;else return false;
        if(event.getAction()==KeyEvent.ACTION_DOWN){select(next,true);labels[next].requestFocus();}return true;
    }
    @Override public void setEnabled(boolean enabled){
        super.setEnabled(enabled);setAlpha(enabled?1:.5f);if(labels==null)return;
        for(Button label:labels)label.setEnabled(enabled);
        if(!enabled){cancelPendingInputEvents();endDrag();indicator.animate().cancel();indicator.setScaleX(1);indicator.setScaleY(1);position(false);}
    }
    @Override protected void onDetachedFromWindow(){endDrag();stopMovement();indicator.animate().cancel();indicator.setScaleX(1);indicator.setScaleY(1);super.onDetachedFromWindow();}
    private StateListDrawable labelFocus(UiTheme.Palette p){
        GradientDrawable focus=new GradientDrawable();focus.setColor(Color.TRANSPARENT);focus.setCornerRadius(dp(12));focus.setStroke(dp(2),p.muted);
        StateListDrawable states=new StateListDrawable();states.addState(new int[]{android.R.attr.state_focused,android.R.attr.state_enabled},focus);states.addState(new int[]{},new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));return states;
    }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
