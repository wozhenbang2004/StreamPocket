package dev.streampocket;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Only the explicit session status PendingIntent can reach this private receiver. */
public final class UpdateInstallReceiver extends BroadcastReceiver {
    static final String RESULT="dev.streampocket.INSTALL_RESULT";
    @Override public void onReceive(Context context,Intent intent){if(intent!=null&&RESULT.equals(intent.getAction()))AppUpdates.get(context).installResult(intent);}
}
