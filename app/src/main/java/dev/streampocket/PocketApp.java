package dev.streampocket;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

public final class PocketApp extends Application {
    private final Handler main=new Handler(Looper.getMainLooper());
    private int startedActivities;
    private boolean changingConfigurations;
    private final Runnable checkUpdates=()->{if(startedActivities>0)AppUpdates.get(this).checkIfDue();};

    @Override public void onCreate() {
        super.onCreate();
        TaskStore.init(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks(){
            @Override public void onActivityStarted(Activity activity){
                if(startedActivities++==0){
                    if(!changingConfigurations)AppUpdates.get(PocketApp.this).enteredForeground();
                    changingConfigurations=false;
                    main.removeCallbacks(checkUpdates);main.postDelayed(checkUpdates,1500);
                }
            }
            @Override public void onActivityStopped(Activity activity){
                if(--startedActivities==0){
                    changingConfigurations=activity.isChangingConfigurations();
                    main.removeCallbacks(checkUpdates);
                    if(!changingConfigurations)AppUpdates.get(PocketApp.this).leftForeground();
                }
            }
            @Override public void onActivityCreated(Activity activity,Bundle state){}
            @Override public void onActivityResumed(Activity activity){}
            @Override public void onActivityPaused(Activity activity){}
            @Override public void onActivitySaveInstanceState(Activity activity,Bundle state){}
            @Override public void onActivityDestroyed(Activity activity){}
        });
    }
}
