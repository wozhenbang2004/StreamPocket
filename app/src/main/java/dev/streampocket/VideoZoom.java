package dev.streampocket;

import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import androidx.media3.ui.PlayerView;

/** Scale only the video surface; controls stay readable and retain their hit areas. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class VideoZoom {
    private final PlayerView player;
    private final Runnable changed;
    private final ScaleGestureDetector detector;
    private float scale=1f,x,y,lastX,lastY,downX,downY;
    private boolean multi,pan,zoomDown;
    VideoZoom(PlayerView player,Runnable changed){
        this.player=player;this.changed=changed;
        detector=new ScaleGestureDetector(player.getContext(),new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector d){
                float old=scale;scale=Math.max(1f,Math.min(5f,scale*d.getScaleFactor()));
                View surface=player.getVideoSurfaceView();
                if(surface!=null){float fx=d.getFocusX()-surface.getLeft()-surface.getWidth()/2f;
                    float fy=d.getFocusY()-surface.getTop()-surface.getHeight()/2f;
                    x=fx-(fx-x)*scale/old;y=fy-(fy-y)*scale/old;}
                apply();return true;
            }
        });detector.setQuickScaleEnabled(false);
        player.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->apply());
    }
    boolean transformed(){return scale>1.01f;}
    float scale(){return scale;}
    void reset(){scale=1f;x=y=0;apply();}
    void cancel(){multi=pan=false;}
    boolean touch(MotionEvent e){
        int action=e.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){multi=pan=false;zoomDown=transformed();downX=lastX=e.getX();downY=lastY=e.getY();}
        detector.onTouchEvent(e);
        if(e.getPointerCount()>1||action==MotionEvent.ACTION_POINTER_DOWN)multi=true;
        if(action==MotionEvent.ACTION_POINTER_UP){int remaining=e.getActionIndex()==0?1:0;lastX=e.getX(remaining);lastY=e.getY(remaining);}
        if(action==MotionEvent.ACTION_MOVE&&zoomDown&&Math.hypot(e.getX()-downX,e.getY()-downY)>android.view.ViewConfiguration.get(player.getContext()).getScaledTouchSlop())pan=true;
        if(action==MotionEvent.ACTION_MOVE&&e.getPointerCount()==1&&(pan||multi)&&transformed()){
            x+=e.getX()-lastX;y+=e.getY()-lastY;apply();
        }
        if(action!=MotionEvent.ACTION_POINTER_UP){lastX=e.getX();lastY=e.getY();}boolean consumed=multi||pan||detector.isInProgress();
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)multi=pan=false;
        return consumed;
    }
    private void apply(){
        View surface=player.getVideoSurfaceView();
        if(surface!=null){
            if(!transformed()){scale=1f;x=y=0;}
            float maxX=Math.max(0,(surface.getWidth()*scale-player.getWidth())/2f);
            float maxY=Math.max(0,(surface.getHeight()*scale-player.getHeight())/2f);
            x=Math.max(-maxX,Math.min(maxX,x));y=Math.max(-maxY,Math.min(maxY,y));
            surface.setScaleX(scale);surface.setScaleY(scale);surface.setTranslationX(x);surface.setTranslationY(y);
        }
        changed.run();
    }
}
