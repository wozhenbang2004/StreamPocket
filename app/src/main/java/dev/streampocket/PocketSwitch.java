package dev.streampocket;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CompoundButton;

/** Compact track, stable text and a small directional thumb stretch on press. */
public final class PocketSwitch extends CompoundButton {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private float position;
    private ValueAnimator movement;
    public PocketSwitch(Context context){
        super(context,null,0);setButtonDrawable(null);setTextSize(14);setTextColor(UiTheme.get(context).ink);
        setMinWidth(dp(48));setMinimumWidth(dp(48));setMinHeight(dp(48));setPadding(0,0,dp(52),0);
        setGravity(android.view.Gravity.START|android.view.Gravity.CENTER_VERTICAL);setBackgroundColor(android.graphics.Color.TRANSPARENT);
    }
    @Override public void setChecked(boolean checked){
        boolean before=isChecked();super.setChecked(checked);if(before==checked)return;
        if(movement!=null)movement.cancel();float end=checked?1:0;
        if(!isLaidOut()||!UiTheme.motionEnabled(getContext())){position=end;invalidate();return;}
        movement=ValueAnimator.ofFloat(position,end);movement.setDuration(180);movement.setInterpolator(new android.view.animation.DecelerateInterpolator());
        movement.addUpdateListener(a->{position=(float)a.getAnimatedValue();invalidate();});movement.start();
    }
    @Override public boolean onTouchEvent(MotionEvent event){boolean handled=super.onTouchEvent(event);invalidate();return handled;}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);UiTheme.Palette p=UiTheme.get(getContext());
        float right=getWidth()-dp(4),left=right-dp(36),top=(getHeight()-dp(22))/2f;
        paint.setAlpha(isEnabled()?255:100);paint.setColor(isChecked()?p.accent:p.line);canvas.drawRoundRect(new RectF(left,top,right,top+dp(22)),dp(11),dp(11),paint);
        float width=dp(isPressed()?21:18),x=left+dp(2)+position*dp(14);if(isPressed()&&isChecked())x-=dp(3);
        paint.setColor(isChecked()?p.surface:p.bg);canvas.drawRoundRect(new RectF(x,top+dp(2),x+width,top+dp(20)),dp(9),dp(9),paint);paint.setAlpha(255);
    }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName("android.widget.Switch");info.setCheckable(true);info.setChecked(isChecked());}
    @Override protected void onDetachedFromWindow(){if(movement!=null)movement.cancel();super.onDetachedFromWindow();}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
