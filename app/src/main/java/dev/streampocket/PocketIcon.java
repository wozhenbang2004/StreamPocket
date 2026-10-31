package dev.streampocket;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;

/** Small vector marks with one stroke grammar, independent of font glyphs. */
final class PocketIcon extends Drawable {
    private final String name;private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    PocketIcon(String name,int color){this.name=name;p.setColor(color);p.setStrokeWidth(1.9f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);p.setStyle(Paint.Style.STROKE);}
    @Override public void draw(Canvas c){c.save();c.translate(getBounds().left,getBounds().top);c.scale(getBounds().width()/24f,getBounds().height()/24f);
        if(name.equals("close")){c.drawLine(6,6,18,18,p);c.drawLine(18,6,6,18,p);}
        else if(name.equals("back")){c.drawLine(14,5,7,12,p);c.drawLine(7,12,14,19,p);}
        else if(name.equals("down")){c.drawLine(6,9,12,15,p);c.drawLine(12,15,18,9,p);}
        else if(name.equals("chevron")){c.drawLine(9,6,15,12,p);c.drawLine(15,12,9,18,p);}
        else if(name.equals("more")){p.setStyle(Paint.Style.FILL);for(int x:new int[]{5,12,19})c.drawCircle(x,12,1.5f,p);p.setStyle(Paint.Style.STROKE);}
        else if(name.equals("source")){c.drawRoundRect(3,4,21,20,4,4,p);c.drawLine(8,8,16,12,p);c.drawLine(16,12,8,16,p);c.drawLine(8,16,8,8,p);}
        else if(name.equals("collection")){c.drawRoundRect(3,7,21,21,3,3,p);c.drawLine(7,3,17,3,p);c.drawLine(5,5,19,5,p);}
        else if(name.equals("bookmark")){c.drawRoundRect(5,3,19,21,2,2,p);c.drawLine(9,10,15,10,p);c.drawLine(12,7,12,13,p);}
        else if(name.equals("scissors")){c.drawCircle(6,6,3,p);c.drawCircle(6,18,3,p);c.drawLine(8,8,21,19,p);c.drawLine(8,16,21,4,p);}
        else if(name.equals("info")){c.drawCircle(12,12,9,p);c.drawLine(12,11,12,17,p);c.drawPoint(12,7,p);}
        else if(name.equals("crop")){c.drawRoundRect(5,3,19,21,2,2,p);c.drawRoundRect(3,6,21,18,2,2,p);}
        else if(name.equals("gesture")){c.drawLine(8,20,4,12,p);c.drawLine(4,12,8,12,p);c.drawLine(8,12,8,5,p);c.drawRoundRect(8,3,12,16,2,2,p);c.drawLine(12,9,20,11,p);c.drawLine(20,11,18,21,p);c.drawLine(18,21,9,21,p);}
        else if(name.equals("download")){c.drawLine(12,3,12,15,p);c.drawLine(7,10,12,15,p);c.drawLine(12,15,17,10,p);c.drawLine(5,17,5,21,p);c.drawLine(5,21,19,21,p);c.drawLine(19,21,19,17,p);}
        c.restore();}
    @Override public void setAlpha(int alpha){p.setAlpha(alpha);}@Override public void setColorFilter(android.graphics.ColorFilter filter){p.setColorFilter(filter);}@Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
}
