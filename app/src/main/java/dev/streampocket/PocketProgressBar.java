package dev.streampocket;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.widget.ProgressBar;

/** Small, consistent progress ink independent of device widget resources. */
public final class PocketProgressBar extends ProgressBar {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    public PocketProgressBar(Context c){this(c,null,android.R.attr.progressBarStyleHorizontal);}
    public PocketProgressBar(Context c,AttributeSet attrs,int style){super(c,attrs,style);setMinimumHeight(dp(4));setProgressDrawable(new android.graphics.drawable.ColorDrawable(0));setIndeterminateDrawable(new android.graphics.drawable.ColorDrawable(0));}
    @Override protected synchronized void onDraw(Canvas canvas){
        UiTheme.Palette p=UiTheme.get(getContext());float left=getPaddingLeft(),right=getWidth()-getPaddingRight(),y=getHeight()/2f,half=dp(2);
        paint.setColor(p.line);canvas.drawRoundRect(new RectF(left,y-half,right,y+half),half,half,paint);
        paint.setColor(getProgressTintList()!=null?getProgressTintList().getDefaultColor():p.accent);
        float width=right-left;if(width<=0)return;
        if(isIndeterminate()){
            boolean motion=UiTheme.motionEnabled(getContext());float t=motion?(android.os.SystemClock.uptimeMillis()%1400)/1400f:.5f;
            float start=left+width*(t*1.3f-.3f),end=start+width*.3f;canvas.save();canvas.clipRect(left,y-half,right,y+half);canvas.drawRoundRect(new RectF(start,y-half,end,y+half),half,half,paint);canvas.restore();if(motion&&isShown())postInvalidateOnAnimation();
        }else canvas.drawRoundRect(new RectF(left,y-half,left+width*Math.max(0,Math.min(1,getProgress()/(float)Math.max(1,getMax()))),y+half),half,half,paint);
    }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
