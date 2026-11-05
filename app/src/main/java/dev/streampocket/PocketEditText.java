package dev.streampocket;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.ViewGroup;
import android.widget.EditText;

/** Consistent field surface and inline validation, without a platform error popup. */
// Framework Activity/theme host; every visual state is owned here, with no AppCompat widget contract.
@android.annotation.SuppressLint("AppCompatCustomView")
public final class PocketEditText extends EditText {
    private CharSequence inlineError;
    private int originalHeight=Integer.MIN_VALUE,errorHeight;
    private final TextPaint errorPaint=new TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    public PocketEditText(Context context){
        super(context,null,android.R.attr.editTextStyle);
        setFocusable(true);setFocusableInTouchMode(true);setCursorVisible(true);setShowSoftInputOnFocus(true);
        setTextSize(14);setPadding(dp(12),dp(12),dp(12),dp(12));setMinHeight(dp(48));skin(UiTheme.get(context));
        errorPaint.setTextSize(12*getResources().getDisplayMetrics().scaledDensity);
    }
    void dark(){skin(UiTheme.darkPalette());}
    private void skin(UiTheme.Palette p){setTextColor(p.ink);setHintTextColor(p.muted);StateListDrawable states=new StateListDrawable();states.addState(new int[]{android.R.attr.state_focused},shape(p.raised,p.accent));states.addState(new int[]{},shape(p.control,0));setBackground(states);if(errorPaint!=null)errorPaint.setColor(p.danger);}
    private GradientDrawable shape(int fill,int line){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(12));if(line!=0)d.setStroke(dp(1),line);return d;}
    @Override public void setBackgroundTintList(ColorStateList ignored){/* The field states own their colors. */}
    @Override public void setError(CharSequence error){setError(error,null);}
    @Override public void setError(CharSequence error,Drawable ignored){
        super.setError(null,null);inlineError=error;
        if(errorPaint==null)return;
        errorHeight=error==null?0:Math.max(dp(36),(int)Math.ceil(errorPaint.getFontSpacing()*2)+dp(6));ViewGroup.LayoutParams params=getLayoutParams();
        if(params!=null){if(originalHeight==Integer.MIN_VALUE)originalHeight=params.height;if(originalHeight>=0){params.height=originalHeight+errorHeight;setLayoutParams(params);}}
        setMinHeight(dp(48)+errorHeight);setPadding(getPaddingLeft(),getPaddingTop(),getPaddingRight(),dp(12)+errorHeight);invalidate();
        if(error!=null)announceForAccessibility(error);
    }
    @Override public CharSequence getError(){return inlineError;}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);if(inlineError==null)return;
        int width=Math.max(1,getWidth()-getPaddingLeft()-getPaddingRight());StaticLayout layout=StaticLayout.Builder.obtain(inlineError,0,inlineError.length(),errorPaint,width).setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(2).setEllipsize(android.text.TextUtils.TruncateAt.END).build();
        canvas.save();canvas.translate(getScrollX()+getPaddingLeft(),getScrollY()+getHeight()-errorHeight);layout.draw(canvas);canvas.restore();
    }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
