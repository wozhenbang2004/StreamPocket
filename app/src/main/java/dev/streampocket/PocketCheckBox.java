package dev.streampocket;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.CheckBox;

/** The same compact choice mark as the app's sheets, without a device checkbox skin. */
@android.annotation.SuppressLint("AppCompatCustomView")
final class PocketCheckBox extends CheckBox {
    PocketCheckBox(Context context) {
        super(context, null, 0);
        UiTheme.Palette palette=UiTheme.get(context);
        StateListDrawable states=new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_checked},new Mark(palette,true));
        states.addState(new int[]{},new Mark(palette,false));
        setButtonDrawable(states);setButtonTintList(null);setBackgroundColor(android.graphics.Color.TRANSPARENT);
        setMinWidth(0);setMinimumWidth(0);setMinHeight(dp(48));setMinimumHeight(dp(48));
        setPadding(dp(10),0,dp(10),0);setGravity(android.view.Gravity.CENTER_VERTICAL);
        setClickable(true);setFocusable(true);
    }
    @Override public boolean performClick(){return isEnabled()&&super.performClick();}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}

    private final class Mark extends Drawable {
        private final UiTheme.Palette palette;private final boolean checked;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final PocketIcon check;
        Mark(UiTheme.Palette palette,boolean checked){this.palette=palette;this.checked=checked;check=new PocketIcon("check",palette.bg);}
        @Override public void draw(Canvas canvas){
            int saved=canvas.save();canvas.translate(getBounds().left,getBounds().top);canvas.scale(getBounds().width()/20f,getBounds().height()/20f);
            paint.setColor(checked?palette.ink:palette.muted);paint.setStrokeWidth(1.5f);paint.setStyle(checked?Paint.Style.FILL:Paint.Style.STROKE);
            canvas.drawRoundRect(1.5f,1.5f,18.5f,18.5f,5,5,paint);
            if(checked){check.setBounds(3,3,17,17);check.draw(canvas);}canvas.restoreToCount(saved);
        }
        @Override public int getIntrinsicWidth(){return dp(20);}
        @Override public int getIntrinsicHeight(){return dp(20);}
        @Override public void setAlpha(int value){paint.setAlpha(value);check.setAlpha(value);invalidateSelf();}
        @Override public void setColorFilter(android.graphics.ColorFilter value){paint.setColorFilter(value);check.setColorFilter(value);invalidateSelf();}
        @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    }
}
