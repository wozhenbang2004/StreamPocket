package dev.streampocket;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import java.util.ArrayList;
import java.util.List;

/** Object-local anchored actions, with the same surfaces and targets as sheets. */
public final class PocketMenu {
    public interface OnMenuItemClickListener{boolean onMenuItemClick(Item item);}
    public interface OnDismissListener{void onDismiss(PocketMenu menu);}
    public static final class Item{private final String title;private OnMenuItemClickListener click;Item(String title){this.title=title;}public CharSequence getTitle(){return title;}public Item setOnMenuItemClickListener(OnMenuItemClickListener value){click=value;return this;}}
    public final class Menu{public Item add(String title){Item item=new Item(title);items.add(item);return item;}}
    private final Context context;private final View anchor;private final List<Item> items=new ArrayList<>();private final Menu menu=new Menu();
    private OnMenuItemClickListener click;private OnDismissListener dismiss;private PopupWindow window;
    public PocketMenu(Context context,View anchor){this.context=context;this.anchor=anchor;}
    public Menu getMenu(){return menu;}public void setOnMenuItemClickListener(OnMenuItemClickListener listener){click=listener;}public void setOnDismissListener(OnDismissListener listener){dismiss=listener;}
    public void show(){
        UiTheme.Palette p=UiTheme.get(context);LinearLayout content=new LinearLayout(context);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(6),dp(6),dp(6),dp(6));
        GradientDrawable bg=new GradientDrawable();bg.setColor(p.surface);bg.setCornerRadius(dp(16));bg.setStroke(dp(1),p.line);content.setBackground(bg);
        int width=Math.min(dp(260),context.getResources().getDisplayMetrics().widthPixels-dp(32));
        for(Item item:items){PocketButton b=PocketButton.of(context,item.title,PocketButton.Role.QUIET);b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);if(item.title.contains("删除")||item.title.contains("移除"))b.setTextColor(p.danger);b.setOnClickListener(v->{window.dismiss();OnMenuItemClickListener action=item.click!=null?item.click:click;if(action!=null)action.onMenuItemClick(item);});content.addView(b,new LinearLayout.LayoutParams(-1,dp(48)));}
        android.graphics.Rect visible=new android.graphics.Rect();anchor.getWindowVisibleDisplayFrame(visible);int maxHeight=Math.max(dp(96),(visible.height()>0?visible.height():context.getResources().getDisplayMetrics().heightPixels)-dp(32));
        android.widget.ScrollView scroll=new android.widget.ScrollView(context);scroll.setFillViewport(false);scroll.addView(content);
        window=new PopupWindow(scroll,width,Math.min(dp(12+48*items.size()),maxHeight),true);window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));window.setOutsideTouchable(true);window.setElevation(0);window.setOnDismissListener(()->{if(dismiss!=null)dismiss.onDismiss(this);});
        content.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(context.getResources().getDisplayMetrics().heightPixels-dp(32),View.MeasureSpec.AT_MOST));
        int[] point=new int[2];anchor.getLocationOnScreen(point);int x=Math.max(dp(12),Math.min(point[0]+anchor.getWidth()-width,context.getResources().getDisplayMetrics().widthPixels-width-dp(12)));
        int below=point[1]+anchor.getHeight()+dp(6),height=Math.min(content.getMeasuredHeight(),maxHeight);int y=below+height<(visible.bottom>0?visible.bottom:context.getResources().getDisplayMetrics().heightPixels)-dp(12)?below:Math.max(visible.top+dp(12),point[1]-height-dp(6));
        window.showAtLocation(anchor.getRootView(),Gravity.TOP|Gravity.START,x,y);UiTheme.enter(content);
    }
    private int dp(float n){return Math.round(n*context.getResources().getDisplayMetrics().density);}
}
