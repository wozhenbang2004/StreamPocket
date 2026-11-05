package dev.streampocket;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CompoundButton;

/** Compact track, stable label and a directional thumb stretch on press. */
public final class PocketSwitch extends CompoundButton {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private float position;
    private ValueAnimator movement;
    public PocketSwitch(Context context){
        super(context,null,0);setButtonDrawable(null);setTextSize(14);setTextColor(UiTheme.get(context).ink);
        setClickable(true);setFocusable(true);
        setMinWidth(dp(48));setMinimumWidth(dp(48));setMinHeight(dp(48));setPaddingRelative(0,0,dp(52),0);
        setGravity(android.view.Gravity.START|android.view.Gravity.CENTER_VERTICAL);setBackgroundColor(android.graphics.Color.TRANSPARENT);
    }
    @Override public void setChecked(boolean checked){
        boolean before=isChecked();super.setChecked(checked);if(before==checked)return;
        if(movement!=null)movement.cancel();float end=checked?1:0;
        if(!isLaidOut()||!UiTheme.motionEnabled(getContext())){position=end;invalidate();return;}
        movement=ValueAnimator.ofFloat(position,end);movement.setDuration(180);movement.setInterpolator(UiTheme.easeOut());
        movement.addUpdateListener(a->{position=(float)a.getAnimatedValue();invalidate();});movement.start();
    }
    @Override public void setEnabled(boolean enabled){
        super.setEnabled(enabled);
        if(!enabled){cancelPendingInputEvents();setPressed(false);if(movement!=null)movement.cancel();position=isChecked()?1:0;}
        invalidate();
    }
    @Override public boolean onTouchEvent(MotionEvent event){boolean handled=super.onTouchEvent(event);invalidate();return handled;}
    @Override protected void drawableStateChanged(){super.drawableStateChanged();invalidate();}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);UiTheme.Palette p=UiTheme.get(getContext());boolean rtl=getLayoutDirection()==LAYOUT_DIRECTION_RTL;
        float left=rtl?dp(4):getWidth()-dp(40),right=left+dp(36),top=(getHeight()-dp(22))/2f;
        int alpha=isEnabled()?255:100;
        paint.setColor(isChecked()?p.accent:p.line);paint.setAlpha(alpha);canvas.drawRoundRect(new RectF(left,top,right,top+dp(22)),dp(11),dp(11),paint);
        boolean pressed=isEnabled()&&isPressed();float progress=rtl?1-position:position;
        float width=dp(pressed?21:18),x=left+dp(2)+progress*dp(14)-(pressed?progress*dp(3):0);
        paint.setColor(isChecked()?p.surface:(p.dark?p.ink:p.raised));paint.setAlpha(alpha);canvas.drawRoundRect(new RectF(x,top+dp(2),x+width,top+dp(20)),dp(9),dp(9),paint);
        if(isEnabled()&&isFocused()&&!isInTouchMode()){
            paint.setColor(p.muted);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.5f));
            canvas.drawRoundRect(new RectF(left-dp(3),top-dp(3),right+dp(3),top+dp(25)),dp(14),dp(14),paint);paint.setStyle(Paint.Style.FILL);
        }
        paint.setAlpha(255);
    }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName("android.widget.Switch");info.setCheckable(true);info.setChecked(isChecked());}
    @Override protected void onDetachedFromWindow(){if(movement!=null)movement.cancel();position=isChecked()?1:0;setPressed(false);super.onDetachedFromWindow();}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
