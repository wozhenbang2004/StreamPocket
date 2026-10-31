package dev.streampocket;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

/** Full-frame foreground, with a tiny blurred backdrop only in letterboxed space. */
@android.annotation.SuppressLint("AppCompatCustomView")
final class LibraryCoverView extends ImageView {
    private Bitmap backdrop;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final RectF bounds=new RectF();
    private final android.graphics.Path outline=new android.graphics.Path();
    LibraryCoverView(Context context){super(context,null,0);setScaleType(ScaleType.FIT_CENTER);}
    @Override public void setImageDrawable(Drawable drawable){
        backdrop=null;
        if(drawable instanceof BitmapDrawable){Bitmap source=((BitmapDrawable)drawable).getBitmap();if(source!=null&&!source.isRecycled()){
            Bitmap tiny=Bitmap.createBitmap(20,20,Bitmap.Config.ARGB_8888);new Canvas(tiny).drawBitmap(source,null,new RectF(0,0,20,20),new Paint(Paint.FILTER_BITMAP_FLAG));
            int[] from=new int[400],to=new int[400];tiny.getPixels(from,0,20,0,0,20,20);
            for(int pass=0;pass<2;pass++){for(int y=0;y<20;y++)for(int x=0;x<20;x++){int red=0,green=0,blue=0,n=0;for(int yy=Math.max(0,y-2);yy<=Math.min(19,y+2);yy++)for(int xx=Math.max(0,x-2);xx<=Math.min(19,x+2);xx++){int c=from[yy*20+xx];red+=(c>>16)&255;green+=(c>>8)&255;blue+=c&255;n++;}to[y*20+x]=0xff000000|(red/n)<<16|(green/n)<<8|blue/n;}int[] swap=from;from=to;to=swap;}
            tiny.setPixels(from,0,20,0,0,20,20);backdrop=tiny;
        }}super.setImageDrawable(drawable);
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);outline.reset();outline.addRoundRect(0,0,w,h,12*getResources().getDisplayMetrics().density,12*getResources().getDisplayMetrics().density,android.graphics.Path.Direction.CW);}
    @Override protected void onDraw(Canvas canvas){int save=canvas.save();canvas.clipPath(outline);if(backdrop!=null){bounds.set(0,0,getWidth(),getHeight());canvas.drawBitmap(backdrop,null,bounds,paint);canvas.drawColor(UiTheme.get(getContext()).dark?0x6A0A0C0F:0x55FFFFFF);}super.onDraw(canvas);canvas.restoreToCount(save);}
}
