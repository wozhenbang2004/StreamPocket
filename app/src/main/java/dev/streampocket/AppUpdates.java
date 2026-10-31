package dev.streampocket;

import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Public GitHub metadata + Android's resumable download service + private, verified install staging. */
final class AppUpdates {
    enum Phase { IDLE, CHECKING, NONE, AVAILABLE, DOWNLOADING, PAUSED, VERIFYING, READY, ERROR }
    interface Listener { void changed(Snapshot state); }
    interface InstallReady { void ready(File apk); }
    static final class Snapshot {
        final Phase phase;final UpdateOffer offer;final String message;final long downloaded;
        Snapshot(Phase phase,UpdateOffer offer,String message,long downloaded){this.phase=phase;this.offer=offer;this.message=message;this.downloaded=downloaded;}
        boolean active(){return phase==Phase.DOWNLOADING||phase==Phase.PAUSED||phase==Phase.VERIFYING||phase==Phase.READY;}
    }
    private static AppUpdates instance;
    static synchronized AppUpdates get(Context context){if(instance==null)instance=new AppUpdates(context.getApplicationContext());return instance;}
    private final Context app;
    private final SharedPreferences prefs;
    private final DownloadManager downloads;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor(task->new Thread(task,"app-updates"));
    private final CopyOnWriteArrayList<Listener> listeners=new CopyOnWriteArrayList<>();
    private final Object lock=new Object();
    private volatile Snapshot state=new Snapshot(Phase.IDLE,null,"从 GitHub 正式发布检查应用更新",0);
    private volatile long generation;
    private long downloadId=-1;
    private boolean busy;
    private Intent installConfirmation;

    private AppUpdates(Context app){
        this.app=app;prefs=app.getSharedPreferences("app_updates",Context.MODE_PRIVATE);downloads=(DownloadManager)app.getSystemService(Context.DOWNLOAD_SERVICE);
        try {
            UpdateOffer saved=UpdateOffer.saved(prefs.getString("offer",""));
            if(saved.code>BuildConfig.VERSION_CODE){downloadId=prefs.getLong("download_id",-1);
                if(downloadId>=0)state=new Snapshot(Phase.DOWNLOADING,saved,"正在恢复系统下载状态…",0);
                else if(prefs.getBoolean("ready",false)&&readyFile(app,saved).isFile())state=new Snapshot(Phase.READY,saved,"安装前将再次校验文件与身份",saved.size);
                else if(prefs.getBoolean("ready",false)){prefs.edit().putBoolean("ready",false).apply();state=new Snapshot(Phase.AVAILABLE,saved,"旧安装缓存已停用，请重新下载更新",0);}
            }else worker.execute(()->{long old=prefs.getLong("download_id",-1);if(old>=0)downloads.remove(old);prefs.edit().remove("offer").remove("download_id").remove("ready").apply();discardFiles();});
        }catch(Exception absent){/* First run has no update state. */}
    }
    Snapshot snapshot(){return state;}
    void listen(Listener listener){listeners.addIfAbsent(listener);listener.changed(state);}
    void unlisten(Listener listener){listeners.remove(listener);}
    boolean automatic(){return prefs.getBoolean("automatic",true);}
    void automatic(boolean value){prefs.edit().putBoolean("automatic",value).apply();}
    boolean shouldNotify(long code){return prefs.getLong("notified_code",0)<code;}
    void notified(long code){prefs.edit().putLong("notified_code",code).apply();}
    boolean shouldNotifyReady(long code){return prefs.getLong("ready_notified_code",0)<code;}
    void notifiedReady(long code){prefs.edit().putLong("ready_notified_code",code).apply();}
    static String installKey(UpdateOffer offer){return offer.code+":"+offer.sha256;}
    boolean installerLaunched(UpdateOffer offer){return installKey(offer).equals(prefs.getString("installer_offer",""));}
    String installationMessage(UpdateOffer offer){return installerLaunched(offer)?prefs.getString("installer_result",""):"";}
    boolean sourcePrompted(UpdateOffer offer){return installKey(offer).equals(prefs.getString("source_prompted",""));}
    void markSourcePrompted(UpdateOffer offer){prefs.edit().putString("source_prompted",installKey(offer)).apply();}
    boolean claimInstaller(UpdateOffer offer){
        synchronized(lock){
            if(busy||state.phase!=Phase.READY||state.offer!=offer||installerLaunched(offer))return false;
            if(!prefs.edit().putString("installer_offer",installKey(offer)).remove("installer_result").commit()){
                publish(new Snapshot(Phase.ERROR,offer,"无法保存安装状态，请重试更新",0));return false;
            }
            return true;
        }
    }
    void retryInstaller(UpdateOffer offer){synchronized(lock){if(state.offer!=offer||busy)return;abandonInstall();prefs.edit().remove("installer_offer").remove("installer_result").apply();}}
    void resumeInstallation(){synchronized(lock){if(busy||state.phase!=Phase.READY||state.offer==null||!installerLaunched(state.offer))return;
        try{PackageInfo own=app.getPackageManager().getPackageInfo(app.getPackageName(),signatureFlags());if(code(own)>=state.offer.code){installationReturned(state.offer,false);return;}}catch(Exception ignored){}
        int id=prefs.getInt("install_session",-1);if(id>=0&&app.getPackageManager().getPackageInstaller().getSessionInfo(id)==null){clearInstallSession();installationReturned(state.offer,false);}
    }}
    String installedLabel(){
        try{PackageInfo info=app.getPackageManager().getPackageInfo(app.getPackageName(),0);return "当前版本 "+info.versionName+" · "+code(info);}
        catch(PackageManager.NameNotFoundException absent){return "当前版本 "+BuildConfig.VERSION_NAME+" · "+BuildConfig.VERSION_CODE;}
    }
    String installationDetails(){
        StringBuilder out=new StringBuilder("拾流安装信息\n").append(app.getPackageName()).append('\n').append(installedLabel())
                .append("\nAndroid ").append(Build.VERSION.RELEASE).append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL);
        try{out.append("\n已安装签名 SHA-256: ").append(certificate(app.getPackageManager().getPackageInfo(app.getPackageName(),signatureFlags())));}catch(Exception unavailable){out.append("\n已安装签名暂不可读");}
        Snapshot current=state;if(current.offer!=null){UpdateOffer offer=current.offer;out.append("\n目标版本 ").append(offer.version).append(" · ").append(offer.code)
                .append("\n安装包 SHA-256: ").append(offer.sha256).append("\n大小: ").append(offer.size).append(" bytes");
            String result=installationMessage(offer);if(!result.isEmpty())out.append('\n').append(result);}
        return out.toString();
    }
    /** Writes verified bytes into a distinct OS session instead of handing an alias URI to an installer. */
    void stageInstall(UpdateOffer offer,File file){
        final long token;
        synchronized(lock){if(busy||state.phase!=Phase.READY||state.offer!=offer||!installerLaunched(offer))return;busy=true;token=generation;publish(new Snapshot(Phase.VERIFYING,offer,"正在交给系统准备安装…",offer.size));}
        worker.execute(()->{
            PackageInstaller installer=app.getPackageManager().getPackageInstaller();int id=-1;
            try{
                PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(offer.packageName);params.setSize(offer.size);
                if(Build.VERSION.SDK_INT>=31)params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
                id=installer.createSession(params);String nonce=java.util.UUID.randomUUID().toString();String identity="streampocket-update://session/"+id+"/"+nonce;
                synchronized(lock){if(token!=generation)throw new IOException("更新已取消");
                    if(!prefs.edit().putInt("install_session",id).putString("install_session_offer",installKey(offer)).putString("install_session_uri",identity).commit())throw new IOException("无法保存系统安装会话");}
                try(PackageInstaller.Session session=installer.openSession(id)){
                    try(InputStream input=new FileInputStream(file);OutputStream output=session.openWrite("base.apk",0,offer.size)){
                        byte[] bytes=new byte[64*1024];int read;while((read=input.read(bytes))!=-1){if(token!=generation)throw new IOException("更新已取消");output.write(bytes,0,read);}session.fsync(output);
                    }
                    verifySessionBytes(session,offer,()->token!=generation);
                    Intent result=new Intent(app,UpdateInstallReceiver.class).setAction(UpdateInstallReceiver.RESULT).setData(Uri.parse(identity));
                    int flags=PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=31?PendingIntent.FLAG_MUTABLE:0);
                    PendingIntent callback=PendingIntent.getBroadcast(app,id,result,flags);
                    synchronized(lock){if(token!=generation)throw new IOException("更新已取消");session.commit(callback.getIntentSender());busy=false;publish(new Snapshot(Phase.READY,offer,"等待系统安装确认",offer.size));}
                }
            }catch(Exception failure){if(id>=0)try{installer.abandonSession(id);}catch(RuntimeException ignored){}
                synchronized(lock){if(token!=generation)return;clearInstallSession();busy=false;prefs.edit().putString("installer_result",message(failure,"系统暂未接受安装包，请重新打开安装确认")).apply();publish(new Snapshot(Phase.READY,offer,installationMessage(offer),offer.size));}}
        });
    }
    static void verifySessionBytes(PackageInstaller.Session session,UpdateOffer offer,Cancelled cancelled)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0;
        try(InputStream input=session.openRead("base.apk")){byte[] bytes=new byte[64*1024];int read;while((read=input.read(bytes))!=-1){if(cancelled.get())throw new IOException("更新已取消");count+=read;digest.update(bytes,0,read);}}
        if(cancelled.get()||count!=offer.size||!hex(digest.digest()).equals(offer.sha256))throw new IOException("系统暂存安装包校验失败");
    }
    Intent takeInstallConfirmation(UpdateOffer offer){synchronized(lock){if(state.offer!=offer||!installerLaunched(offer))return null;Intent result=installConfirmation;installConfirmation=null;return result;}}
    void installationLaunchFailed(UpdateOffer offer){synchronized(lock){if(state.offer!=offer)return;prefs.edit().putString("installer_result","系统安装确认未能打开，可重新尝试。安装包已保留。").apply();}}
    void installResult(Intent result){
        synchronized(lock){UpdateOffer offer=state.offer;int id=result.getIntExtra(PackageInstaller.EXTRA_SESSION_ID,-1);
            if(offer==null||!installerLaunched(offer)||id<0||id!=prefs.getInt("install_session",-1)||!installKey(offer).equals(prefs.getString("install_session_offer",""))
                    ||result.getData()==null||!result.getData().toString().equals(prefs.getString("install_session_uri","")))return;
            int status=result.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);
            if(status==PackageInstaller.STATUS_PENDING_USER_ACTION){
                if(prefs.getBoolean("install_pending_received",false))return;prefs.edit().putBoolean("install_pending_received",true).apply();installConfirmation=result.getParcelableExtra(Intent.EXTRA_INTENT);
                prefs.edit().putString("installer_result",installConfirmation==null?"系统未返回安装确认，请重试。":"安装包已交给系统，等待你确认安装").apply();
                publish(new Snapshot(Phase.READY,offer,installationMessage(offer),offer.size));return;}
            clearInstallSession();
            if(status==PackageInstaller.STATUS_SUCCESS){installationReturned(offer,true);return;}
            String reason=status==PackageInstaller.STATUS_FAILURE_ABORTED?"已取消系统安装":status==PackageInstaller.STATUS_FAILURE_STORAGE?"系统存储空间不足":status==PackageInstaller.STATUS_FAILURE_CONFLICT?"系统检测到版本或签名冲突":status==PackageInstaller.STATUS_FAILURE_BLOCKED?"系统暂不允许安装":"系统未完成安装";
            prefs.edit().putString("installer_result",reason+"。"+installedLabel()+"，目标 "+offer.version+" · "+offer.code+"。安装包已保留，可重试或复制安装信息。").apply();
            publish(new Snapshot(Phase.READY,offer,installationMessage(offer),offer.size));
        }
    }
    private void clearInstallSession(){installConfirmation=null;prefs.edit().remove("install_session").remove("install_session_offer").remove("install_session_uri").remove("install_pending_received").apply();}
    private void abandonInstall(){int id=prefs.getInt("install_session",-1);clearInstallSession();if(id>=0)try{app.getPackageManager().getPackageInstaller().abandonSession(id);}catch(RuntimeException ignored){}}
    void installationReturned(UpdateOffer offer,boolean reportedSuccess){
        synchronized(lock){
            if(state.phase!=Phase.READY||state.offer!=offer||!installerLaunched(offer))return;
            try{
                PackageInfo installed=app.getPackageManager().getPackageInfo(app.getPackageName(),signatureFlags());
                if(code(installed)>=offer.code&&offer.certificate.equals(certificate(installed))){
                    generation++;prefs.edit().remove("offer").remove("download_id").remove("ready").remove("installer_offer").remove("installer_result").apply();
                    publish(new Snapshot(Phase.NONE,null,"已更新到 "+installed.versionName,0));worker.execute(this::discardFiles);return;
                }
                String message=(reportedSuccess?"安装返回后仍是 ":"当前仍是 ")+installed.versionName+"，尚未升级到 "+offer.version+"。可重新安装，或下载独立安装包覆盖更新。";
                prefs.edit().putString("installer_result",message).apply();publish(new Snapshot(Phase.READY,offer,message,offer.size));
            }catch(Exception unavailable){prefs.edit().putString("installer_result","尚未确认更新完成，请重新打开应用检查版本，或重试安装。").apply();}
        }
    }
    boolean ownsDownload(long id){synchronized(lock){return id>=0&&id==downloadId&&state.offer!=null;}}
    void clearDownloadReminder(){UpdateDownloadReceiver.clearReminder(app);}
    void checkIfDue(){if(automatic()&&System.currentTimeMillis()-prefs.getLong("last_check",0)>24*60*60*1000L)check();}

    void check(){
        final long token;
        synchronized(lock){if(busy||state.active())return;busy=true;token=++generation;publish(new Snapshot(Phase.CHECKING,null,"正在检查 GitHub 正式发布…",0));}
        prefs.edit().putLong("last_check",System.currentTimeMillis()).apply();
        worker.execute(()->{
            try {
                String json=getJson(UpdateOffer.API,256*1024,true);
                if(json==null){finish(token,new Snapshot(Phase.NONE,null,"暂未发布可安装的正式更新",0));return;}
                JSONObject release=new JSONObject(json);
                if(release.optBoolean("draft",true)||release.optBoolean("prerelease",true)){finish(token,new Snapshot(Phase.NONE,null,"暂无正式更新（测试发布不自动分发）",0));return;}
                JSONObject asset=UpdateOffer.asset(release,UpdateOffer.APK),manifestAsset=UpdateOffer.asset(release,UpdateOffer.MANIFEST);
                if(asset==null||manifestAsset==null){finish(token,new Snapshot(Phase.NONE,null,"当前发布尚无完整安装包与校验清单",0));return;}
                String tag=release.getString("tag_name"),manifestUrl=manifestAsset.getString("browser_download_url");
                if(!UpdateOffer.validAssetUrl(manifestUrl,tag,UpdateOffer.MANIFEST))throw new IOException("更新清单来源不匹配");
                UpdateOffer offer=new UpdateOffer(new JSONObject(getJson(manifestUrl,64*1024,false)),tag,release.optString("body"),asset.getString("browser_download_url"));
                if(offer.code<=BuildConfig.VERSION_CODE){finish(token,new Snapshot(Phase.NONE,null,"当前已是最新可用版本",0));return;}
                compatible(offer);
                if(asset.getLong("size")!=offer.size)throw new IOException("安装包大小与发布清单不一致");
                String digest=asset.optString("digest");if(!digest.isEmpty()&&!digest.equals("sha256:"+offer.sha256))throw new IOException("安装包摘要与发布清单不一致");
                finish(token,new Snapshot(Phase.AVAILABLE,offer,"发现新版本，下载需你确认",0));
            }catch(Exception failure){fail(token,null,message(failure,"无法检查更新，请检查网络后重试"));}
        });
    }

    void download(){
        final long token;final UpdateOffer offer;
        synchronized(lock){if(busy||state.phase!=Phase.AVAILABLE||state.offer==null)return;offer=state.offer;busy=true;token=++generation;publish(new Snapshot(Phase.DOWNLOADING,offer,"正在交给系统下载…",0));}
        worker.execute(()->{
            long id=-1;
            try {
                compatible(offer);if(token!=generation)return;
                File external=app.getExternalFilesDir(null);if(external==null)throw new IOException("更新下载存储暂不可用");
                File destination=new File(external,"app-updates/update-"+offer.code+".download");
                if(destination.exists()&&!destination.delete())throw new IOException("无法准备更新下载目录");
                DownloadManager.Request request=new DownloadManager.Request(Uri.parse(offer.url)).setTitle("拾流更新 "+offer.version)
                        .setDescription("点此查看进度；完成后自动校验并进入系统安装确认")
                        .setMimeType("application/octet-stream")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                        .setDestinationInExternalFilesDir(app,null,"app-updates/update-"+offer.code+".download");
                id=downloads.enqueue(request);
                synchronized(lock){if(token!=generation){downloads.remove(id);return;}
                    if(!prefs.edit().putString("offer",offer.json().toString()).putLong("download_id",id).putBoolean("ready",false)
                            .remove("installer_offer").remove("installer_result").remove("source_prompted").commit())throw new IOException("无法保存更新下载状态");
                    downloadId=id;busy=false;publish(new Snapshot(Phase.DOWNLOADING,offer,"系统下载中；离开本页也会继续",0));}
            }catch(Exception failure){if(id>=0)downloads.remove(id);fail(token,offer,message(failure,"无法开始更新下载"));}
        });
    }

    void refresh(){
        final long token,id;final UpdateOffer offer;
        synchronized(lock){if(busy||downloadId<0||state.offer==null)return;busy=true;token=generation;id=downloadId;offer=state.offer;}
        worker.execute(()->{
            try(Cursor cursor=downloads.query(new DownloadManager.Query().setFilterById(id))){
                if(cursor==null||!cursor.moveToFirst())throw new IOException("下载记录已被移除，请重新检查更新");
                int status=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                long bytes=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                if(bytes>offer.size){downloads.remove(id);throw new IOException("下载内容超过发布大小，已拒绝");}
                if(status==DownloadManager.STATUS_SUCCESSFUL){
                    publishIfCurrent(token,new Snapshot(Phase.VERIFYING,offer,"正在校验安装包，请稍候…",offer.size));
                    File stage=new File(app.getFilesDir(),"app-updates/staged.apk");stage.getParentFile().mkdirs();
                    try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(downloads.openDownloadedFile(id))){copyAndHash(input,stage,offer,()->token!=generation);}
                    verifyArchive(stage,offer);if(token!=generation){stage.delete();return;}
                    File ready=readyFile(app,offer);ready.getParentFile().mkdirs();
                    if(ready.exists()){hashFile(ready,offer,()->token!=generation);stage.delete();}
                    else if(!stage.renameTo(ready))throw new IOException("无法保存已校验安装包");
                    synchronized(lock){if(token!=generation){ready.delete();return;}downloadId=-1;
                        if(!prefs.edit().putLong("download_id",-1).putBoolean("ready",true).commit())throw new IOException("无法保存安装状态");}
                    downloads.remove(id);finish(token,new Snapshot(Phase.READY,offer,"校验完成，将进入系统安装确认",offer.size));
                }else if(status==DownloadManager.STATUS_FAILED){downloads.remove(id);throw new IOException("系统下载失败，请重新检查后下载");}
                else finish(token,new Snapshot(status==DownloadManager.STATUS_PAUSED?Phase.PAUSED:Phase.DOWNLOADING,offer,
                        status==DownloadManager.STATUS_PAUSED?"系统等待网络或重试；可取消":"系统下载中；离开本页也会继续",Math.max(0,bytes)));
            }catch(Exception failure){if(token==generation){downloads.remove(id);synchronized(lock){downloadId=-1;prefs.edit().putLong("download_id",-1).putBoolean("ready",false).apply();}discardFiles();}fail(token,offer,message(failure,"更新下载或校验失败"));}
        });
    }

    void cancel(){
        final long id;
        synchronized(lock){generation++;abandonInstall();id=downloadId;downloadId=-1;busy=false;prefs.edit().remove("offer").remove("download_id").remove("ready").remove("installer_offer").remove("installer_result").remove("source_prompted").apply();publish(new Snapshot(Phase.IDLE,null,"已取消更新；现有应用与视频不受影响",0));}
        clearDownloadReminder();
        worker.execute(()->{if(id>=0)downloads.remove(id);discardFiles();});
    }

    void prepareInstall(UpdateOffer expected,InstallReady callback){
        final long token;final UpdateOffer offer;
        synchronized(lock){if(busy||state.phase!=Phase.READY||state.offer==null||state.offer!=expected)return;busy=true;token=generation;offer=state.offer;publish(new Snapshot(Phase.VERIFYING,offer,"正在复核安装包…",offer.size));}
        worker.execute(()->{
            try {File file=readyFile();hashFile(file,offer,()->token!=generation);verifyArchive(file,offer);
                if(token!=generation)return;finish(token,new Snapshot(Phase.READY,offer,"交由系统确认覆盖安装；不会卸载或清除数据",offer.size));
                main.post(()->{if(token==generation&&state.phase==Phase.READY&&state.offer==offer)callback.ready(file);});
            }catch(Exception failure){if(token==generation)discardFiles();fail(token,offer,message(failure,"安装包复核失败，请重新下载"));}
        });
    }

    private void compatible(UpdateOffer offer) throws Exception {PackageInfo own=app.getPackageManager().getPackageInfo(app.getPackageName(),signatureFlags());offer.compatible(app.getPackageName(),code(own),certificate(own),Build.VERSION.SDK_INT,Build.SUPPORTED_ABIS);}
    private void verifyArchive(File file,UpdateOffer offer) throws Exception {
        compatible(offer);PackageInfo apk;
        try{apk=app.getPackageManager().getPackageArchiveInfo(file.getPath(),signatureFlags());}
        catch(OutOfMemoryError unavailable){throw new IOException("安装包解析内存不足，请稍后重试");}
        if(apk==null)throw new IOException("安装包无法读取");
        verifyIdentity(offer,apk.packageName,code(apk),apk.versionName,certificate(apk),apk.applicationInfo==null?Integer.MAX_VALUE:apk.applicationInfo.minSdkVersion);
    }
    static void verifyIdentity(UpdateOffer offer,String packageName,long code,String version,String certificate,int minSdk)throws IOException{
        if(!offer.packageName.equals(packageName)||offer.code!=code||!offer.version.equals(version)||!offer.certificate.equals(certificate)||offer.minSdk!=minSdk)throw new IOException("APK 身份与发布清单不一致，已拒绝安装");
    }
    private static int signatureFlags(){return Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;}
    private static long code(PackageInfo info){return Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;}
    private static String certificate(PackageInfo info)throws Exception{
        Signature[] signatures=Build.VERSION.SDK_INT>=28&&info.signingInfo!=null?info.signingInfo.getApkContentsSigners():info.signatures;
        if(signatures==null||signatures.length!=1)throw new IOException("无法确认单一应用签名");return hex(MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray()));
    }
    interface Cancelled{boolean get();}
    static void copyAndHash(InputStream input,File target,UpdateOffer offer,Cancelled cancelled)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0;
        try(FileOutputStream output=new FileOutputStream(target)){byte[] buffer=new byte[64*1024];int read;
            while((read=input.read(buffer))!=-1){if(cancelled.get())throw new IOException("更新已取消");count+=read;if(count>offer.size)throw new IOException("安装包大小不匹配");digest.update(buffer,0,read);output.write(buffer,0,read);}
            if(cancelled.get()||count!=offer.size||!hex(digest.digest()).equals(offer.sha256))throw new IOException("安装包不完整或摘要不匹配，已拒绝");
        }catch(Exception failure){target.delete();throw failure;}
    }
    static void hashFile(File file,UpdateOffer offer,Cancelled cancelled)throws Exception{
        if(file.length()!=offer.size)throw new IOException("安装包大小不匹配");MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=new FileInputStream(file)){byte[] bytes=new byte[64*1024];int read;while((read=input.read(bytes))!=-1){if(cancelled.get())throw new IOException("更新已取消");digest.update(bytes,0,read);}}
        if(!hex(digest.digest()).equals(offer.sha256))throw new IOException("安装包完整性复核失败");
    }
    static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte value:bytes)out.append(String.format(Locale.ROOT,"%02x",value&255));return out.toString();}
    static File readyFile(Context context,UpdateOffer offer){return new File(context.getFilesDir(),"app-updates/ready/update-"+offer.code+"-"+offer.sha256+".apk");}
    private File readyFile(){return readyFile(app,state.offer);}
    private void discardFiles(){
        File[] files=new File(app.getFilesDir(),"app-updates/ready").listFiles();
        if(files!=null)for(File file:files)if(file.isFile()&&(file.getName().equals("update.apk")||file.getName().matches("update-[0-9]+-[a-f0-9]{64}\\.apk")))file.delete();
        new File(app.getFilesDir(),"app-updates/staged.apk").delete();
    }
    private void finish(long token,Snapshot result){synchronized(lock){if(token!=generation)return;busy=false;publish(result);}}
    private void fail(long token,UpdateOffer offer,String message){finish(token,new Snapshot(Phase.ERROR,offer,message,0));}
    private void publishIfCurrent(long token,Snapshot result){synchronized(lock){if(token==generation)publish(result);}}
    private void publish(Snapshot value){state=value;main.post(()->{if(state==value)for(Listener listener:listeners)listener.changed(value);});}
    private static String message(Exception error,String fallback){String value=error.getMessage();return value!=null&&value.matches("(?s).*[\\u4e00-\\u9fff].*")&&!value.contains("http")?value:fallback;}

    static String getJson(String url,int limit,boolean missingIsNone)throws Exception{
        for(int redirects=0;redirects<5;redirects++){
            if(!UpdateOffer.allowedRedirect(url))throw new IOException("更新服务重定向来源无效");
            HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
            connection.setConnectTimeout(15_000);connection.setReadTimeout(20_000);connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept","application/vnd.github+json");connection.setRequestProperty("User-Agent","StreamPocket/"+BuildConfig.VERSION_NAME);
            connection.setRequestProperty("X-GitHub-Api-Version","2026-03-10");
            try {
                int code=connection.getResponseCode();
                if(code==404&&missingIsNone)return null;
                if(code==403||code==429)throw new IOException("GitHub 暂时限流或拒绝访问，请稍后重试");
                if(code>=300&&code<=399){String location=connection.getHeaderField("Location");if(location==null)throw new IOException("更新服务缺少重定向地址");url=new URL(new URL(url),location).toString();continue;}
                if(code!=200)throw new IOException("更新服务暂不可用（HTTP "+code+"）");
                if(connection.getContentLengthLong()>limit)throw new IOException("更新清单过大");
                try(InputStream input=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1){if(out.size()+count>limit)throw new IOException("更新清单过大");out.write(buffer,0,count);}return out.toString(StandardCharsets.UTF_8.name());}
            }finally{connection.disconnect();}
        }
        throw new IOException("更新服务重定向次数过多");
    }
}
