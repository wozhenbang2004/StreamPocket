package dev.streampocket;

import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import java.util.function.Consumer;

/** Reveal one non-destructive action. A swipe never executes it; an explicit tap does. */
final class SwipeActionRow extends FrameLayout {
    private final View front,action;
    private final int revealWidth,slop;
    private float downX,downY,start;
    private boolean dragging,revealed,vertical;
    private VelocityTracker velocity;
    private Consumer<Boolean> interaction=touching->{};
    SwipeActionRow(Context context,View content,View action) {
        super(context);front=content;this.action=action;action.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);revealWidth=Math.round(88*getResources().getDisplayMetrics().density);
        slop=ViewConfiguration.get(context).getScaledTouchSlop();setClipToOutline(true);
        setBackground(content.getBackground().getConstantState().newDrawable());
        addView(action,new LayoutParams(revealWidth,-1,android.view.Gravity.END));addView(content,new LayoutParams(-1,-2));
    }
    void setInteractionListener(Consumer<Boolean> listener){interaction=listener;}
    boolean isRevealed(){return revealed||front.getTranslationX()<-slop;}
    void close(boolean animate){settle(false,animate);}
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){
            front.animate().cancel();downX=event.getX();downY=event.getY();start=front.getTranslationX();dragging=false;vertical=false;
            recycle();velocity=VelocityTracker.obtain();interaction.accept(true);
        }
        if(velocity!=null)velocity.addMovement(event);
        boolean result=super.dispatchTouchEvent(event);
        if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){interaction.accept(false);recycle();}
        return result;
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&!vertical){
            float dx=event.getX()-downX,dy=event.getY()-downY;
            if(!dragging&&Math.abs(dy)>slop&&Math.abs(dy)>Math.abs(dx)){vertical=true;close(false);return false;}
            if(dragging||(Math.abs(dx)>slop&&Math.abs(dx)>Math.abs(dy)*1.3f)){
                dragging=true;front.setTranslationX(Math.max(-revealWidth,Math.min(0,start+dx)));getParent().requestDisallowInterceptTouchEvent(true);return true;
            }
        }
        // A tap on the shifted content closes it instead of opening the item below it.
        return event.getActionMasked()==MotionEvent.ACTION_DOWN&&revealed&&event.getX()<getWidth()-revealWidth;
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        if(event.getPointerCount()>1){settle(revealed,true);dragging=false;vertical=true;return true;}
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_MOVE:
                if(!vertical){dragging=true;front.setTranslationX(Math.max(-revealWidth,Math.min(0,start+event.getX()-downX)));}return true;
            case MotionEvent.ACTION_UP:
                if(dragging){float vx=0;if(velocity!=null){velocity.computeCurrentVelocity(1000);vx=velocity.getXVelocity();}
                    settle(Math.abs(vx)>600? vx<0 : front.getTranslationX()<-revealWidth*.45f,true);
                }else close(true);dragging=false;return true;
            case MotionEvent.ACTION_CANCEL:settle(revealed,true);dragging=false;return true;
            default:return true;
        }
    }
    private void settle(boolean open,boolean animate){
        revealed=open;action.setImportantForAccessibility(open?View.IMPORTANT_FOR_ACCESSIBILITY_AUTO:View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);front.animate().cancel();float x=open?-revealWidth:0;
        if(animate&&UiTheme.motionEnabled(getContext()))front.animate().translationX(x).setDuration(180).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        else front.setTranslationX(x);
    }
    private void recycle(){if(velocity!=null){velocity.recycle();velocity=null;}}
    @Override protected void onDetachedFromWindow(){front.animate().cancel();recycle();interaction.accept(false);super.onDetachedFromWindow();}
}
