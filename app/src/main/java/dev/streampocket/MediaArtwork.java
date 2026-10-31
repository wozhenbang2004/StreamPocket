package dev.streampocket;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Public artwork only: no account cookies, bounded image bytes, and cancellable bindings. */
public final class MediaArtwork {
    private static final int BYTE_LIMIT=4*1024*1024;
    private final ThumbnailRepository local;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<ImageView,Request> requests=new WeakHashMap<>();
    private final Map<String,Request> jobs=new java.util.HashMap<>();
    private final LruCache<String,Bitmap> memory=new LruCache<String,Bitmap>(BYTE_LIMIT){
        @Override protected int sizeOf(String key,Bitmap bitmap){return bitmap.getAllocationByteCount();}
    };
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,15,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"media-artwork");t.setPriority(Thread.MIN_PRIORITY);return t;},new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean released;
    public MediaArtwork(Context context){local=new ThumbnailRepository(context);worker.allowCoreThreadTimeOut(true);}
    public void load(ImageView target,String inputSource,String cacheKey){
        final String source=secureSource(inputSource);
        cancel(target);target.setImageDrawable(null);
        if(released||source==null||source.isEmpty())return;
        Uri uri=Uri.parse(source);
        String key=key(cacheKey+"|"+source);
        if("content".equals(uri.getScheme())){local.load(target,0,source,"task-"+key,1);return;}
        if(!isPublicImageUrl(source))return;
        Bitmap cached=memory.get(key);if(cached!=null){target.setImageBitmap(cached);return;}
        Request existing=jobs.get(key);
        if(existing!=null){existing.targets.add(new WeakReference<>(target));requests.put(target,existing);return;}
        Request request=new Request(target);requests.put(target,request);jobs.put(key,request);
        request.task=new FutureTask<>(()->{
            Bitmap bitmap=null;
            try {if(!request.cancelled&&!released)bitmap=read(source,request);}catch(Exception|OutOfMemoryError unavailable){/* Keep the neutral placeholder. */}
            Bitmap result=bitmap;
            if(result!=null&&!released)memory.put(key,result);
            main.post(()->{
                if(jobs.get(key)==request)jobs.remove(key);
                for(WeakReference<ImageView> targetRef:request.targets){
                    ImageView view=targetRef.get();
                    if(view!=null&&requests.get(view)==request){requests.remove(view);if(!released&&!request.cancelled&&result!=null)view.setImageBitmap(result);}
                }
                request.targets.clear();
            });
            return null;
        });
        try {worker.execute(request.task);}catch(RejectedExecutionException full){requests.remove(target);jobs.remove(key);}
    }
    static String secureSource(String source){
        if(source==null)return null;
        try {Uri uri=Uri.parse(source);return "http".equalsIgnoreCase(uri.getScheme())?uri.buildUpon().scheme("https").build().toString():source;}
        catch(RuntimeException invalid){return "";}
    }
    static boolean isPublicImageUrl(String source){
        try {
            URL u=new URL(source);String host=u.getHost().toLowerCase(java.util.Locale.ROOT);
            if(!"https".equals(u.getProtocol())||u.getUserInfo()!=null||u.getPort()!=-1&&u.getPort()!=443)return false;
            return !host.isEmpty()&&!host.equals("localhost")&&!host.endsWith(".localhost")&&!host.endsWith(".local")
                    &&!host.matches("[0-9.]+")&&!host.contains(":");
        }catch(Exception invalid){return false;}
    }
    private static Bitmap read(String source,Request request)throws Exception{
        String current=source;long deadline=android.os.SystemClock.elapsedRealtime()+10000;
        for(int redirects=0;redirects<4;redirects++){
            if(request.cancelled||android.os.SystemClock.elapsedRealtime()>deadline)return null;
            HttpURLConnection connection=(HttpURLConnection)new URL(current).openConnection();request.connection=connection;
            connection.setConnectTimeout(6000);connection.setReadTimeout(6000);connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept","image/*");connection.setRequestProperty("Accept-Encoding","identity");
            try {
                int status=connection.getResponseCode();
                if(status>=300&&status<400){String next=connection.getHeaderField("Location");if(next==null)return null;current=new URL(new URL(current),next).toString();if(!isPublicImageUrl(current))return null;continue;}
                if(status!=200||connection.getContentLengthLong()>BYTE_LIMIT)return null;
                byte[] data;
                try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] buffer=new byte[16384];int n;
                    while((n=in.read(buffer))!=-1){if(request.cancelled||android.os.SystemClock.elapsedRealtime()>deadline||out.size()+n>BYTE_LIMIT)return null;out.write(buffer,0,n);}
                    data=out.toByteArray();
                }
                BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,options);
                if(options.outWidth<=0||options.outHeight<=0||options.outWidth>30000||options.outHeight>30000)return null;
                int sample=1;while(options.outWidth/sample>640||options.outHeight/sample>640)sample*=2;
                options.inJustDecodeBounds=false;options.inSampleSize=sample;options.inPreferredConfig=Bitmap.Config.RGB_565;
                return request.cancelled?null:BitmapFactory.decodeByteArray(data,0,data.length,options);
            }finally{connection.disconnect();request.connection=null;}
        }
        return null;
    }
    private static String key(String value){
        try{byte[] hash=MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:hash)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
        catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
    public void cancel(ImageView target){
        local.cancel(target);requests.remove(target);
        // Finish bounded shared artwork jobs into cache: frequent progress row rebinding
        // must not repeatedly cancel and restart the same thumbnail network request.
    }
    public void release(){
        released=true;for(ImageView view:new ArrayList<>(requests.keySet()))if(view!=null)cancel(view);
        for(Request job:jobs.values()){job.cancelled=true;if(job.task!=null)job.task.cancel(false);HttpURLConnection connection=job.connection;if(connection!=null)connection.disconnect();}
        jobs.clear();worker.shutdownNow();local.release();memory.evictAll();main.removeCallbacksAndMessages(null);
    }
    private static final class Request{
        final java.util.List<WeakReference<ImageView>> targets=new ArrayList<>();volatile boolean cancelled;volatile HttpURLConnection connection;FutureTask<Void> task;
        Request(ImageView view){targets.add(new WeakReference<>(view));}
    }
}
