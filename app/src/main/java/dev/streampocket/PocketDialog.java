package dev.streampocket;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shared custom sheet for short decisions, selections and bounded forms. */
public final class PocketDialog extends Dialog {
    public static final int BUTTON_POSITIVE=-1,BUTTON_NEGATIVE=-2,BUTTON_NEUTRAL=-3;
    private final Builder spec;
    private final SparseArray<Button> buttons=new SparseArray<>();
    private ListView list;
    private LinearLayout surface;
    private boolean cancellable=true;
    private float downY;
    private int selected;
    private PocketDialog(Builder spec){super(spec.context);this.spec=spec;selected=spec.selected;requestWindowFeature(Window.FEATURE_NO_TITLE);}
    public Button getButton(int which){return buttons.get(which);}
    public ListView getListView(){return list;}
    public CharSequence getMessage(){return spec.message;}
    public CharSequence getTitle(){return spec.title;}
    @Override public void setCancelable(boolean value){cancellable=value;super.setCancelable(value);}
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);UiTheme.Palette p=spec.dark?UiTheme.darkPalette():UiTheme.get(getContext());
        surface=new LinearLayout(getContext()){@Override protected void onMeasure(int w,int h){android.graphics.Rect visible=new android.graphics.Rect();getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);int available=visible.height()>0?visible.height():getResources().getDisplayMetrics().heightPixels;int limit=Math.min(MeasureSpec.getSize(h)>0?MeasureSpec.getSize(h):available,available-dp(20));super.onMeasure(w,MeasureSpec.makeMeasureSpec(Math.max(dp(120),limit),MeasureSpec.AT_MOST));}};surface.setOrientation(LinearLayout.VERTICAL);surface.setPadding(dp(20),dp(10),dp(20),dp(18));
        GradientDrawable panel=new GradientDrawable();panel.setColor(p.surface);panel.setCornerRadius(dp(24));panel.setStroke(dp(1),p.line);surface.setBackground(panel);
        LinearLayout header=new LinearLayout(getContext());header.setOrientation(LinearLayout.VERTICAL);
        View handle=new View(getContext());GradientDrawable grip=new GradientDrawable();grip.setColor(p.line);grip.setCornerRadius(dp(2));handle.setBackground(grip);LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(dp(32),dp(4));hp.gravity=Gravity.CENTER_HORIZONTAL;hp.bottomMargin=dp(8);header.addView(handle,hp);
        LinearLayout titleRow=new LinearLayout(getContext());titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text(spec.title==null?"":spec.title,18,p.ink,true);title.setMaxLines(2);title.setPadding(0,dp(8),dp(8),dp(12));titleRow.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button close=spec.dark?PocketButton.dark(getContext(),"",PocketButton.Role.QUIET):PocketButton.of(getContext(),"",PocketButton.Role.QUIET);PocketIcon icon=new PocketIcon("close",p.muted);icon.setBounds(0,0,dp(20),dp(20));close.setCompoundDrawables(icon,null,null,null);close.setPadding(dp(14),0,dp(14),0);close.setContentDescription("关闭面板");close.setOnClickListener(v->{if(cancellable)cancel();});titleRow.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48)));header.addView(titleRow);
        surface.addView(header,new LinearLayout.LayoutParams(-1,-2));header.setOnTouchListener((v,e)->drag(e));
        if(spec.items!=null){
            if(spec.message!=null){TextView note=text(spec.message,13,p.muted,false);note.setPadding(0,0,0,dp(10));surface.addView(note,new LinearLayout.LayoutParams(-1,-2));}
            buildList(p);
        }
        else if(spec.custom!=null||spec.message!=null){
            BoundedScroll scroll=new BoundedScroll(getContext());scroll.setFillViewport(false);scroll.setClipToPadding(false);
            View content=spec.custom;
            if(content==null){TextView message=text(spec.message,14,p.muted,false);message.setTextIsSelectable(true);message.setPadding(0,0,0,dp(12));content=message;}
            if(content.getParent() instanceof ViewGroup)((ViewGroup)content.getParent()).removeView(content);
            if(spec.dark)darkFields(content);
            scroll.addView(content,new ScrollView.LayoutParams(-1,-2));surface.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        }
        LinearLayout actions=new LinearLayout(getContext());int count=0;for(int key:new int[]{BUTTON_NEGATIVE,BUTTON_NEUTRAL,BUTTON_POSITIVE})if(spec.labels.get(key)!=null)count++;
        actions.setOrientation(count>2?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);actions.setPadding(0,dp(14),0,0);
        int index=0;for(int key:new int[]{BUTTON_NEGATIVE,BUTTON_NEUTRAL,BUTTON_POSITIVE}){
            CharSequence label=spec.labels.get(key);if(label==null)continue;
            boolean closing=label.toString().matches("返回|关闭|知道了|完成");
            PocketButton.Role role=key==BUTTON_POSITIVE&&!closing?(label.toString().contains("删除")?PocketButton.Role.DANGER:PocketButton.Role.PRIMARY):PocketButton.Role.QUIET;
            Button b=spec.dark?PocketButton.dark(getContext(),label.toString(),role):PocketButton.of(getContext(),label.toString(),role);buttons.put(key,b);final int which=key;
            b.setOnClickListener(v->{DialogInterface.OnClickListener callback=spec.clicks.get(which);if(callback!=null)callback.onClick(this,which);dismiss();});
            LinearLayout.LayoutParams ap=count>2?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(0,-2,1);if(index++>0){if(count>2)ap.topMargin=dp(6);else ap.leftMargin=dp(8);}actions.addView(b,ap);
        }
        if(count>0)surface.addView(actions,new LinearLayout.LayoutParams(-1,-2));setContentView(surface);
        setCancelable(spec.cancelable);setCanceledOnTouchOutside(spec.cancelable);if(spec.onCancel!=null)setOnCancelListener(spec.onCancel);
        Window window=getWindow();if(window!=null){window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));window.setGravity(Gravity.BOTTOM);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.32f);window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);window.setWindowAnimations(0);}
    }
    @Override public void show(){super.show();Window w=getWindow();if(w!=null)w.setLayout(Math.min(getContext().getResources().getDisplayMetrics().widthPixels-dp(20),dp(560)),WindowManager.LayoutParams.WRAP_CONTENT);if(surface!=null)UiTheme.enter(surface);}
    private boolean drag(MotionEvent e){
        if(!cancellable)return false;
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){downY=e.getRawY();surface.animate().cancel();return true;}
        if(e.getActionMasked()==MotionEvent.ACTION_MOVE){float dy=Math.max(0,e.getRawY()-downY);surface.setTranslationY(dy);if(getWindow()!=null)getWindow().setDimAmount(.32f*(1-Math.min(1,dy/Math.max(1,surface.getHeight()))));return true;}
        if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){if(e.getActionMasked()==MotionEvent.ACTION_UP&&surface.getTranslationY()>dp(80)){cancel();return true;}surface.animate().translationY(0).setDuration(UiTheme.motionEnabled(getContext())?180:0).start();if(getWindow()!=null)getWindow().setDimAmount(.32f);return true;}return true;
    }
    private void buildList(UiTheme.Palette p){
        list=new BoundedList(getContext());list.setDivider(null);list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setChoiceMode(spec.multi!=null?ListView.CHOICE_MODE_MULTIPLE:spec.single?ListView.CHOICE_MODE_SINGLE:ListView.CHOICE_MODE_NONE);
        BaseAdapter adapter=new BaseAdapter(){
            public int getCount(){return spec.items.length;}public Object getItem(int at){return spec.items[at];}public long getItemId(int at){return at;}
            public View getView(int at,View old,ViewGroup parent){
                TextView row=text(spec.items[at],14,p.ink,false);row.setGravity(Gravity.CENTER_VERTICAL);row.setMinHeight(dp(54));row.setPadding(dp(12),dp(12),dp(12),dp(12));
                boolean chosen=spec.multi!=null?spec.multi[at]:spec.single&&at==selected;
                GradientDrawable bg=new GradientDrawable();bg.setCornerRadius(dp(12));bg.setColor(chosen?p.accentSoft:Color.TRANSPARENT);row.setBackground(bg);row.setSelected(chosen);
                if(spec.single||spec.multi!=null){ChoiceMark mark=new ChoiceMark(p,chosen,spec.multi!=null);mark.setBounds(0,0,dp(20),dp(20));row.setCompoundDrawablesRelative(null,null,mark,null);row.setCompoundDrawablePadding(dp(12));}
                row.setContentDescription(spec.items[at]+(chosen?"，已选中":""));return row;
            }
        };list.setAdapter(adapter);if(spec.single&&selected>=0)list.setItemChecked(selected,true);if(spec.multi!=null)for(int n=0;n<spec.multi.length;n++)list.setItemChecked(n,spec.multi[n]);
        list.setOnItemClickListener((parent,view,at,id)->{
            if(spec.multi!=null){spec.multi[at]=!spec.multi[at];list.setItemChecked(at,spec.multi[at]);adapter.notifyDataSetChanged();if(spec.multiClick!=null)spec.multiClick.onClick(this,at,spec.multi[at]);}
            else{selected=at;adapter.notifyDataSetChanged();if(spec.itemClick!=null)spec.itemClick.onClick(this,at);if(!spec.single)dismiss();}
        });
        if(spec.multi!=null&&spec.bulkActions){
            LinearLayout choices=new LinearLayout(getContext());choices.setGravity(Gravity.CENTER_VERTICAL);
            choices.addView(text("批量选择",12,p.muted,false),new LinearLayout.LayoutParams(0,-2,1));
            for(boolean checked:new boolean[]{true,false}){
                Button action=PocketButton.of(getContext(),checked?"全选":"清空",PocketButton.Role.QUIET);action.setTextSize(12);
                action.setOnClickListener(v->{for(int n=0;n<spec.multi.length;n++){boolean changed=spec.multi[n]!=checked;spec.multi[n]=checked;list.setItemChecked(n,checked);if(changed&&spec.multiClick!=null)spec.multiClick.onClick(this,n,checked);}adapter.notifyDataSetChanged();});
                choices.addView(action,new LinearLayout.LayoutParams(dp(48),dp(48)));
            }
            surface.addView(choices,new LinearLayout.LayoutParams(-1,-2));
        }
        surface.addView(list,new LinearLayout.LayoutParams(-1,0,1));
    }
    private TextView text(CharSequence value,int size,int color,boolean bold){TextView v=new TextView(getContext());v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setLineSpacing(dp(3),1);if(bold)v.setTypeface(null,android.graphics.Typeface.BOLD);return v;}
    private void darkFields(View view){if(view instanceof PocketEditText)((PocketEditText)view).dark();if(view instanceof ViewGroup)for(int n=0;n<((ViewGroup)view).getChildCount();n++)darkFields(((ViewGroup)view).getChildAt(n));}
    private int dp(float n){return Math.round(n*getContext().getResources().getDisplayMetrics().density);}
    private int boundedHeight(int spec){if(View.MeasureSpec.getMode(spec)==View.MeasureSpec.EXACTLY)return spec;int cap=(int)(getContext().getResources().getDisplayMetrics().heightPixels*.6f);if(View.MeasureSpec.getMode(spec)==View.MeasureSpec.AT_MOST)cap=Math.min(cap,View.MeasureSpec.getSize(spec));return View.MeasureSpec.makeMeasureSpec(cap,View.MeasureSpec.AT_MOST);}
    private final class BoundedScroll extends ScrollView{BoundedScroll(Context c){super(c);}@Override protected void onMeasure(int w,int h){super.onMeasure(w,boundedHeight(h));}}
    private final class BoundedList extends ListView{BoundedList(Context c){super(c);}@Override protected void onMeasure(int w,int h){super.onMeasure(w,boundedHeight(h));}}
    private static final class ChoiceMark extends Drawable{
        private final UiTheme.Palette palette;private final boolean checked,multi;private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        ChoiceMark(UiTheme.Palette palette,boolean checked,boolean multi){this.palette=palette;this.checked=checked;this.multi=multi;}
        @Override public void draw(Canvas canvas){canvas.save();canvas.translate(getBounds().left,getBounds().top);float scale=getBounds().width()/20f;canvas.scale(scale,scale);p.setStrokeWidth(1.7f);p.setStyle(Paint.Style.STROKE);p.setColor(checked?palette.accent:palette.muted);if(multi)canvas.drawRoundRect(2,2,18,18,4,4,p);else canvas.drawCircle(10,10,7,p);if(checked){if(multi){p.setStrokeCap(Paint.Cap.ROUND);canvas.drawLine(5,10,9,14,p);canvas.drawLine(9,14,15,6,p);}else{p.setStyle(Paint.Style.FILL);canvas.drawCircle(10,10,3.5f,p);}}canvas.restore();}
        @Override public void setAlpha(int alpha){p.setAlpha(alpha);}@Override public void setColorFilter(android.graphics.ColorFilter filter){p.setColorFilter(filter);}@Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    }
    public static final class Builder{
        private final Context context;private CharSequence title,message;private View custom;private CharSequence[] items;
        private boolean single,cancelable=true,dark,bulkActions;private int selected=-1;private boolean[] multi;
        private DialogInterface.OnClickListener itemClick;private DialogInterface.OnMultiChoiceClickListener multiClick;private DialogInterface.OnCancelListener onCancel;
        private final SparseArray<CharSequence> labels=new SparseArray<>();private final SparseArray<DialogInterface.OnClickListener> clicks=new SparseArray<>();
        public Builder(Context c){context=c;}public Builder(Context c,int theme){this(c);dark=theme==android.R.style.Theme_Material_Dialog_Alert;}
        public Builder setTitle(CharSequence value){title=value;return this;}public Builder setMessage(CharSequence value){message=value;return this;}public Builder setView(View value){custom=value;return this;}
        public Builder setItems(CharSequence[] value,DialogInterface.OnClickListener click){items=value;itemClick=click;return this;}
        public Builder setSingleChoiceItems(CharSequence[] value,int checked,DialogInterface.OnClickListener click){items=value;single=true;selected=checked;itemClick=click;return this;}
        public Builder setMultiChoiceItems(CharSequence[] value,boolean[] checked,DialogInterface.OnMultiChoiceClickListener click){items=value;multi=checked;multiClick=click;return this;}
        public Builder setMultiChoiceBulkActions(boolean enabled){bulkActions=enabled;return this;}
        public Builder setPositiveButton(CharSequence label,DialogInterface.OnClickListener click){labels.put(BUTTON_POSITIVE,label);clicks.put(BUTTON_POSITIVE,click);return this;}
        public Builder setNegativeButton(CharSequence label,DialogInterface.OnClickListener click){labels.put(BUTTON_NEGATIVE,label);clicks.put(BUTTON_NEGATIVE,click);return this;}
        public Builder setNeutralButton(CharSequence label,DialogInterface.OnClickListener click){labels.put(BUTTON_NEUTRAL,label);clicks.put(BUTTON_NEUTRAL,click);return this;}
        public Builder setCancelable(boolean value){cancelable=value;return this;}public Builder setOnCancelListener(DialogInterface.OnCancelListener listener){onCancel=listener;return this;}
        public PocketDialog create(){return new PocketDialog(this);}public PocketDialog show(){PocketDialog dialog=create();dialog.show();return dialog;}
    }
}
