package dev.streampocket;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;

/** Playback marks share the stroke and sizing of the existing pocket icons. */
final class PlayerMenuIcon extends Drawable {
    private final String name;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    static Drawable create(String name,int color){
        return "repeat".equals(name)||"tracks".equals(name)||"pip".equals(name)||"next".equals(name)||"split".equals(name)?new PlayerMenuIcon(name,color):new PocketIcon(name,color);
    }
    private PlayerMenuIcon(String name,int color){this.name=name;paint.setColor(color);paint.setStrokeWidth(1.9f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStyle(Paint.Style.STROKE);}
    @Override public void draw(Canvas canvas){
        canvas.save();canvas.translate(getBounds().left,getBounds().top);canvas.scale(getBounds().width()/24f,getBounds().height()/24f);
        if("repeat".equals(name)){
            Path top=new Path();top.moveTo(4,10);top.lineTo(4,7);top.quadTo(4,5,6,5);top.lineTo(20,5);top.lineTo(17,2);canvas.drawPath(top,paint);canvas.drawLine(20,5,17,8,paint);
            Path bottom=new Path();bottom.moveTo(20,14);bottom.lineTo(20,17);bottom.quadTo(20,19,18,19);bottom.lineTo(4,19);bottom.lineTo(7,22);canvas.drawPath(bottom,paint);canvas.drawLine(4,19,7,16,paint);
        }else if("tracks".equals(name)){
            canvas.drawRoundRect(2,4,22,20,3,3,paint);canvas.drawLine(6,10,11,10,paint);canvas.drawLine(15,10,18,10,paint);canvas.drawLine(6,15,9,15,paint);canvas.drawLine(13,15,18,15,paint);
        }else if("pip".equals(name)){
            canvas.drawRoundRect(2,4,22,20,2,2,paint);canvas.drawRoundRect(12,11,19,17,1,1,paint);
        }else if("split".equals(name)){
            canvas.drawRoundRect(2,4,22,20,2,2,paint);canvas.drawLine(12,4,12,20,paint);
        }else{
            Path next=new Path();next.moveTo(5,4);next.lineTo(16,12);next.lineTo(5,20);next.close();canvas.drawPath(next,paint);canvas.drawLine(20,4,20,20,paint);
        }
        canvas.restore();
    }
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);}
    @Override public void setColorFilter(android.graphics.ColorFilter filter){paint.setColorFilter(filter);}
    @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
}
