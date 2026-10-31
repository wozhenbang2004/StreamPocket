package dev.streampocket;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Native UI. All extraction and disk/network work stays off the main thread. */
public final class MainActivity extends Activity {
    private int BG, INK, MUTED, ACCENT, SURFACE, LINE;
    private UiTheme.Palette palette;
    private MediaArtwork artwork;
    private LinearLayout brand, inspectionResults, notice, linkEditor, resolvedLink, homeExtras;
    private TextView resolvedSource;
    private SwipeActionRow openSwipeRow;
    private boolean queueTouchActive;
    private FrameLayout navigationFrame;
    private View navigationPill;
    private final Button[] destinations=new Button[4];
    private final List<Engine.Media> inspectedMedia=new ArrayList<>();
    private final Map<String,Integer> qualitySelections=new HashMap<>();
    private final Map<String,JSONObject> pendingRemoval=new LinkedHashMap<>();
    private Bundle libraryState;
    private String queueFilter="all";
    private final Runnable commitRemovals=()->commitPendingRemovals(true);
    private static final int COOKIE_REQUEST=51, NOTIFY_REQUEST=52, STORAGE_REQUEST=53, LOGIN_REQUEST=54;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private Engine engine;
    private VideoLibraryView libraryView;
    private LinearLayout root, body, navigation;
    private EditText input;
    private TextView feedback;
    private Button parse, cancel, failureDetails, paste;
    private String lastInspectionMessage="", lastInspectionDetails="";
    private String draft="", tab="new", lastQueueSignature="";
    private boolean parsing=false, loginOpening=false, taskMenuOpen=false;
    private volatile int parseGeneration=0;
    private Engine.Media pendingMedia;
    private Engine.Quality pendingQuality;
    private String pendingRetryId;
    private final Runnable poll=new Runnable() {
        @Override public void run() {
            AppUpdates.get(MainActivity.this).refresh();
            if ("queue".equals(tab)&&!taskMenuOpen&&!queueTouchActive&&(openSwipeRow==null||!openSwipeRow.isRevealed())) refreshQueue(false);
            else if ("library".equals(tab) && libraryView!=null) libraryView.refresh();
            main.postDelayed(this,"library".equals(tab)?3000:800);
        }
    };

    @Override public void onCreate(Bundle state) {
        UiTheme.apply(this); super.onCreate(state);
        palette=UiTheme.get(this); BG=palette.bg;INK=palette.ink;MUTED=palette.muted;ACCENT=palette.accent;SURFACE=palette.surface;LINE=palette.line;
        artwork=new MediaArtwork(this); engine=new Engine(this);
        if(state!=null){
            libraryState=state.getBundle("library_state");queueFilter=state.getString("queue_filter","all");
            try{JSONObject selections=new JSONObject(state.getString("quality_selection","{}"));Iterator<String> keys=selections.keys();while(keys.hasNext()){String key=keys.next();qualitySelections.put(key,selections.optInt(key));}}catch(Exception ignored){}
            try{org.json.JSONArray saved=new org.json.JSONArray(state.getString("inspection_media","[]"));for(int n=0;n<saved.length();n++)inspectedMedia.add(Engine.Media.fromJson(saved.getJSONObject(n)));}catch(Exception ignored){}
        }
        draft=state==null?getPreferences(0).getString("draft",""):state.getString("draft","");
        tab=state==null?"new":state.getString("tab","new");
        UiTheme.systemBars(this);
        buildShell();
        readShare(getIntent());
    }

    private void buildShell() {
        root=column();root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;
        });
        brand=row();brand.setPadding(dp(20),dp(12),dp(20),dp(6));
        ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.ic_launcher);logo.setContentDescription("拾流");
        brand.addView(logo,new LinearLayout.LayoutParams(dp(28),dp(28)));
        TextView name=text("拾流",18,INK,true);LinearLayout.LayoutParams n=new LinearLayout.LayoutParams(0,-2,1);n.leftMargin=dp(9);brand.addView(name,n);
        brand.addView(text("本机保存",12,MUTED,false));root.addView(brand);
        body=column();root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        notice=column();notice.setVisibility(View.GONE);root.addView(notice);
        navigationFrame=new FrameLayout(this);navigationFrame.setBackgroundColor(SURFACE);
        navigationPill=new View(this);navigationPill.setBackground(shape(palette.accentSoft,16,0));
        FrameLayout.LayoutParams pill=new FrameLayout.LayoutParams(dp(72),dp(54));pill.topMargin=dp(6);navigationFrame.addView(navigationPill,pill);
        navigation=row();navigation.setPadding(dp(10),dp(4),dp(10),dp(4));navigationFrame.addView(navigation,new FrameLayout.LayoutParams(-1,dp(68)));
        String[] labels={"新建","下载","视频库","设置"},ids={"new","queue","library","settings"};
        for(int i=0;i<ids.length;i++){
            final String id=ids[i];Button b=quietButton(labels[i]);destinations[i]=b;
            b.setTextSize(11);b.setPadding(0,dp(6),0,dp(3));b.setMinWidth(0);b.setSingleLine(true);b.setGravity(Gravity.CENTER);
            b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x12397D72),null,null));
            b.setOnClickListener(v->showTab(id));navigation.addView(b,new LinearLayout.LayoutParams(0,-1,1));
        }
        root.addView(navigationFrame,new LinearLayout.LayoutParams(-1,dp(68)));
        setContentView(root);root.requestApplyInsets();showTab(tab);
    }

    private void showTab(String destination) {
        if(input!=null)draft=input.getText().toString();
        if(libraryView!=null){libraryState=libraryView.saveUiState();libraryView.release();libraryView=null;}
        if(openSwipeRow!=null)openSwipeRow.close(false);openSwipeRow=null;queueTouchActive=false;
        clearArtwork(body);tab=destination;body.removeAllViews();input=null;inspectionResults=null;homeExtras=null;
        brand.setVisibility("new".equals(tab)?View.VISIBLE:View.GONE);
        String[] labels={"新建","下载","视频库","设置"},ids={"new","queue","library","settings"};
        int chosen=0;
        for(int i=0;i<ids.length;i++){
            boolean selected=ids[i].equals(tab);if(selected)chosen=i;
            Button b=destinations[i];b.setTextColor(selected?ACCENT:MUTED);b.setTypeface(Typeface.DEFAULT,selected?Typeface.BOLD:Typeface.NORMAL);
            b.setSelected(selected);b.setContentDescription(labels[i]+(selected?"，当前页面":""));
            NavIcon icon=new NavIcon(i,selected?ACCENT:MUTED);icon.setBounds(0,0,dp(22),dp(22));b.setCompoundDrawables(null,icon,null,null);b.setCompoundDrawablePadding(dp(3));
        }
        final int index=chosen;
        navigationFrame.post(()->{if(isDestroyed())return;float width=(navigation.getWidth()-navigation.getPaddingLeft()-navigation.getPaddingRight())/4f;
            float x=navigation.getPaddingLeft()+index*width+(width-dp(72))/2;
            navigationPill.animate().cancel();if(UiTheme.motionEnabled(this)&&navigationPill.isLaidOut())navigationPill.animate().translationX(x).setDuration(220).setInterpolator(new android.view.animation.OvershootInterpolator(.7f)).start();else navigationPill.setTranslationX(x);
        });
        if("new".equals(tab))showNew();else if("queue".equals(tab))refreshQueue(true);
        else if("library".equals(tab)){
            libraryView=new VideoLibraryView(this);if(libraryState!=null)libraryView.restoreUiState(libraryState);
            body.addView(libraryView,new LinearLayout.LayoutParams(-1,-1));libraryView.refresh();
        }else showSettings();
    }

    private LinearLayout scrollContent() {
        ScrollView s=new ScrollView(this); s.setFillViewport(true); s.setClipToPadding(false);
        s.setOnTouchListener((view,event)->{if("queue".equals(tab)&&event.getActionMasked()==MotionEvent.ACTION_DOWN&&openSwipeRow!=null){openSwipeRow.close(true);openSwipeRow=null;}return false;});
        LinearLayout c=column(); c.setPadding(dp(20),dp(14),dp(20),dp(24));
        s.addView(c,new ScrollView.LayoutParams(-1,-2)); body.addView(s,new LinearLayout.LayoutParams(-1,-1)); return c;
    }

    private void showNew() {
        LinearLayout c=scrollContent();c.addView(text("新建下载",24,INK,true));
        add(c,text("X / Twitter · 哔哩哔哩 · 抖音",12,MUTED,false),4,18);
        LinearLayout form=column();form.setPadding(dp(16),dp(12),dp(16),dp(16));form.setBackground(shape(SURFACE,16,0));
        linkEditor=column();form.addView(linkEditor,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout label=row();label.addView(text("视频链接",14,INK,true),new LinearLayout.LayoutParams(0,-2,1));
        paste=quietButton("粘贴");paste.setMinWidth(0);paste.setTextColor(ACCENT);paste.setEnabled(!parsing);paste.setOnClickListener(v->pasteLink());label.addView(paste,new LinearLayout.LayoutParams(dp(60),dp(48)));linkEditor.addView(label);
        input=new EditText(this);input.setTextSize(14);input.setTextColor(INK);input.setHintTextColor(MUTED);
        input.setHint("粘贴链接或分享文案");input.setGravity(Gravity.TOP);
        input.setSingleLine(false);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_VARIATION_URI);input.setMinLines(2);input.setMaxLines(4);
        input.setBackground(fieldBackground());input.setPadding(dp(12),dp(12),dp(12),dp(12));input.setText(draft);input.setEnabled(!parsing);input.setLineSpacing(dp(3),1);
        input.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence a,int b,int c,int d){}public void onTextChanged(CharSequence a,int b,int c,int d){}public void afterTextChanged(android.text.Editable value){if(!parsing&&!value.toString().equals(draft)){draft=value.toString();inspectedMedia.clear();renderInspection();lastInspectionMessage="";lastInspectionDetails="";if(feedback!=null)feedback.setVisibility(View.GONE);if(failureDetails!=null)failureDetails.setVisibility(View.GONE);}}});add(linkEditor,input,4,12);
        parse=button(parsing?"正在解析…":"解析视频",true);parse.setEnabled(!parsing);parse.setOnClickListener(v->analyze());linkEditor.addView(parse,new LinearLayout.LayoutParams(-1,dp(48)));
        cancel=quietButton("取消解析");cancel.setVisibility(parsing?View.VISIBLE:View.GONE);cancel.setOnClickListener(v->cancelAnalysis());linkEditor.addView(cancel,new LinearLayout.LayoutParams(-1,dp(48)));
        resolvedLink=row();resolvedLink.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout source=column();source.addView(text("链接已解析",12,ACCENT,true));TextView sourceUrl=text(draft,12,MUTED,false);resolvedSource=sourceUrl;sourceUrl.setSingleLine();sourceUrl.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);add(source,sourceUrl,4,0);resolvedLink.addView(source,new LinearLayout.LayoutParams(0,-2,1));
        Button change=quietButton("更换链接");change.setTextColor(ACCENT);change.setTextSize(12);change.setMinWidth(0);change.setPadding(dp(10),0,0,0);change.setOnClickListener(v->{inspectedMedia.clear();lastInspectionMessage="";lastInspectionDetails="";renderInspection();feedback.setVisibility(View.GONE);input.requestFocus();input.selectAll();});resolvedLink.addView(change,new LinearLayout.LayoutParams(dp(80),dp(48)));form.addView(resolvedLink);
        feedback=text(parsing?"正在读取视频信息与可用画质…":lastInspectionMessage,12,MUTED,false);feedback.setTextIsSelectable(true);
        feedback.setVisibility(parsing||!lastInspectionMessage.isEmpty()?View.VISIBLE:View.GONE);add(form,feedback,10,0);
        failureDetails=quietButton("查看 / 复制错误详情");failureDetails.setVisibility(!parsing&&!lastInspectionDetails.isEmpty()?View.VISIBLE:View.GONE);
        failureDetails.setOnClickListener(v->showInspectionDetails());form.addView(failureDetails,new LinearLayout.LayoutParams(-1,dp(48)));c.addView(form);
        inspectionResults=column();add(c,inspectionResults,16,0);renderInspection();
        homeExtras=column();c.addView(homeExtras);
        if(inspectedMedia.isEmpty()){
            c=homeExtras;
            LinearLayout hint=row();TextView note=text("最高可用画质 · 直接保存到手机",12,MUTED,false);hint.addView(note,new LinearLayout.LayoutParams(0,-2,1));
            Button settings=quietButton("登录设置");settings.setPadding(dp(8),0,0,0);settings.setTextSize(12);settings.setOnClickListener(v->showTab("settings"));hint.addView(settings,new LinearLayout.LayoutParams(dp(72),dp(48)));add(c,hint,4,0);
            List<JSONObject> completed=new ArrayList<>();for(JSONObject task:TaskStore.all(this))if("completed".equals(task.optString("status")))completed.add(task);
            if(!completed.isEmpty()){
                LinearLayout head=row();head.addView(text("最近保存",14,INK,true),new LinearLayout.LayoutParams(0,-2,1));Button all=quietButton("查看视频库");all.setTextSize(12);all.setTextColor(ACCENT);all.setOnClickListener(v->showTab("library"));head.addView(all,new LinearLayout.LayoutParams(dp(96),dp(48)));add(c,head,14,4);
                for(int i=0;i<Math.min(2,completed.size());i++){
                    JSONObject task=completed.get(i);LinearLayout recent=row();recent.setPadding(dp(12),dp(10),dp(12),dp(10));recent.setBackground(shape(SURFACE,12,0));
                    ImageView cover=cover(task.optString("uri"),task.optString("id"),64,48);recent.addView(cover,new LinearLayout.LayoutParams(dp(64),dp(48)));
                    LinearLayout words=column();TextView name=text(task.optString("title","视频"),13,INK,true);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);words.addView(name);add(words,text(MediaSize.taskSummary(task),12,MUTED,false),3,0);
                    LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,-2,1);wp.leftMargin=dp(12);recent.addView(words,wp);recent.setOnClickListener(v->openFile(task,false));recent.setContentDescription("播放最近保存的视频："+task.optString("title"));UiTheme.press(recent);add(c,recent,0,8);
                }
            }
        }
    }

    private void renderInspection() {
        if(inspectionResults==null)return;
        boolean resolved=!inspectedMedia.isEmpty();if(linkEditor!=null)linkEditor.setVisibility(resolved?View.GONE:View.VISIBLE);if(resolvedLink!=null)resolvedLink.setVisibility(resolved?View.VISIBLE:View.GONE);if(resolved&&feedback!=null)feedback.setVisibility(View.GONE);if(homeExtras!=null)homeExtras.setVisibility(resolved?View.GONE:View.VISIBLE);if(resolvedSource!=null)resolvedSource.setText(draft);
        clearArtwork(inspectionResults);inspectionResults.removeAllViews();
        if(inspectedMedia.isEmpty())return;
        LinearLayout heading=row();heading.addView(text("解析结果",14,INK,true),new LinearLayout.LayoutParams(0,-2,1));heading.addView(text(inspectedMedia.size()+" 个视频",12,MUTED,false));add(inspectionResults,heading,0,10);
        for(Engine.Media m:inspectedMedia){
            LinearLayout item=column();item.setPadding(dp(14),dp(14),dp(14),dp(14));item.setBackground(shape(SURFACE,16,0));
            LinearLayout media=row();ImageView image=cover(m.thumbnail,"inspect:"+m.url+":"+m.id,88,66);media.addView(image,new LinearLayout.LayoutParams(dp(88),dp(66)));
            LinearLayout words=column();TextView name=text(m.title==null||m.title.isEmpty()?"未命名视频":m.title,14,INK,true);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);words.addView(name);
            String metadata=m.platform+(m.duration>0?" · "+duration(m.duration):"");add(words,text(metadata,12,MUTED,false),5,0);
            if(m.uploader!=null&&!m.uploader.isEmpty()){TextView author=text(m.uploader,12,MUTED,false);author.setSingleLine();author.setEllipsize(android.text.TextUtils.TruncateAt.END);add(words,author,3,0);}
            LinearLayout.LayoutParams info=new LinearLayout.LayoutParams(0,-2,1);info.leftMargin=dp(12);media.addView(words,info);item.addView(media);
            String key=m.url+":"+m.id+":"+m.playlistIndex;int[] selection={Math.min(Math.max(0,qualitySelections.getOrDefault(key,0)),m.qualities.size()-1)};
            TextView qualityDetails=text(mediaSummary(m,selection[0]),12,MUTED,false);
            Button quality=button("画质："+m.qualities.get(selection[0]).label+"  ▾",false);quality.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
            quality.setOnClickListener(v->{String[] options=new String[m.qualities.size()];for(int i=0;i<options.length;i++)options[i]=m.qualities.get(i).label;
                AlertDialog panel=new AlertDialog.Builder(this).setTitle("选择画质").setSingleChoiceItems(options,selection[0],(d,which)->{selection[0]=which;qualitySelections.put(key,which);quality.setText("画质："+options[which]+"  ▾");qualityDetails.setText(mediaSummary(m,which));d.dismiss();}).setNegativeButton("返回",null).show();bottom(panel);
            });add(item,quality,12,0);
            add(item,qualityDetails,7,0);
            Button save=button(inspectedMedia.size()>1?"下载这个视频":"加入下载",true);save.setOnClickListener(v->enqueue(m,m.qualities.get(selection[0])));add(item,save,12,0);
            add(inspectionResults,item,0,12);UiTheme.enter(item);
        }
    }

    private void pasteLink() {
        ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if(clipboard!=null&&clipboard.hasPrimaryClip()&&clipboard.getPrimaryClip().getItemCount()>0) {
            CharSequence s=clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
            if(s!=null) {input.setText(s); input.setSelection(input.length());}
        } else toast("剪贴板里还没有链接");
    }

    private void analyze() {
        final String url;
        try {url=Engine.normalizeUrl(input.getText().toString());}
        catch(Exception e) {feedback.setText(e.getMessage());feedback.setVisibility(View.VISIBLE); return;}
        draft=input.getText().toString(); input.setEnabled(false);if(paste!=null)paste.setEnabled(false); parsing=true; final int generation=++parseGeneration;
        lastInspectionMessage="";lastInspectionDetails="";inspectedMedia.clear();renderInspection();failureDetails.setVisibility(View.GONE);feedback.setVisibility(View.VISIBLE);
        parse.setEnabled(false); parse.setText("正在解析…"); cancel.setVisibility(View.VISIBLE);
        feedback.setText("正在读取视频信息与可用画质…");
        io.execute(()->{
            try {
                if(generation!=parseGeneration)return;
                List<Engine.Media> media=engine.inspect(url,stage->main.post(()->{
                    if(!isDestroyed()&&generation==parseGeneration&&parsing&&"new".equals(tab)&&feedback!=null)feedback.setText(stage);
                }));
                main.post(()->{
                    if(isDestroyed()||generation!=parseGeneration)return;
                    finishAnalysis(); if("new".equals(tab)&&feedback!=null)feedback.setText("已找到 "+media.size()+" 个视频");
                    showMedia(media);
                });
            } catch(Exception error) {
                final String message=friendly(error);
                main.post(()->{if(!isDestroyed()&&generation==parseGeneration){finishAnalysis();
                    String explanation=Engine.inspectionMessage(error);
                    lastInspectionMessage=explanation;lastInspectionDetails=message;
                    if("new".equals(tab)&&feedback!=null){feedback.setVisibility(View.VISIBLE);feedback.setText(explanation);failureDetails.setVisibility(View.VISIBLE);}
                    else showMessage("解析失败",explanation+"\n\n"+message);}});
            }
        });
    }

    private void finishAnalysis() {
        parsing=false;
        if("new".equals(tab)&&parse!=null) {if(input!=null)input.setEnabled(true);if(paste!=null)paste.setEnabled(true);parse.setEnabled(true);parse.setText("解析视频"); cancel.setVisibility(View.GONE);}
    }
    private void cancelAnalysis() {
        ++parseGeneration; parsing=false; engine.cancelInspect(); finishAnalysis();
        lastInspectionMessage="已取消，链接已保留。";lastInspectionDetails="";
        if(failureDetails!=null)failureDetails.setVisibility(View.GONE);
        if(feedback!=null){feedback.setVisibility(View.VISIBLE);feedback.setText("已取消，链接已保留。");}
    }

    private void showInspectionDetails() {
        String details="拾流 "+BuildConfig.VERSION_NAME+"\n\n"+Engine.cleanLog(lastInspectionDetails);
        TextView view=text(details,13,INK,false);view.setTextIsSelectable(true);view.setPadding(dp(22),dp(15),dp(22),dp(12));
        ScrollView scroll=new ScrollView(this);scroll.addView(view);
        new AlertDialog.Builder(this).setTitle("解析错误详情").setView(scroll).setPositiveButton("返回",null)
                .setNeutralButton("复制详情",(d,w)->{
                    ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    if(clipboard!=null){clipboard.setPrimaryClip(ClipData.newPlainText("拾流解析错误",details));toast("错误详情已复制");}
                }).show();
    }

    private void showMedia(List<Engine.Media> media) {
        if(media.isEmpty()){lastInspectionMessage="没有找到可用视频；请检查链接或平台登录状态。";if(feedback!=null){feedback.setText(lastInspectionMessage);feedback.setVisibility(View.VISIBLE);}return;}
        inspectedMedia.clear();inspectedMedia.addAll(media);lastInspectionMessage="已找到 "+media.size()+" 个视频";
        if("new".equals(tab)){if(feedback!=null){feedback.setText(lastInspectionMessage);feedback.setVisibility(View.VISIBLE);}renderInspection();}
        else toast("解析完成，回到新建查看视频");
    }

    private void enqueue(Engine.Media media,Engine.Quality quality) {
        if(Build.VERSION.SDK_INT<29&&checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED) {
            pendingMedia=media;pendingQuality=quality; requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},STORAGE_REQUEST);return;
        }
        try {
            String id=TaskStore.create(this,media,quality);
            startTask(id);
        } catch(Exception e){showMessage("暂时无法开始",friendly(e));}
    }

    private void startTask(String id) {
        if(Build.VERSION.SDK_INT<29&&checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){
            pendingRetryId=id;requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},STORAGE_REQUEST);return;
        }
        try {
            Intent i=new Intent(this,DownloadService.class).setAction(DownloadService.ENQUEUE).putExtra("id",id);
            if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);
            showTab("queue");
            if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFY_REQUEST);
        } catch(Exception e){
            try{TaskStore.update(this,id,new JSONObject().put("status","failed").put("detail","未能启动下载服务").put("userError",friendly(e)));}catch(Exception ignored){}
            showMessage("暂时无法开始",friendly(e));
        }
    }

    private void refreshQueue(boolean force) {
        if(!force&&(queueTouchActive||(openSwipeRow!=null&&openSwipeRow.isRevealed())))return;
        if(force&&openSwipeRow!=null){openSwipeRow.close(false);openSwipeRow=null;queueTouchActive=false;}
        List<JSONObject> tasks=DownloadQueueOrder.sorted(TaskStore.all(this));String signature=tasks.toString()+queueFilter+pendingRemoval.keySet();
        if(!force&&signature.equals(lastQueueSignature))return;lastQueueSignature=signature;
        int previousScroll=body.getChildCount()>0&&body.getChildAt(0) instanceof ScrollView?((ScrollView)body.getChildAt(0)).getScrollY():0;
        clearArtwork(body);body.removeAllViews();LinearLayout c=scrollContent();LinearLayout heading=row();heading.addView(text("下载",24,INK,true),new LinearLayout.LayoutParams(0,-2,1));heading.addView(text("本机任务",12,MUTED,false));c.addView(heading);
        LinearLayout filters=row();String[] labels={"全部","进行中","已完成","失败"},keys={"all","active","completed","failed"};
        for(int i=0;i<keys.length;i++){final String key=keys[i];Button filter=quietButton(labels[i]);filter.setTextSize(12);filter.setPadding(dp(12),0,dp(12),0);filter.setMinWidth(0);filter.setTextColor(key.equals(queueFilter)?ACCENT:MUTED);
            if(key.equals(queueFilter))filter.setBackground(shape(palette.accentSoft,12,0));filter.setSelected(key.equals(queueFilter));filter.setOnClickListener(v->{queueFilter=key;refreshQueue(true);});filters.addView(filter,new LinearLayout.LayoutParams(0,dp(48),1));}
        add(c,filters,10,12);
        int queuePosition=0,shown=0;
        for(JSONObject task:tasks){
            String status=task.optString("status"),id=task.optString("id");if("queued".equals(status))queuePosition++;
            if(pendingRemoval.containsKey(id)||!matchesFilter(status))continue;shown++;
            boolean active=isActive(status),pauseable=DownloadService.canPause(task);
            LinearLayout item=row();item.setGravity(Gravity.TOP);item.setPadding(dp(12),dp(12),dp(4),dp(12));item.setBackground(shape(SURFACE,12,0));
            JSONObject media=task.optJSONObject("media");String poster=media==null?"":media.optString("thumbnail");if(poster.isEmpty()&&"completed".equals(status))poster=task.optString("uri");
            FrameLayout thumbnail=new FrameLayout(this);thumbnail.setBackground(shape(palette.control,10,0));thumbnail.setClipToOutline(true);
            ImageView cover=cover(poster,id,64,64);thumbnail.addView(cover,new FrameLayout.LayoutParams(-1,-1));
            String primary="completed".equals(status)?"播放":"paused".equals(status)?"继续":"pausing".equals(status)?"暂停中":"cancelling".equals(status)?"取消中":active?(pauseable?"暂停":"处理中"):"重试";
            Button action=quietButton(primary);action.setTextColor(Color.WHITE);action.setTextSize(12);action.setPadding(0,0,0,0);action.setMinWidth(0);action.setBackground(new android.graphics.drawable.InsetDrawable(shape(0xB3171A1C,18,0),dp(6)));
            action.setEnabled((!active||pauseable)&&!"pausing".equals(status)&&!"cancelling".equals(status));action.setContentDescription(primary+"："+task.optString("title"));action.setVisibility(action.isEnabled()?View.VISIBLE:View.GONE);
            action.setOnClickListener(v->performTaskPrimary(id));thumbnail.addView(action,new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.CENTER));item.addView(thumbnail,new LinearLayout.LayoutParams(dp(64),dp(64)));
            LinearLayout content=column();LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-2,1);cp.leftMargin=dp(12);item.addView(content,cp);
            TextView name=text(task.optString("title","视频"),14,INK,true);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);content.addView(name);
            double progress=task.optDouble("progress",-1);boolean known="downloading".equals(status)&&!task.optString("phase").isEmpty()&&!task.optBoolean("transferComplete")&&progress>=0&&progress<100;
            String state="queued".equals(status)?"等待下载 · 第 "+queuePosition+" 位":taskPhaseTitle(task);
            LinearLayout stage=row();TextView stateLabel=text(state,12,"completed".equals(status)?ACCENT:MUTED,false);stage.addView(stateLabel,new LinearLayout.LayoutParams(0,-2,1));
            String prefix=task.optBoolean("progressEstimated")?"整体约 ":"整体 ";String percent="completed".equals(status)?"100%":known?prefix+(int)progress+"%":"";
            if(!percent.isEmpty())stage.addView(text(percent,12,MUTED,false));add(content,stage,6,0);
            String size=MediaSize.taskSummary(task);if(!size.isEmpty())add(content,text(size,12,MUTED,false),4,0);
            if(active&&!"queued".equals(status)){
                ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(LINE));bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ACCENT));bar.setIndeterminate(!known);bar.setProgress((int)Math.min(99,Math.max(0,progress)));bar.setContentDescription(known?prefix+(int)progress+"%":state+"，整体完成时间暂无法估计");add(content,bar,6,0);bar.getLayoutParams().height=dp(4);
            }
            String error=task.optString("userError"),summary=error.isEmpty()?taskCompactDetail(task,known):error;
            if(!summary.isEmpty()){TextView detail=text(summary,12,error.isEmpty()?MUTED:palette.danger,false);detail.setMaxLines(2);detail.setEllipsize(android.text.TextUtils.TruncateAt.END);add(content,detail,5,0);}
            Button more=quietButton("⋯");more.setTextSize(22);more.setContentDescription("任务更多操作："+task.optString("title"));more.setPadding(0,0,0,0);more.setMinWidth(0);more.setOnClickListener(v->showTaskActions(v,task));item.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48)));
            item.setOnClickListener(v->{JSONObject current=TaskStore.get(this,id);if(current!=null)showTask(current);});item.setOnLongClickListener(v->{showTaskActions(v,task);return true;});
            if(pauseable||"paused".equals(status)){
                final String expected="paused".equals(status)?"paused":"pauseable";
                Button swipe=button("paused".equals(status)?"继续":"暂停",false);swipe.setTextColor(ACCENT);swipe.setBackground(shape(palette.accentSoft,12,0));swipe.setContentDescription("侧滑"+swipe.getText()+"："+task.optString("title"));
                SwipeActionRow wrapper=new SwipeActionRow(this,item,swipe);wrapper.setInteractionListener(touching->{queueTouchActive=touching;if(touching&&openSwipeRow!=wrapper){if(openSwipeRow!=null)openSwipeRow.close(false);openSwipeRow=wrapper;}});
                swipe.setOnClickListener(v->{wrapper.close(false);performSwipeAction(id,expected);});add(c,wrapper,0,8);
            }else add(c,item,0,8);
        }
        if(shown==0){add(c,text(tasks.isEmpty()?"还没有下载任务":"没有这类任务",18,INK,true),30,8);add(c,text(tasks.isEmpty()?"分享或粘贴一条链接，开始保存视频。":"切换筛选即可查看其他任务。",13,MUTED,false),0,16);Button first=button(tasks.isEmpty()?"新建下载":"查看全部任务",true);first.setOnClickListener(v->{if(tasks.isEmpty())showTab("new");else{queueFilter="all";refreshQueue(true);}});add(c,first,0,0);}
        ScrollView scroll=(ScrollView)c.getParent();scroll.post(()->scroll.scrollTo(0,previousScroll));
    }

    private void performTaskPrimary(String id){
        JSONObject task=TaskStore.get(this,id);if(task==null)return;String status=task.optString("status");
        if("completed".equals(status))openFile(task,false);
        else if(DownloadService.canPause(task))startService(new Intent(this,DownloadService.class).setAction(DownloadService.PAUSE).putExtra("id",id));
        else if(!isActive(status))startTask(id);
        else toast("任务状态已变化，请稍候");
    }
    private void performSwipeAction(String id,String expected){
        // A revealed affordance is not authorization to invert a newer live state.
        JSONObject task=TaskStore.get(this,id);
        if(task!=null&&(("paused".equals(expected)&&"paused".equals(task.optString("status")))||("pauseable".equals(expected)&&DownloadService.canPause(task))))performTaskPrimary(id);
        else toast("任务状态已变化，列表已更新");
        if("queue".equals(tab))refreshQueue(true);
    }
    private static String taskCompactDetail(JSONObject task,boolean known){
        String status=task.optString("status"),phase=task.optString("phase");
        if(!"downloading".equals(status)||task.optBoolean("transferComplete"))return "";
        if(!Arrays.asList("video","audio","media","retrying").contains(phase))return "";
        if("retrying".equals(phase))return "连接暂时中断，正在重试";
        double speed=task.optDouble("speedBytesPerSecond",-1);long eta=task.optLong("etaSeconds",-1);
        String rate=speed>0?MediaSize.format((long)speed)+"/s":"速度测算中";
        if(eta>0)rate+=" · 本阶段约 "+eta+" 秒";
        return (!known?"整体大小尚未确定\n":"")+rate;
    }

    private boolean matchesFilter(String status){
        if("completed".equals(queueFilter))return "completed".equals(status);
        if("active".equals(queueFilter))return isActive(status)||"paused".equals(status);
        if("failed".equals(queueFilter))return "failed".equals(status)||"interrupted".equals(status)||"cancelled".equals(status);
        return true;
    }

    private void showTaskActions(View anchor,JSONObject task){
        JSONObject latest=TaskStore.get(this,task.optString("id"));if(latest==null)return;task=latest;final JSONObject current=task;
        PopupMenu menu=new PopupMenu(this,anchor);menu.getMenu().add("详情").setOnMenuItemClickListener(i->{JSONObject fresh=TaskStore.get(this,current.optString("id"));if(fresh!=null)showTask(fresh);return true;});
        String status=task.optString("status"),id=task.optString("id");
        if("completed".equals(status))menu.getMenu().add("分享视频").setOnMenuItemClickListener(i->{openFile(current,true);return true;});
        if(isActive(status)&&!"cancelling".equals(status)&&!"pausing".equals(status))menu.getMenu().add("取消任务").setOnMenuItemClickListener(i->{startService(new Intent(this,DownloadService.class).setAction("dev.streampocket.CANCEL").putExtra("id",id));return true;});
        if(!isActive(status))menu.getMenu().add("移除记录").setOnMenuItemClickListener(i->{removeTaskWithUndo(current);return true;});menu.setOnDismissListener(d->taskMenuOpen=false);taskMenuOpen=true;menu.show();
    }

    private void removeTaskWithUndo(JSONObject task){
        JSONObject current=TaskStore.get(this,task.optString("id"));
        if(current==null||isActive(current.optString("status"))){if(current!=null)toast("任务已重新开始，记录已保留");if("queue".equals(tab))refreshQueue(true);return;}
        pendingRemoval.put(task.optString("id"),task);main.removeCallbacks(commitRemovals);main.postDelayed(commitRemovals,5000);
        refreshQueue(true);notice.removeAllViews();notice.setPadding(dp(16),dp(4),dp(16),dp(4));notice.setBackgroundColor(palette.control);
        LinearLayout row=row();row.addView(text("5秒后移除记录，视频文件保留",12,INK,false),new LinearLayout.LayoutParams(0,-2,1));Button undo=quietButton("撤销");undo.setTextColor(ACCENT);undo.setOnClickListener(v->{main.removeCallbacks(commitRemovals);pendingRemoval.clear();hideNotice();refreshQueue(true);});row.addView(undo,new LinearLayout.LayoutParams(dp(64),dp(48)));notice.addView(row);notice.setVisibility(View.VISIBLE);
    }
    private void commitPendingRemovals(boolean refresh){
        boolean retained=false;
        for(String id:new ArrayList<>(pendingRemoval.keySet())){
            // A second activity may have resumed this task during the undo interval.
            if(!TaskStore.removeIfInactive(this,id)&&TaskStore.get(this,id)!=null)retained=true;
        }
        pendingRemoval.clear();
        if(refresh&&!isDestroyed()){
            hideNotice();if("queue".equals(tab))refreshQueue(true);
            if(retained)toast("任务已重新开始，记录已保留");
        }
    }
    private void hideNotice(){notice.removeAllViews();notice.setVisibility(View.GONE);}

    private static String taskPhaseTitle(JSONObject task) {
        String status=task.optString("status");
        if(!"downloading".equals(status))return statusName(status);
        switch(task.optString("phase")){
            case "video":return "下载视频";
            case "audio":return "下载音频";
            case "media":return "下载音视频";
            case "retrying":return "正在重试";
            case "processing":return "下载已完成，正在处理";
            case "merging":return "正在合并音视频";
            case "saving":return "正在保存到手机";
            case "preparing":return "正在获取下载信息";
            default:return "正在下载";
        }
    }

    private static String taskProgressDetail(JSONObject task,String title,boolean overallKnown) {
        String detail=task.optString("detail","");
        if(detail.startsWith(title+" · "))detail=detail.substring(title.length()+3);
        String status=task.optString("status"),phase=task.optString("phase");
        boolean transferring="downloading".equals(status)&&Arrays.asList("video","audio","media","retrying").contains(phase)
                &&!task.optBoolean("transferComplete");
        if(transferring && !"retrying".equals(phase) && task.optDouble("speedBytesPerSecond",-1)<=0 && !detail.contains("/s"))
            detail+=(detail.isEmpty()?"":" · ")+"速度测算中";
        double stage=task.optDouble("stageProgress",-1);
        String stageText=stage>=0?"当前阶段 "+(int)Math.min(100,Math.max(0,stage))+"%":"";
        if(transferring&&!overallKnown){
            String hint=stageText.isEmpty()||detail.contains("%")?"整体大小尚未确定":stageText+" · 整体大小尚未确定";
            return detail.isEmpty()?hint:hint+"\n"+detail;
        }
        // The current part may restart at zero while aggregate bytes remain steady.
        if(transferring&&stage>=0&&!detail.contains("%"))return stageText+(detail.isEmpty()?"":" · "+detail);
        return detail;
    }

    private static String taskTransferDetail(JSONObject task) {
        if(!"downloading".equals(task.optString("status")) || task.optBoolean("transferComplete"))return "";
        int configured=task.optInt("configuredConnections",0),active=task.optInt("activeConnections",-1);
        switch(task.optString("transferMode")){
            case "fragments":return "分片下载"+(configured>0?" · 并发上限 "+configured+" 路":"");
            case "parallel-http":return "分块下载"+(active>=0?" · 当前 "+active+" 个连接":" · 连接数测量中")
                    +(configured>0?" · 上限 "+configured+" 路":"");
            case "native-http":return task.optString("transferReason").isEmpty()
                    ? "兼容下载 · 未启用多连接" : task.optString("transferReason");
            default:return "";
        }
    }

    private void openFile(JSONObject task,boolean share) {
        String raw=task.optString("uri"); if(raw.isEmpty()){toast("没有找到保存位置");return;}
        if(!share){
            io.execute(()->{
                try(LibraryStore store=new LibraryStore(this)){
                    long videoId=store.upsertCompletedDownload(task);
                    main.post(()->{if(!isDestroyed()){
                        if(videoId>0)startActivity(new Intent(this,PlayerActivity.class).putExtra("video_id",videoId));
                        else showMessage("无法播放","没有找到保存的视频记录。");
                    }});
                }catch(Exception failure){main.post(()->{if(!isDestroyed())showMessage("无法播放",friendly(failure));});}
            });return;
        }
        try {
            Uri uri=Uri.parse(raw); String mime=task.optString("mime","video/*");
            Intent i=new Intent(share?Intent.ACTION_SEND:Intent.ACTION_VIEW);
            if(share){i.setType(mime);i.putExtra(Intent.EXTRA_STREAM,uri);}else i.setDataAndType(uri,mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);i.setClipData(ClipData.newRawUri("视频",uri));
            startActivity(Intent.createChooser(i,share?"分享视频":"打开视频"));
        }catch(ActivityNotFoundException e){toast("没有可打开此视频的应用");}
        catch(Exception e){showMessage("无法打开", "文件可能已被移动或删除。\n"+friendly(e));}
    }

    private void showTask(JSONObject task) {
        String message=task.optString("title")+"\n\n"+statusName(task.optString("status"))+"\n"+
                task.optString("detail")+"\n"+MediaSize.taskSummary(task)+"\n"+taskTransferDetail(task)+"\n\n"+task.optString("userError")+"\n\n"+task.optString("url")+
                (task.optString("uri").isEmpty()?"":"\n\n"+task.optString("uri"));
        if(!task.optString("log").isEmpty())message+="\n\n诊断记录（敏感参数已隐藏）\n"+Engine.cleanLog(task.optString("log"));
        showMessage("任务详情",message);
    }

    private void showSettings() {
        LinearLayout c=scrollContent();c.addView(text("设置",24,INK,true));
        section(c,"账户与来源");LinearLayout accounts=settingsGroup(c);TextView accountSummary=settingRow(accounts,"账号管理","读取中…",this::showLoginSettings);
        io.execute(()->{String summary;try{AccountStore store=new AccountStore(this);store.prepare();int count=0;for(LoginPlatform p:LoginPlatform.values())if(store.hasSaved(p))count++;summary=count==0?"未登录":count+" 个平台已保存";}catch(Exception e){summary="点此管理";}String value=summary;main.post(()->{if(!isDestroyed()&&"settings".equals(tab)&&accountSummary.isAttachedToWindow())accountSummary.setText(value);});});
        section(c,"下载管理");LinearLayout downloads=settingsGroup(c);settingRow(downloads,"下载与速度",downloadConnections()+" 路 · 最高可用",this::showDownloadSettings);settingRow(downloads,"保存位置","Download / StreamPocket",()->showMessage("保存位置","完整视频保存到系统 Download / StreamPocket。未完成片段保留在本 App 临时目录，不作为成片导出。"));
        section(c,"播放与外观");LinearLayout appearance=settingsGroup(c);settingRow(appearance,"外观主题",themeLabel(),this::showAppearanceSettings);settingRow(appearance,"快进 / 快退",getSharedPreferences("config",0).getInt("seek_seconds",10)+" 秒",this::showSeekSettings);
        switchRow(appearance,"播放手势","player_gestures",false);switchRow(appearance,"界面动效","motion_enabled",true);
        section(c,"高级与关于");LinearLayout advanced=settingsGroup(c);String proxy=getSharedPreferences("config",0).getString("proxy","");settingRow(advanced,"网络与代理",proxy==null||proxy.isEmpty()?"跟随手机网络":"独立代理",this::showNetworkSettings);settingRow(advanced,"下载内核","更新与诊断",this::showParserSettings);settingRow(advanced,"应用更新",BuildConfig.VERSION_NAME+" · GitHub Releases",()->startActivity(new Intent(this,UpdateActivity.class)));settingRow(advanced,"关于拾流",BuildConfig.VERSION_NAME,this::showAboutSettings);
        add(c,text("视频、账号与设置仅保存在本机",12,MUTED,false),18,0);
    }
    private void section(LinearLayout c,String title){add(c,text(title,12,MUTED,true),22,8);}
    private LinearLayout settingsGroup(LinearLayout c){LinearLayout group=column();group.setPadding(dp(14),0,dp(14),0);group.setBackground(shape(SURFACE,16,0));c.addView(group,new LinearLayout.LayoutParams(-1,-2));return group;}
    private void switchRow(LinearLayout parent,String title,String key,boolean fallback){
        LinearLayout r=row();r.setMinimumHeight(dp(56));r.addView(text(title,14,INK,false),new LinearLayout.LayoutParams(0,-2,1));Switch control=new Switch(this);control.setMinHeight(dp(48));control.setContentDescription(title);control.setChecked(getSharedPreferences("config",0).getBoolean(key,fallback));control.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));control.setOnCheckedChangeListener((v,checked)->getSharedPreferences("config",0).edit().putBoolean(key,checked).apply());r.addView(control);parent.addView(r);
    }
    private String themeLabel(){String t=getSharedPreferences("config",0).getString("appearance_theme","system");return "dark".equals(t)?"深色":"light".equals(t)?"浅色":"跟随系统";}
    private void showAppearanceSettings(){
        String[] labels={"跟随系统","浅色","深色"},values={"system","light","dark"};String value=getSharedPreferences("config",0).getString("appearance_theme","system");int selected=Arrays.asList(values).indexOf(value);
        AlertDialog panel=new AlertDialog.Builder(this).setTitle("外观主题").setSingleChoiceItems(labels,Math.max(0,selected),(d,which)->{getSharedPreferences("config",0).edit().putString("appearance_theme",values[which]).apply();d.dismiss();recreate();}).setNegativeButton("返回",null).show();bottom(panel);
    }
    private void showSeekSettings(){
        int[] values={5,10,15,30};String[] labels={"5 秒","10 秒","15 秒","30 秒"};int now=getSharedPreferences("config",0).getInt("seek_seconds",10),selected=1;for(int i=0;i<values.length;i++)if(values[i]==now)selected=i;
        AlertDialog panel=new AlertDialog.Builder(this).setTitle("双击快进 / 快退").setSingleChoiceItems(labels,selected,(d,which)->{getSharedPreferences("config",0).edit().putInt("seek_seconds",values[which]).apply();d.dismiss();showTab("settings");}).setNegativeButton("返回",null).show();bottom(panel);
    }

    private int downloadConnections() {
        int value=getSharedPreferences("config",0).getInt("download_connections",4);
        return value==1||value==8?value:4;
    }

    private void showDownloadSettings() {
        LinearLayout c=column();
        c.addView(text("选择同时下载的连接数",14,INK,true));
        add(c,text("分片视频并发下载，支持的 MP4（含B站）使用分块加速。已有兼容下载断点会继续续传。",13,MUTED,false),8,12);
        RadioGroup options=new RadioGroup(this);options.setOrientation(RadioGroup.VERTICAL);
        int[] values={1,4,8};String[] labels={"标准 · 1 路连接","并行 · 4 路连接（推荐）","并行 · 8 路连接"};
        int selected=downloadConnections();
        for(int i=0;i<values.length;i++){
            RadioButton option=new RadioButton(this);option.setId(View.generateViewId());option.setTag(values[i]);
            option.setText(labels[i]);option.setTextSize(14);option.setTextColor(INK);option.setMinHeight(dp(52));
            option.setButtonTintList(android.content.res.ColorStateList.valueOf(INK));option.setPadding(0,dp(4),dp(4),dp(4));
            options.addView(option,new RadioGroup.LayoutParams(-1,-2));if(values[i]==selected)option.setChecked(true);
        }
        c.addView(options,new LinearLayout.LayoutParams(-1,-2));
        add(c,text("更多连接不一定更快。实际速度取决于手机网络和源站，下次开始任务时生效。",12,MUTED,false),12,18);
        Button save=button("保存下载设置",true);c.addView(save,new LinearLayout.LayoutParams(-1,dp(48)));
        AlertDialog panel=settingsPanel("下载与速度",c);
        save.setOnClickListener(v->{
            View chosen=options.findViewById(options.getCheckedRadioButtonId());
            int value=chosen!=null&&chosen.getTag() instanceof Integer?(Integer)chosen.getTag():4;
            getSharedPreferences("config",0).edit().putInt("download_connections",value).apply();
            panel.dismiss();showTab("settings");toast("已保存，下次开始下载时生效");
        });
    }

    private void showLoginSettings() {
        LinearLayout c=column();
        c.addView(text("选择平台，在官方网页中登录。",14,INK,true));
        add(c,text("登录信息只在本机使用。",13,MUTED,false),8,12);
        LinearLayout platforms=column();platforms.addView(text("正在读取本机登录状态…",13,MUTED,false));c.addView(platforms);
        Button importCookie=quietButton("从 Cookie 文件导入");add(c,importCookie,16,0);
        Button clearAll=quietButton("清除全部登录信息");add(c,clearAll,0,0);
        AlertDialog panel=settingsPanel("账号管理",c);
        importCookie.setOnClickListener(v->openCookieImport(panel));
        clearAll.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("清除全部本机登录信息？")
                .setMessage("将清除所有平台及文件导入的登录会话。进行中的任务可能仍持有本次会话，后续新任务不再使用它们。")
                .setNegativeButton("保留",null).setPositiveButton("全部清除",(d,w)->{
                    clearAll.setEnabled(false);importCookie.setEnabled(false);
                    io.execute(()->{
                        String error=null;try{new AccountStore(this).clearAll();}catch(Exception e){error=friendly(e);}
                        String failure=error;main.post(()->{
                            if(isDestroyed()||!panel.isShowing())return;
                            if(failure!=null){clearAll.setEnabled(true);importCookie.setEnabled(true);showMessage("清除未完成",failure);return;}
                            panel.dismiss();refreshSettingsSummary();toast("已清除全部本机登录信息");showLoginSettings();
                        });
                    });
                }).show());
        io.execute(()->{
            List<AccountState> states;String error=null;
            try{states=readAccountStates();}catch(Exception e){states=Collections.emptyList();error=friendly(e);}
            List<AccountState> result=states;String failure=error;
            main.post(()->{
                if(isDestroyed()||!panel.isShowing())return;
                platforms.removeAllViews();
                if(failure!=null){
                    platforms.addView(text("登录状态读取失败："+failure,13,MUTED,false));
                    Button retry=quietButton("重新读取");retry.setOnClickListener(v->{panel.dismiss();showLoginSettings();});add(platforms,retry,8,0);return;
                }
                for(AccountState state:result)settingRow(platforms,state.platform.title,accountStatus(state),state.saved?"管理":"登录",()->{
                    if(state.saved){panel.dismiss();showAccountDetails(state);}
                    else launchLogin(state.platform,panel);
                });
            });
        });
    }

    private List<AccountState> readAccountStates() throws Exception {
        AccountStore store=new AccountStore(this);store.prepare();List<AccountState> states=new ArrayList<>();
        for(LoginPlatform platform:LoginPlatform.values())states.add(new AccountState(platform,store.hasSaved(platform),
                store.isEnabled(platform),store.savedAt(platform),store.source(platform)));
        return states;
    }

    private static String accountStatus(AccountState state) {
        return state.saved?(state.enabled?"已保存 · 用于下载":"已保存 · 已暂停"):"未保存登录信息";
    }

    private void showAccountDetails(AccountState state) {
        LoginPlatform platform=state.platform;LinearLayout c=column();
        TextView savedStatus=text(accountStatus(state),14,INK,true);c.addView(savedStatus);
        if(state.saved){
            String time=state.savedAt>0?android.text.format.DateFormat.format("yyyy-MM-dd HH:mm",state.savedAt).toString():"时间未知";
            add(c,text(state.source+" · "+time,12,MUTED,false),6,0);
        }
        add(c,text(state.saved?"是否仍然有效，以源站实际响应为准。":"登录后，后续解析和下载会使用此平台的会话。",13,MUTED,false),8,18);
        Button login=button(state.saved?"打开官方登录页":"登录"+platform.title,true);c.addView(login,new LinearLayout.LayoutParams(-1,dp(48)));
        TextView result=text("",13,MUTED,false);result.setVisibility(View.GONE);
        AlertDialog panel=new AlertDialog.Builder(this).setTitle(platform.title).setView(accountPanelContent(c))
                .setPositiveButton("返回",(d,w)->showLoginSettings()).setOnCancelListener(d->showLoginSettings()).create();
        login.setOnClickListener(v->{
            login.setEnabled(false);
            if(!launchLogin(platform,panel))login.setEnabled(true);
        });
        if(state.saved){
            Switch enabled=new Switch(this);enabled.setText("用于后续解析和下载");enabled.setTextSize(14);enabled.setTextColor(INK);
            enabled.setChecked(state.enabled);enabled.setMinHeight(dp(48));enabled.setSwitchPadding(dp(12));add(c,enabled,16,0);
            final boolean[] reverting={false};
            enabled.setOnCheckedChangeListener((control,checked)->{
                if(reverting[0])return;
                enabled.setEnabled(false);result.setVisibility(View.GONE);
                io.execute(()->{
                    String error=null;try{new AccountStore(this).setEnabled(platform,checked);}catch(Exception e){error=friendly(e);}
                    String failure=error;main.post(()->{
                        if(isDestroyed()||!panel.isShowing())return;
                        enabled.setEnabled(true);
                        if(failure!=null){reverting[0]=true;enabled.setChecked(!checked);reverting[0]=false;result.setText(failure);result.setVisibility(View.VISIBLE);}
                        else{state.enabled=checked;savedStatus.setText(accountStatus(state));refreshSettingsSummary();}
                    });
                });
            });
            Button clear=quietButton("清除本机登录信息");add(c,clear,10,0);
            clear.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("清除"+platform.title+"登录信息？")
                    .setMessage("将移除此平台保存在拾流中的登录信息。进行中的任务可能仍持有本次会话，重新解析和新任务不再使用它。")
                    .setNegativeButton("保留",null).setPositiveButton("清除",(d,w)->{
                        clear.setEnabled(false);login.setEnabled(false);enabled.setEnabled(false);
                        io.execute(()->{
                            String error=null;try{new AccountStore(this).clear(platform);}catch(Exception e){error=friendly(e);}
                            String failure=error;main.post(()->{
                                if(isDestroyed()||!panel.isShowing())return;
                                if(failure!=null){clear.setEnabled(true);login.setEnabled(true);enabled.setEnabled(true);result.setText(failure);result.setVisibility(View.VISIBLE);return;}
                                panel.dismiss();refreshSettingsSummary();toast("已清除"+platform.title+"的本机登录信息");showLoginSettings();
                            });
                        });
                    }).show());
        }
        add(c,result,10,0);panel.show();
    }

    private ScrollView accountPanelContent(LinearLayout content) {
        content.setPadding(dp(24),dp(12),dp(24),dp(8));ScrollView scroll=new ScrollView(this);scroll.addView(content);return scroll;
    }

    private boolean launchLogin(LoginPlatform platform,AlertDialog panel) {
        if(loginOpening)return false;
        loginOpening=true;
        try{startActivityForResult(new Intent(this,LoginActivity.class).putExtra("platform",platform.id),LOGIN_REQUEST);panel.dismiss();return true;}
        catch(ActivityNotFoundException|SecurityException e){
            loginOpening=false;showMessage("无法打开登录页","请重新安装完整版本后重试。");return false;
        }
    }

    private void openCookieImport(AlertDialog panel) {
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/*");
        try{startActivityForResult(intent,COOKIE_REQUEST);panel.dismiss();}
        catch(ActivityNotFoundException e){toast("请先安装文件管理器");}
    }

    private void refreshSettingsSummary() {
        if("settings".equals(tab))showTab("settings");
    }

    private static final class AccountState {
        final LoginPlatform platform;final boolean saved;boolean enabled;final long savedAt;final String source;
        AccountState(LoginPlatform platform,boolean saved,boolean enabled,long savedAt,String source){
            this.platform=platform;this.saved=saved;this.enabled=enabled;this.savedAt=savedAt;this.source=source;
        }
    }

    private void showNetworkSettings() {
        LinearLayout c=column();
        c.addView(text("默认跟随手机网络",14,INK,true));
        add(c,text("使用手机已有 VPN 时通常无需填写。需要独立代理时再设置。",13,MUTED,false),8,18);
        add(c,text("代理地址（可留空）",13,INK,true),0,8);
        EditText proxy=new EditText(this);proxy.setSingleLine(true);proxy.setTextSize(14);proxy.setTextColor(INK);proxy.setHintTextColor(MUTED);
        proxy.setHint("http://地址:端口 或 socks5://…");proxy.setBackground(fieldBackground());proxy.setPadding(dp(12),dp(12),dp(12),dp(12));
        proxy.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        proxy.setText(getSharedPreferences("config",0).getString("proxy",""));c.addView(proxy);
        Button saveProxy=button("保存网络设置",true);add(c,saveProxy,16,0);
        AlertDialog panel=settingsPanel("网络与代理",c);
        saveProxy.setOnClickListener(v->{
            String value=proxy.getText().toString().trim();
            if(!value.isEmpty())try{URI u=new URI(value);if(!Arrays.asList("http","https","socks5","socks5h").contains(u.getScheme())||u.getHost()==null||u.getPort()<1||u.getPort()>65535)throw new IllegalArgumentException();}
            catch(Exception e){proxy.setError("请输入带端口的有效地址，或留空");return;}
            getSharedPreferences("config",0).edit().putString("proxy",value).apply();panel.dismiss();showTab("settings");toast("已保存，下次解析和下载生效");
        });
    }

    private void showParserSettings() {
        LinearLayout c=column();
        c.addView(text("已内置解析器，可直接尝试下载。",14,INK,true));
        add(c,text("平台无法解析时可更新；遇到组件错误时先检查运行状态。",13,MUTED,false),8,18);
        Button update=button("更新解析器",true);update.setOnClickListener(v->background(update,"正在更新…",()->engine.update(),"解析器更新"));c.addView(update,new LinearLayout.LayoutParams(-1,dp(48)));
        Button diagnose=button("检查内核与合并组件",false);diagnose.setOnClickListener(v->background(diagnose,"检查中…",()->engine.diagnose(),"内核诊断"));add(c,diagnose,10,0);
        settingsPanel("下载内核",c);
    }

    private void showAboutSettings() {
        LinearLayout c=column();
        c.addView(text("拾流 "+BuildConfig.VERSION_NAME,16,INK,true));
        add(c,text("Android 7.0+ · 本地下载\n默认最高可用画质，无广告。",13,MUTED,false),8,16);
        add(c,text("覆盖升级保留账号、下载记录和视频库。登录状态仍由原平台决定。",13,MUTED,false),0,12);
        c.addView(text("实际画质取决于源站和账号权限。抖音仍为实验性支持，部分链接可能无法解析。",13,MUTED,false));
        Button license=button("开源与第三方组件",false);add(c,license,18,0);
        license.setOnClickListener(v->showMessage("开源组件","本项目：GPL-3.0-or-later\nyoutubedl-android 0.18.1：GPLv3\naria2c 1.37.0：GPL-2.0-or-later；Android wrapper 0.18.1：GPLv3\nyt-dlp：Unlicense；FFmpeg 及其依赖遵循各自许可。\n\n抖音实验补丁基于 yt-dlp #17826，固定提交 728fe46fbaa97d6f03efcaf9e766d4db63338b8d。\n\n完整来源、许可证与构建说明随工程提供。"));
        settingsPanel("关于拾流",c);
    }

    private TextView settingRow(LinearLayout parent,String title,String subtitle,Runnable click) {
        return settingRow(parent,title,subtitle,"›",click);
    }

    private TextView settingRow(LinearLayout parent,String title,String subtitle,String actionLabel,Runnable click) {
        LinearLayout r=row();r.setPadding(0,dp(8),0,dp(8));r.setMinimumHeight(dp(56));boolean arrowOnly="›".equals(actionLabel);
        TextView summary=text(subtitle,12,MUTED,false);summary.setMaxLines(2);summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if(arrowOnly){r.addView(text(title,14,INK,false),new LinearLayout.LayoutParams(0,-2,1));summary.setGravity(Gravity.END);LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(dp(142),-2);vp.leftMargin=dp(8);r.addView(summary,vp);}
        else{LinearLayout labels=column();labels.addView(text(title,14,INK,true));add(labels,summary,4,0);r.addView(labels,new LinearLayout.LayoutParams(0,-2,1));}
        TextView arrow=text(actionLabel,arrowOnly?20:13,arrowOnly?MUTED:ACCENT,false);arrow.setGravity(Gravity.CENTER);r.addView(arrow,new LinearLayout.LayoutParams(dp(arrowOnly?22:48),dp(40)));
        r.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x14397D72),shape(SURFACE,10,0),null));r.setFocusable(true);r.setClickable(true);r.setContentDescription(title+"，"+subtitle+(arrowOnly?"":"，"+actionLabel));r.setOnClickListener(v->click.run());UiTheme.press(r);parent.addView(r,new LinearLayout.LayoutParams(-1,-2));return summary;
    }

    private AlertDialog settingsPanel(String title,LinearLayout content) {
        content.setPadding(dp(20),dp(8),dp(20),dp(8));ScrollView scroll=new ScrollView(this);scroll.addView(content);AlertDialog panel=new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("完成",null).show();bottom(panel);return panel;
    }
    private void bottom(AlertDialog panel){Window window=panel.getWindow();if(window!=null){window.setGravity(Gravity.BOTTOM);window.setBackgroundDrawable(shape(SURFACE,20,0));window.setLayout(-1,-2);window.setDimAmount(.35f);}}

    private void background(Button button,String pending,Callable<String> work,String title) {
        CharSequence original=button.getText();button.setEnabled(false);button.setText(pending);
        io.execute(()->{String message;try{message=work.call();}catch(Exception e){message=friendly(e);}String result=message;
            main.post(()->{if(isDestroyed())return;button.setEnabled(true);button.setText(original);showMessage(title,result);});});
    }

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==LOGIN_REQUEST){
            loginOpening=false;refreshSettingsSummary();showLoginSettings();return;
        }
        if(request!=COOKIE_REQUEST||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri selected=data.getData();
        io.execute(()->{
            String message;
            try(InputStream source=getContentResolver().openInputStream(selected)) {
                if(source==null)throw new IOException("无法读取所选文件");
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
                while((count=source.read(buffer))!=-1){if(bytes.size()+count>2*1024*1024)throw new IOException("Cookie 文件过大（最多 2 MB）");bytes.write(buffer,0,count);}
                String contents=bytes.toString(StandardCharsets.UTF_8.name());
                new AccountStore(this).importLegacy(contents);
                message="已按平台保存到本机。重新解析链接即可使用账号当前可用的内容与画质。";
            }catch(Exception e){message=friendly(e);}String finalMessage=message;
            main.post(()->{if(!isDestroyed()){if("settings".equals(tab))showTab("settings");showMessage("Cookie 导入",finalMessage);}});
        });
    }

    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==STORAGE_REQUEST) {
            if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED){if(pendingRetryId!=null)startTask(pendingRetryId);else if(pendingMedia!=null)enqueue(pendingMedia,pendingQuality);}
            else showMessage("没有开始下载","Android 7–9 需要存储权限才能把视频保存到公共下载目录。链接已保留。");
            pendingMedia=null;pendingQuality=null;pendingRetryId=null;
        }
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);readShare(intent);}
    private void readShare(Intent intent) {
        if(intent!=null&&Intent.ACTION_SEND.equals(intent.getAction())) {
            String value=intent.getStringExtra(Intent.EXTRA_TEXT);
            if(value!=null&&!value.isEmpty()){if(parsing)cancelAnalysis();draft=value;showTab("new");input.setText(value);feedback.setVisibility(View.VISIBLE);feedback.setText("已接收分享链接，点击解析即可。");intent.setAction(null);}
        }
    }
    private final AppUpdates.Listener appUpdateListener=state->{
        boolean ready=state.phase==AppUpdates.Phase.READY;
        if((!ready&&state.phase!=AppUpdates.Phase.AVAILABLE)||state.offer==null||isFinishing()||isDestroyed())return;
        AppUpdates updates=AppUpdates.get(this);
        if(ready){if(!updates.shouldNotifyReady(state.offer.code))return;updates.notifiedReady(state.offer.code);}
        else{if(!updates.shouldNotify(state.offer.code))return;updates.notified(state.offer.code);}
        new AlertDialog.Builder(this).setTitle(ready?"拾流更新已下载":"拾流有新版本 "+state.offer.version)
                .setMessage(ready?"可返回更新页确认覆盖安装，不会自动安装或清除数据。":"安装包 "+UpdateActivity.size(state.offer.size)+"。可查看更新内容后决定下载，不会自动安装。")
                .setNegativeButton("稍后",null).setPositiveButton("查看更新",(d,w)->startActivity(new Intent(this,UpdateActivity.class))).show();
    };
    private final Runnable appUpdateCheck=()->AppUpdates.get(this).checkIfDue();
    @Override protected void onResume(){super.onResume();AppUpdates.get(this).listen(appUpdateListener);main.postDelayed(appUpdateCheck,1500);main.removeCallbacks(poll);main.post(poll);if(libraryView!=null)libraryView.refresh();}
    @Override protected void onPause(){AppUpdates.get(this).unlisten(appUpdateListener);main.removeCallbacks(appUpdateCheck);if(openSwipeRow!=null)openSwipeRow.close(false);queueTouchActive=false;super.onPause();main.removeCallbacks(poll);if(input!=null)draft=input.getText().toString();getPreferences(0).edit().putString("draft",draft).apply();}
    @Override protected void onSaveInstanceState(Bundle state){
        super.onSaveInstanceState(state);state.putString("draft",input!=null?input.getText().toString():draft);state.putString("tab",tab);state.putString("queue_filter",queueFilter);state.putString("quality_selection",new JSONObject(qualitySelections).toString());
        if(libraryView!=null)libraryState=libraryView.saveUiState();if(libraryState!=null)state.putBundle("library_state",libraryState);
        try{org.json.JSONArray media=new org.json.JSONArray();for(Engine.Media m:inspectedMedia)media.put(m.toJson());state.putString("inspection_media",media.toString());}catch(Exception ignored){}
    }
    @Override protected void onDestroy(){super.onDestroy();main.removeCallbacks(poll);main.removeCallbacks(commitRemovals);if(!isChangingConfigurations())commitPendingRemovals(false);pendingRemoval.clear();if(libraryView!=null)libraryView.release();if(artwork!=null)artwork.release();++parseGeneration;engine.cancelInspect();io.shutdownNow();}

    private static boolean isActive(String s){return Arrays.asList("queued","downloading","merging","saving","cancelling","pausing").contains(s);}
    private static String statusName(String s){switch(s){case"queued":return"等待下载";case"downloading":return"正在下载";case"merging":return"正在合并音视频";case"saving":return"正在保存到手机";case"completed":return"已保存";case"cancelling":return"正在取消";case"cancelled":return"已取消";case"pausing":return"正在暂停";case"paused":return"已暂停，断点保留";case"interrupted":return"上次下载中断，可重试";default:return"下载失败";}}
    private String friendly(Exception e){String m=e.getMessage();if(m==null||m.trim().isEmpty())return"操作未完成，请重试或检查内核。";return Engine.cleanLog(m);}
    private void action(LinearLayout row,String label,Runnable click){Button b=quietButton(label);b.setMinWidth(0);b.setPadding(dp(10),0,dp(10),0);b.setTextSize(12);b.setOnClickListener(v->click.run());row.addView(b,new LinearLayout.LayoutParams(-2,dp(44)));}
    private void divider(LinearLayout c){View v=new View(this);v.setBackgroundColor(LINE);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(1));p.topMargin=dp(23);p.bottomMargin=dp(23);c.addView(v,p);}
    private void showMessage(String title,String message){TextView t=text(message,14,INK,false);t.setTextIsSelectable(true);t.setPadding(dp(22),dp(15),dp(22),dp(12));ScrollView s=new ScrollView(this);s.addView(t);new AlertDialog.Builder(this).setTitle(title).setView(s).setPositiveButton("知道了",null).show();}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(3),1);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private Button button(String title,boolean primary){
        Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextSize(14);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{MUTED,primary?(palette.dark?0xFF171A1C:Color.WHITE):INK}));
        b.setMinHeight(dp(44));b.setPadding(dp(16),0,dp(16),0);b.setStateListAnimator(null);
        StateListDrawable surface=new StateListDrawable();surface.addState(new int[]{-android.R.attr.state_enabled},shape(palette.control,12,primary?0:LINE));surface.addState(new int[]{},shape(primary?(palette.dark?0xFFEEF1F2:INK):SURFACE,12,primary?0:LINE));
        b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(primary?0x24FFFFFF:0x14397D72),surface,null));UiTheme.press(b);return b;
    }
    private Button quietButton(String title){Button b=button(title,false);b.setTypeface(Typeface.DEFAULT,Typeface.NORMAL);b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x0D000000),shape(android.graphics.Color.TRANSPARENT,10,0),null));return b;}
    private StateListDrawable fieldBackground(){StateListDrawable states=new StateListDrawable();GradientDrawable focused=shape(palette.control,12,ACCENT);focused.setStroke(dp(2),ACCENT);states.addState(new int[]{android.R.attr.state_focused},focused);states.addState(new int[]{},shape(palette.control,12,0));return states;}
    private GradientDrawable shape(int color,int radius,int border){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(border!=0)d.setStroke(dp(1),border);return d;}
    private void add(LinearLayout parent,View child,int top,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);p.bottomMargin=dp(bottom);parent.addView(child,p);}
    private static String mediaSummary(Engine.Media media,int selection){
        if(selection>0)return media.qualities.get(selection).label+" · 大小待下载时确定";
        String value=media.width>0&&media.height>0?media.width+" × "+media.height:"";
        if(media.filesize>0)value+=(value.isEmpty()?"":" · ")+(media.filesizeEstimated?"预计 ":"")+MediaSize.format(media.filesize);
        return value.isEmpty()?"实际大小在开始下载后确定":value;
    }
    private static String duration(long seconds){return String.format(Locale.ROOT,"%d:%02d",Math.max(0,seconds)/60,Math.max(0,seconds)%60);}
    private ImageView cover(String source,String key,int width,int height){ImageView image=new ImageView(this);image.setBackground(shape(palette.control,12,0));image.setClipToOutline(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setContentDescription("视频封面");artwork.load(image,source,key);return image;}
    private void clearArtwork(View view){if(artwork==null||view==null)return;if(view instanceof ImageView)artwork.cancel((ImageView)view);if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)clearArtwork(group.getChildAt(i));}}
    private final class NavIcon extends android.graphics.drawable.Drawable {
        private final int kind,color;private final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        NavIcon(int kind,int color){this.kind=kind;this.color=color;}
        @Override public void draw(android.graphics.Canvas canvas){android.graphics.Rect b=getBounds();canvas.save();canvas.translate(b.left,b.top);canvas.scale(b.width()/24f,b.height()/24f);paint.setColor(color);paint.setStyle(android.graphics.Paint.Style.STROKE);paint.setStrokeWidth(1.7f);paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            if(kind==0){canvas.drawRoundRect(3,3,21,21,5,5,paint);canvas.drawLine(12,7,12,17,paint);canvas.drawLine(7,12,17,12,paint);}
            else if(kind==1){canvas.drawLine(12,3,12,15,paint);canvas.drawLine(7,10,12,15,paint);canvas.drawLine(17,10,12,15,paint);android.graphics.Path p=new android.graphics.Path();p.moveTo(4,16);p.lineTo(4,21);p.lineTo(20,21);p.lineTo(20,16);canvas.drawPath(p,paint);}
            else if(kind==2){canvas.drawRoundRect(3,4,21,20,3,3,paint);android.graphics.Path p=new android.graphics.Path();p.moveTo(10,8);p.lineTo(16,12);p.lineTo(10,16);p.close();canvas.drawPath(p,paint);}
            else{canvas.drawCircle(12,12,8,paint);canvas.drawCircle(12,12,3,paint);for(int i=0;i<8;i++){double a=Math.PI*i/4;canvas.drawLine(12+(float)Math.cos(a)*8,12+(float)Math.sin(a)*8,12+(float)Math.cos(a)*10,12+(float)Math.sin(a)*10,paint);}}
            canvas.restore();}
        @Override public void setAlpha(int a){paint.setAlpha(a);}@Override public void setColorFilter(android.graphics.ColorFilter f){paint.setColorFilter(f);}@Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    }

}
