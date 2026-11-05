package dev.streampocket;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.Button;

/** Android adaptation of the Affect UI button with Relay's flat, neutral surfaces. */
@android.annotation.SuppressLint("AppCompatCustomView")
public final class PocketButton extends Button {
    public enum Role { PRIMARY, SECONDARY, QUIET, DANGER }
    public PocketButton(Context context) { this(context, Role.SECONDARY); }
    public PocketButton(Context context, Role role) { this(context,role,UiTheme.get(context)); }
    private PocketButton(Context context, Role role,UiTheme.Palette p) {
        super(context, null, 0);
        setAllCaps(false);setTextSize(14);setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        setMinWidth(0);setMinimumWidth(0);setMinHeight(dp(48));setMinimumHeight(dp(48));
        setPadding(dp(role==Role.QUIET?8:16),0,dp(role==Role.QUIET?8:16),0);setGravity(android.view.Gravity.CENTER);
        setStateListAnimator(null);setElevation(0);setTranslationZ(0);
        int fill=role==Role.PRIMARY?p.ink:role==Role.QUIET?Color.TRANSPARENT:p.control;
        int ink=role==Role.PRIMARY?p.bg:role==Role.DANGER?p.danger:p.ink;
        int pressed=role==Role.PRIMARY?blend(fill,p.surface,.14f):role==Role.QUIET?p.control:blend(fill,p.ink,.06f);
        setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled},{}},new int[]{p.muted,ink}));
        StateListDrawable states=new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled},surface(role==Role.QUIET?Color.TRANSPARENT:p.control,0));
        states.addState(new int[]{android.R.attr.state_pressed},surface(pressed,0));
        // A keyboard focus cue is transient; no role carries a permanent decorative stroke.
        states.addState(new int[]{android.R.attr.state_focused},surface(fill,p.muted));
        states.addState(new int[]{},surface(fill,0));setBackground(states);
        UiTheme.press(this);
    }
    public static PocketButton of(Context context,String label,Role role){PocketButton b=new PocketButton(context,role);b.setText(label);return b;}
    public static PocketButton dark(Context context,String label,Role role){PocketButton b=new PocketButton(context,role,UiTheme.darkPalette());b.setText(label);return b;}
    @Override public void setEnabled(boolean enabled){
        super.setEnabled(enabled);
        if(!enabled){cancelPendingInputEvents();setPressed(false);animate().cancel();setScaleX(1);setScaleY(1);setTranslationY(0);}
    }
    private GradientDrawable surface(int fill,int focus){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(12));if(focus!=0)d.setStroke(dp(2),focus);return d;}
    private static int blend(int a,int b,float t){return Color.rgb((int)(Color.red(a)*(1-t)+Color.red(b)*t),(int)(Color.green(a)*(1-t)+Color.green(b)*t),(int)(Color.blue(a)*(1-t)+Color.blue(b)*t));}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
