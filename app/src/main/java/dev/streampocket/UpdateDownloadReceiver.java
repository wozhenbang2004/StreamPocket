package dev.streampocket;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Build;

/** Only a notification tap opens the update page; completion never brings an Activity forward. */
public final class UpdateDownloadReceiver extends BroadcastReceiver {
    private static final int REMINDER=290;
    private static final String CHANNEL="application-updates";

    @Override public void onReceive(Context context,Intent intent){
        if(intent==null)return;
        AppUpdates updates=AppUpdates.get(context);
        if(DownloadManager.ACTION_NOTIFICATION_CLICKED.equals(intent.getAction())){
            long[] ids=intent.getLongArrayExtra(DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS);
            if(ids!=null)for(long id:ids)if(updates.ownsDownload(id)){
                try{context.startActivity(page(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}catch(RuntimeException unavailable){/* Reopening the app still restores the same owned job. */}
                return;
            }
        }else if(DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())){
            long id=intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,-1);if(!updates.ownsDownload(id))return;
            DownloadManager downloads=(DownloadManager)context.getSystemService(Context.DOWNLOAD_SERVICE);
            try(Cursor cursor=downloads.query(new DownloadManager.Query().setFilterById(id))){
                if(cursor==null||!cursor.moveToFirst()||cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))!=DownloadManager.STATUS_SUCCESSFUL)return;
                NotificationManager manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
                if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(new NotificationChannel(CHANNEL,"应用更新",NotificationManager.IMPORTANCE_DEFAULT));
                Notification.Builder notice=Build.VERSION.SDK_INT>=26?new Notification.Builder(context,CHANNEL):new Notification.Builder(context);
                manager.notify(REMINDER,notice.setSmallIcon(R.drawable.ic_launcher).setContentTitle("拾流更新已下载")
                        .setContentText("点击继续安装更新")
                        .setContentIntent(PendingIntent.getActivity(context,REMINDER,page(context),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE))
                        .setAutoCancel(true).build());
            }catch(RuntimeException unavailable){/* Notification permission/storage state must not discard the update. */}
        }
    }
    private static Intent page(Context context){return new Intent(context,UpdateActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);}
    static void clearReminder(Context context){((NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(REMINDER);}
}
