package dev.streampocket;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/** One palette and bounded, interruptible motion across the native application. */
public final class UiTheme {
    private UiTheme() {}
    public static final class Palette {
        public final int bg,surface,ink,muted,line,accent,accentSoft,control,danger;
        public final boolean dark;
        private Palette(boolean night) {
            dark=night;
            bg=night?0xFF0F1215:0xFFF5F6F8;
            surface=night?0xFF1B2024:0xFFFFFFFF;
            ink=night?0xFFEEF1F2:0xFF171A1C;
            muted=night?0xFFA6B0B4:0xFF69707D;
            line=night?0xFF343E43:0xFFE4E7EC;
            accent=night?0xFF70B8A8:0xFF397D72;
            accentSoft=night?0xFF233D38:0xFFE3EFEB;
            control=night?0xFF293035:0xFFEEF0F4;
            danger=night?0xFFF0A8A0:0xFF9D3E39;
        }
    }
    public static Palette get(Context context) {
        String mode=context.getSharedPreferences("config",0).getString("appearance_theme","system");
        boolean night="dark".equals(mode) || (!"light".equals(mode) &&
                (context.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);
        return new Palette(night);
    }
    static Palette darkPalette(){return new Palette(true);}
    /** Call before Activity.onCreate so dialogs and stock widgets inherit the same palette. */
    public static void apply(Activity activity) {
        activity.setTheme(get(activity).dark?R.style.DarkAppTheme:R.style.LightAppTheme);
    }
    public static void systemBars(Activity activity) {
        Palette p=get(activity);
        activity.getWindow().setStatusBarColor(p.bg);
        activity.getWindow().setNavigationBarColor(Build.VERSION.SDK_INT>=26?p.bg:0xFF0F1215);
        int flags=View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if(!p.dark)flags|=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if(!p.dark&&Build.VERSION.SDK_INT>=26)flags|=View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        activity.getWindow().getDecorView().setSystemUiVisibility(flags);
        if(Build.VERSION.SDK_INT>=29){
            activity.getWindow().setNavigationBarContrastEnforced(false);
            activity.getWindow().setStatusBarContrastEnforced(false);
        }
    }
    public static boolean motionEnabled(Context context) {
        if(!context.getSharedPreferences("config",0).getBoolean("motion_enabled",true))return false;
        if(Build.VERSION.SDK_INT>=26&&!ValueAnimator.areAnimatorsEnabled())return false;
        try {return Settings.Global.getFloat(context.getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0;}
        catch(RuntimeException unavailable){return true;}
    }
    /** Adds feedback without taking ownership of click or gesture dispatch. */
    public static void press(View view) {
        view.setOnTouchListener((v,event)->{
            if(!motionEnabled(v.getContext())||!v.isEnabled())return false;
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){
                v.animate().cancel();v.animate().translationY(v.getResources().getDisplayMetrics().density).setDuration(90).setInterpolator(new DecelerateInterpolator()).start();
            } else if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){
                v.animate().cancel();v.animate().translationY(0).setDuration(170).setInterpolator(new OvershootInterpolator(.6f)).start();
            }
            return false;
        });
    }
    public static void enter(View view) {
        view.animate().cancel();view.setTranslationY(0);view.setAlpha(1);
        if(!motionEnabled(view.getContext()))return;
        view.setAlpha(0);view.setTranslationY(6*view.getResources().getDisplayMetrics().density);
        view.animate().alpha(1).translationY(0).setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
    }
}
