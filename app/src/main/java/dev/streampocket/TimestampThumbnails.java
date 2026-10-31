package dev.streampocket;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bound, off-main timestamp frames. Reuses storyboard revision identity and disk budget.
 * Unlike scrub overview tiles these request the closest frame, including non-keyframes.
 * Android may return a nearby frame; the result is not an exact presentation timestamp.
 */
final class TimestampThumbnails {
    interface Callback { void onFrame(Bitmap bitmap); }
    interface Extractor { Bitmap frame(Context app,Uri uri,long at) throws Exception; }
    final class Binding {
        private volatile boolean cancelled;
        private FutureTask<Void> task;
        void cancel() { cancelled=true; if(task!=null){task.cancel(false);worker.remove(task);} bindings.remove(this); }
    }
    private final Context app;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<Binding> bindings=new ArrayList<>();
    private final Extractor extractor;
    private volatile boolean released;
    private final LruCache<String,Bitmap> memory=new LruCache<String,Bitmap>(2*1024*1024) {
        @Override protected int sizeOf(String key,Bitmap bitmap){return bitmap.getAllocationByteCount();}
    };
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,10,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(24),task->{Thread thread=new Thread(task,"timestamp-frames");thread.setPriority(Thread.MIN_PRIORITY);return thread;},
            new ThreadPoolExecutor.AbortPolicy());
    TimestampThumbnails(Context context){this(context,TimestampThumbnails::extract);}
    TimestampThumbnails(Context context,Extractor extractor){app=context.getApplicationContext();this.extractor=extractor;worker.allowCoreThreadTimeOut(true);}
    Binding load(Uri uri,long at,long durationMs,Callback callback) {
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Bind frames on main");
        Binding binding=new Binding();
        if(released || uri==null || !("content".equals(uri.getScheme())||"file".equals(uri.getScheme()))){callback.onFrame(null);return binding;}
        bindings.add(binding);long position=Math.max(0,durationMs>0?Math.min(at,durationMs-1):at);
        binding.task=new FutureTask<>(()->{
            Bitmap frame=null;
            try {
                if(binding.cancelled||released)return null;
                // Opening probes access and file revision before even a memory hit.
                ScrubStoryboardCache disk=ScrubStoryboardCache.open(app,uri,durationMs);
                String key=(disk==null?null:disk.identity()+"|"+position);
                if(key!=null)frame=memory.get(key);
                if(frame==null && disk!=null)frame=disk.readFrame(position);
                if(frame==null && !binding.cancelled && !released)frame=bounded(extractor.frame(app,uri,position));
                if(frame!=null&&!binding.cancelled&&!released){if(key!=null)memory.put(key,frame);if(disk!=null)disk.writeFrame(position,frame);}
            }catch(Exception|OutOfMemoryError unavailable){frame=null;}
            Bitmap ready=frame;
            main.post(()->{bindings.remove(binding);if(!released&&!binding.cancelled)callback.onFrame(ready);});return null;
        });
        try{worker.execute(binding.task);}catch(RejectedExecutionException full){bindings.remove(binding);binding.cancelled=true;callback.onFrame(null);}
        return binding;
    }
    void clear(){for(Binding binding:new ArrayList<>(bindings))binding.cancel();}
    void release(){released=true;clear();worker.shutdown();memory.evictAll();}
    private static Bitmap extract(Context app,Uri uri,long at)throws Exception {
        MediaMetadataRetriever retriever=new MediaMetadataRetriever();
        try{retriever.setDataSource(app,uri);long us=Math.min(at,Long.MAX_VALUE/1000)*1000;
            return Build.VERSION.SDK_INT>=27?retriever.getScaledFrameAtTime(us,MediaMetadataRetriever.OPTION_CLOSEST,256,144)
                    :retriever.getFrameAtTime(us,MediaMetadataRetriever.OPTION_CLOSEST);
        }finally{retriever.release();}
    }
    private static Bitmap bounded(Bitmap bitmap){
        if(bitmap==null||bitmap.isRecycled())return null;
        if(bitmap.getWidth()<=256&&bitmap.getHeight()<=144)return bitmap;
        float factor=Math.min(256f/bitmap.getWidth(),144f/bitmap.getHeight());
        Bitmap small=Bitmap.createScaledBitmap(bitmap,Math.max(1,Math.round(bitmap.getWidth()*factor)),Math.max(1,Math.round(bitmap.getHeight()*factor)),true);
        if(small!=bitmap)bitmap.recycle();return small;
    }
}
