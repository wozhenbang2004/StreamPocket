package dev.streampocket;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.widget.RadioButton;

/** RadioGroup semantics with an application-owned indicator and whole-row target. */
// Framework Activity/theme host; every visual state is owned here, with no AppCompat widget contract.
@android.annotation.SuppressLint("AppCompatCustomView")
public final class PocketRadio extends RadioButton {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    public PocketRadio(Context context){super(context,null,0);setButtonDrawable(null);setGravity(android.view.Gravity.START|android.view.Gravity.CENTER_VERTICAL);setPadding(dp(40),dp(8),dp(10),dp(8));setMinHeight(dp(52));setTextSize(14);setTextColor(UiTheme.get(context).ink);setBackgroundColor(android.graphics.Color.TRANSPARENT);}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);UiTheme.Palette p=UiTheme.get(getContext());paint.setColor(isChecked()?p.accent:p.muted);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.5f));canvas.drawCircle(dp(16),getHeight()/2f,dp(8),paint);paint.setStyle(Paint.Style.FILL);if(isChecked())canvas.drawCircle(dp(16),getHeight()/2f,dp(4),paint);}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
