package dev.streampocket;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Brief in-app feedback, attached to the current screen and replaced instead of stacked. */
final class PocketNotice {
    private static final String TAG="streampocket.notice";
    private PocketNotice(){}
    static void show(Activity activity,String message){show(activity,message,null);}
    static void show(Activity activity,String message,Runnable undo){
        if(activity.isFinishing()||activity.isDestroyed())return;
        FrameLayout host=activity.findViewById(android.R.id.content);if(host==null)return;
        View previous=host.findViewWithTag(TAG);if(previous!=null)host.removeView(previous);
        UiTheme.Palette p=activity instanceof PlayerActivity?UiTheme.darkPalette():UiTheme.get(activity);
        float density=activity.getResources().getDisplayMetrics().density;
        TextView notice=new TextView(activity);notice.setTag(TAG);notice.setText(message);notice.setTextSize(13);notice.setTextColor(p.bg);notice.setMaxLines(3);notice.setGravity(Gravity.CENTER_VERTICAL);
        notice.setPadding(Math.round(16*density),Math.round(12*density),Math.round(16*density),Math.round(12*density));
        if(undo!=null){notice.setText(message+"    撤销");notice.setMinHeight(Math.round(48*density));notice.setClickable(true);notice.setFocusable(true);notice.setContentDescription(message+"，点按撤销");notice.setOnClickListener(v->{host.removeView(notice);undo.run();});}
        GradientDrawable face=new GradientDrawable();face.setColor(p.ink);face.setCornerRadius(12*density);notice.setBackground(face);
        FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);params.leftMargin=params.rightMargin=Math.round(20*density);
        int bottom=0;if(android.os.Build.VERSION.SDK_INT>=23&&host.getRootWindowInsets()!=null)bottom=host.getRootWindowInsets().getSystemWindowInsetBottom();
        params.bottomMargin=bottom+Math.round((activity instanceof MainActivity?92:24)*density);host.addView(notice,params);UiTheme.enter(notice);notice.announceForAccessibility(message);
        notice.postDelayed(()->{if(notice.getParent()==host)host.removeView(notice);},4000);
    }
}
