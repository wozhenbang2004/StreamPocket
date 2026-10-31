package dev.streampocket;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.Button;

/** Application-owned button surfaces; no platform button skin or elevation. */
// Framework Activity/theme host; every visual state is owned here, with no AppCompat widget contract.
@android.annotation.SuppressLint("AppCompatCustomView")
public final class PocketButton extends Button {
    public enum Role { PRIMARY, SECONDARY, QUIET, DANGER }
    public PocketButton(Context context) { this(context, Role.SECONDARY); }
    public PocketButton(Context context, Role role) { this(context,role,UiTheme.get(context)); }
    private PocketButton(Context context, Role role,UiTheme.Palette p) {
        super(context, null, 0);
        setAllCaps(false);setTextSize(14);
        setTypeface(Typeface.DEFAULT,role==Role.PRIMARY?Typeface.BOLD:Typeface.NORMAL);
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
        UiTheme.press(this);
    }
    public static PocketButton of(Context context,String label,Role role){PocketButton b=new PocketButton(context,role);b.setText(label);return b;}
    public static PocketButton dark(Context context,String label,Role role){PocketButton b=new PocketButton(context,role,UiTheme.darkPalette());b.setText(label);return b;}
    private GradientDrawable surface(int fill,int line){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(12));if(line!=0)d.setStroke(dp(1),line);return d;}
    private static int blend(int a,int b,float t){return Color.rgb((int)(Color.red(a)*(1-t)+Color.red(b)*t),(int)(Color.green(a)*(1-t)+Color.green(b)*t),(int)(Color.blue(a)*(1-t)+Color.blue(b)*t));}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
