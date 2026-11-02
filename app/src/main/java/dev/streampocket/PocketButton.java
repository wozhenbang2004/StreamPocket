package dev.streampocket;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.Button;

/** Application-owned surfaces and state elevation; the touch target stays stationary. */
// Framework Activity/theme host; every visual state is owned here, with no AppCompat widget contract.
@android.annotation.SuppressLint("AppCompatCustomView")
public final class PocketButton extends Button {
    public enum Role { PRIMARY, SECONDARY, QUIET, DANGER }
    public PocketButton(Context context) { this(context, Role.SECONDARY); }
    public PocketButton(Context context, Role role) { this(context,role,UiTheme.get(context)); }
    private PocketButton(Context context, Role role,UiTheme.Palette p) {
        super(context, null, 0);
        setAllCaps(false);setTextSize(14);
        setTypeface(Typeface.create(role==Role.PRIMARY?"sans-serif-medium":"sans-serif",Typeface.NORMAL));
        setMinWidth(0);setMinimumWidth(0);setMinHeight(dp(48));setMinimumHeight(dp(48));
        setPadding(dp(role==Role.QUIET?8:16),0,dp(role==Role.QUIET?8:16),0);setGravity(android.view.Gravity.CENTER);
        setStateListAnimator(null);setElevation(0);setTranslationZ(0);
        int fill=role==Role.PRIMARY?p.ink:role==Role.DANGER?p.danger:role==Role.QUIET?Color.TRANSPARENT:p.surface;
        int ink=role==Role.PRIMARY?p.bg:role==Role.DANGER?Color.WHITE:p.ink;
        setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled},{}},new int[]{p.muted,ink}));
        StateListDrawable states=new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled},surface(role==Role.QUIET?Color.TRANSPARENT:p.control,0));
        states.addState(new int[]{android.R.attr.state_pressed},surface(role==Role.PRIMARY?blend(fill,p.surface,.14f):p.control,role==Role.SECONDARY?p.line:0));
        states.addState(new int[]{android.R.attr.state_focused},surface(fill,role==Role.QUIET?p.accent:p.line));
        states.addState(new int[]{},surface(fill,role==Role.SECONDARY?p.line:0));setBackground(states);
        // Native state animation cancels cleanly on release/cancel; only the shadow moves.
        // Reference: BoardUI primary/secondary surface roles; Android implementation, not copied TSX.
        if(role==Role.PRIMARY||role==Role.SECONDARY){
            float resting=dp(role==Role.PRIMARY?2:1);
            android.animation.StateListAnimator elevation=new android.animation.StateListAnimator();
            android.animation.ObjectAnimator disabled=android.animation.ObjectAnimator.ofFloat(this,"elevation",0f);disabled.setDuration(0);
            android.animation.ObjectAnimator pressed=android.animation.ObjectAnimator.ofFloat(this,"elevation",0f);pressed.setDuration(UiTheme.motionEnabled(context)?80:0);
            android.animation.ObjectAnimator released=android.animation.ObjectAnimator.ofFloat(this,"elevation",resting);released.setDuration(UiTheme.motionEnabled(context)?150:0);
            elevation.addState(new int[]{-android.R.attr.state_enabled},disabled);
            elevation.addState(new int[]{android.R.attr.state_pressed},pressed);
            elevation.addState(new int[]{},released);setStateListAnimator(elevation);
            if(android.os.Build.VERSION.SDK_INT>=28){setOutlineAmbientShadowColor(0x20000000);setOutlineSpotShadowColor(0x30000000);}
        }
    }
    public static PocketButton of(Context context,String label,Role role){PocketButton b=new PocketButton(context,role);b.setText(label);return b;}
    public static PocketButton dark(Context context,String label,Role role){PocketButton b=new PocketButton(context,role,UiTheme.darkPalette());b.setText(label);return b;}
    private GradientDrawable surface(int fill,int line){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(10));if(line!=0)d.setStroke(dp(1),line);return d;}
    private static int blend(int a,int b,float t){return Color.rgb((int)(Color.red(a)*(1-t)+Color.red(b)*t),(int)(Color.green(a)*(1-t)+Color.green(b)*t),(int)(Color.blue(a)*(1-t)+Color.blue(b)*t));}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
