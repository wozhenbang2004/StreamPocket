package dev.streampocket;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.TimeBar;

/** Route blank controller space to canvas gestures while preserving actual controls. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class CanvasPlayerView extends PlayerView {
    private OnTouchListener canvasTouch;
    private boolean controlTouch;
    public CanvasPlayerView(Context context){this(context,null);}
    public CanvasPlayerView(Context context,AttributeSet attrs){super(context,attrs);}
    public void setCanvasTouchListener(OnTouchListener listener){canvasTouch=listener;}
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN)
            controlTouch=hitsControl(this,event.getX(),event.getY());
        if(!controlTouch&&canvasTouch!=null)return canvasTouch.onTouch(this,event);
        return super.dispatchTouchEvent(event);
    }
    private boolean hitsControl(View view,float x,float y){
        if(view.getVisibility()!=VISIBLE||x<0||y<0||x>=view.getWidth()||y>=view.getHeight())return false;
        if(view!=this&&(view instanceof TimeBar||!(view instanceof ViewGroup)&&view.isClickable()))return true;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;
            for(int i=group.getChildCount()-1;i>=0;i--){View child=group.getChildAt(i);
                if(hitsControl(child,x+group.getScrollX()-child.getLeft()-child.getTranslationX(),y+group.getScrollY()-child.getTop()-child.getTranslationY()))return true;}}
        return false;
    }
}
