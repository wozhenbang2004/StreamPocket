package dev.streampocket;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

/** One palette and bounded, interruptible motion across the native application. */
public final class UiTheme {
    private UiTheme() {}
    public static final class Palette {
        public final int bg,surface,raised,ink,muted,line,accent,accentSoft,control,danger;
        public final boolean dark;
        private Palette(boolean night) {
            dark=night;
            bg=night?0xFF141414:0xFFF7F7F7;
            surface=night?0xFF202020:0xFFF0F0F0;
            raised=night?0xFF303030:0xFFFFFFFF;
            ink=night?0xFFF4F4F4:0xFF252525;
            muted=night?0xFFAAAAAA:0xFF6D6D6D;
            line=night?0xFF3A3A3A:0xFFE0E0E0;
            accent=night?0xFFF0F0F0:0xFF292929;
            accentSoft=night?0xFF303030:0xFFE7E7E7;
            control=night?0xFF2B2B2B:0xFFEAEAEA;
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
        activity.getWindow().setNavigationBarColor(Build.VERSION.SDK_INT>=26?p.bg:0xFF141414);
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
            int action=event.getActionMasked();
            // Release is unconditional: a request may disable its button while held.
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
                releasePress(v);
            }else if(action==MotionEvent.ACTION_DOWN&&v.isEnabled()&&motionEnabled(v.getContext())){
                v.animate().cancel();v.animate().scaleX(.97f).scaleY(.97f).setDuration(160).setInterpolator(easeOut()).start();
            }else if(action==MotionEvent.ACTION_MOVE&&(event.getX()<0||event.getY()<0||event.getX()>v.getWidth()||event.getY()>v.getHeight())){
                releasePress(v);
            }
            return false;
        });
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            @Override public void onViewAttachedToWindow(View v){}
            @Override public void onViewDetachedFromWindow(View v){v.animate().cancel();v.setScaleX(1);v.setScaleY(1);v.setTranslationY(0);}
        });
    }
    static PathInterpolator easeOut(){return new PathInterpolator(.22f,1f,.36f,1f);}
    static void releasePress(View view){
        view.animate().cancel();
        if(!view.isEnabled()||!motionEnabled(view.getContext())){view.setScaleX(1);view.setScaleY(1);view.setTranslationY(0);}
        else view.animate().scaleX(1).scaleY(1).translationY(0).setDuration(160).setInterpolator(easeOut()).start();
    }
    /** Only floating controls receive a shadow; content groups use tonal surfaces. */
    static void floatingSurface(View view,float elevationDp){
        view.setElevation(elevationDp*view.getResources().getDisplayMetrics().density);
        if(Build.VERSION.SDK_INT>=28){view.setOutlineAmbientShadowColor(0x08000000);view.setOutlineSpotShadowColor(0x10000000);}
    }
    public static void enter(View view) {
        view.animate().cancel();view.setTranslationY(0);view.setAlpha(1);
        if(!motionEnabled(view.getContext()))return;
        view.setAlpha(0);view.setTranslationY(5*view.getResources().getDisplayMetrics().density);
        view.animate().alpha(1).translationY(0).setDuration(160).setInterpolator(easeOut()).start();
    }
}
