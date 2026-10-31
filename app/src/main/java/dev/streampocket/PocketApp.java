package dev.streampocket;

import android.app.Application;

public final class PocketApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        TaskStore.init(this);
    }
}
