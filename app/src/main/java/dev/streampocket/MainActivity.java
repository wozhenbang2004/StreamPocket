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
    private Engine collectionEngine;
    private LinearLayout brand, inspectionResults, notice, linkEditor, homeExtras, addLinkRow, linkPreview;
    private boolean linkEditorExpanded;
    private PocketDialog clipboardReview;
    private SwipeActionRow openSwipeRow;
    private boolean queueTouchActive;
    private FrameLayout navigationFrame;
    private View navigationPill;
    private final Button[] destinations=new Button[4];
    private final InspectionInbox inspectionInbox=new InspectionInbox();
    private final List<Engine.Media> inspectedMedia=inspectionInbox.media;
    private InspectionInbox.Job activeInspection;
    private final Map<String,Integer> qualitySelections=new HashMap<>();
    private final Map<String,JSONObject> pendingRemoval=new LinkedHashMap<>();
    private Bundle libraryState;
    private String queueFilter="all";
    private PocketTabs queueTabs;
    private final Runnable commitRemovals=()->commitPendingRemovals(true);
    private static final int COOKIE_REQUEST=51, NOTIFY_REQUEST=52, STORAGE_REQUEST=53, LOGIN_REQUEST=54, DIRECTORY_REQUEST=55, MOVE_STORAGE_REQUEST=56;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ExecutorService fileIo=Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean fileMoveCancelled=new java.util.concurrent.atomic.AtomicBoolean();
    private boolean movingFiles;
    private Engine engine;
    private VideoLibraryView libraryView;
    private LinearLayout root, body, navigation;
    private EditText input;
    private TextView feedback,linkHint;
    private final Runnable previewLinks=this::renderLinkHint;
    private Button parse, cancel, failureDetails, paste;
    private String lastInspectionMessage="", lastInspectionDetails="";
    private String draft="", tab="new", lastQueueSignature="";
    private LinearLayout clipboardNotice;
    private List<String> clipboardUrls=Collections.emptyList();
    private String clipboardSourceDigest="";
    private boolean foreground=false,clipboardCheckPending=false;
    private final Runnable checkClipboard=()->checkClipboardOffer();
    private InspectionBatch.Inspector inspector;
    private final ArrayList<Engine.Media> pendingBatch=new ArrayList<>();
    private boolean parsing=false, loginOpening=false, taskMenuOpen=false;
    private String cookieImportPlatform;
    private String loginRecoveryId, loginRecoveryType;
    private volatile int parseGeneration=0;
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
        artwork=new MediaArtwork(this); engine=new Engine(this);inspector=engine::inspect;
        inspectionInbox.restore(getPreferences(0).getString("inspection_inbox",""));
        try{JSONObject selections=new JSONObject(getPreferences(0).getString("inspection_quality","{}"));Iterator<String> keys=selections.keys();while(keys.hasNext()){String key=keys.next();qualitySelections.put(key,selections.optInt(key));}}catch(Exception ignored){}
        if(state!=null){
            cookieImportPlatform=state.getString("cookie_import_platform");
            loginRecoveryId=state.getString("login_recovery_id");loginRecoveryType=state.getString("login_recovery_type");
            linkEditorExpanded=state.getBoolean("link_editor_expanded",false);
            lastInspectionMessage=state.getString("inspection_message","");lastInspectionDetails=state.getString("inspection_details","");
            libraryState=state.getBundle("library_state");queueFilter=state.getString("queue_filter","all");
            try{JSONObject selections=new JSONObject(state.getString("quality_selection","{}"));Iterator<String> keys=selections.keys();while(keys.hasNext()){String key=keys.next();qualitySelections.put(key,selections.optInt(key));}}catch(Exception ignored){}
            if(inspectedMedia.isEmpty())try{org.json.JSONArray saved=new org.json.JSONArray(state.getString("inspection_media","[]"));for(int n=0;n<saved.length();n++)inspectedMedia.add(Engine.Media.fromJson(saved.getJSONObject(n)));}catch(Exception ignored){}
        }
        draft=state==null?getPreferences(0).getString("draft",""):state.getString("draft","");
        tab=state==null?"new":state.getString("tab","new");
        UiTheme.systemBars(this);
        buildShell();
        readShare(getIntent());main.post(this::startNextInspection);
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
        clipboardNotice=column();clipboardNotice.setVisibility(View.GONE);root.addView(clipboardNotice);
        notice=column();notice.setVisibility(View.GONE);root.addView(notice);
        navigationFrame=new FrameLayout(this);navigationFrame.setBackgroundColor(SURFACE);
        navigationPill=new View(this);navigationPill.setBackground(shape(palette.accentSoft,12,0));
        FrameLayout.LayoutParams pill=new FrameLayout.LayoutParams(dp(56),dp(48));pill.topMargin=dp(6);navigationFrame.addView(navigationPill,pill);
        navigation=row();navigation.setPadding(dp(10),dp(4),dp(10),dp(4));navigationFrame.addView(navigation,new FrameLayout.LayoutParams(-1,dp(64)));
        String[] labels={"新建","下载","视频库","设置"},ids={"new","queue","library","settings"};
        for(int i=0;i<ids.length;i++){
            final String id=ids[i];Button b=quietButton(labels[i]);destinations[i]=b;
            b.setTextSize(11);b.setPadding(0,dp(6),0,dp(3));b.setMinWidth(0);b.setSingleLine(true);b.setGravity(Gravity.CENTER);
            b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(palette.dark?0x16FFFFFF:0x14000000),null,null));
            b.setOnClickListener(v->showTab(id));navigation.addView(b,new LinearLayout.LayoutParams(0,-1,1));
        }
        root.addView(navigationFrame,new LinearLayout.LayoutParams(-1,dp(64)));
        setContentView(root);root.requestApplyInsets();showTab(tab);
    }

    private void showTab(String destination) {
        if(input!=null)draft=input.getText().toString();
        if(libraryView!=null){libraryState=libraryView.saveUiState();libraryView.release();libraryView=null;}
        if(openSwipeRow!=null)openSwipeRow.close(false);openSwipeRow=null;queueTouchActive=false;
        clearArtwork(body);tab=destination;body.removeAllViews();input=null;inspectionResults=null;homeExtras=null;
        brand.setVisibility(View.GONE);
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
            float x=navigation.getPaddingLeft()+index*width+(width-dp(56))/2;
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
        LinearLayout c=scrollContent();c.addView(text("新建下载",22,INK,true));
        add(c,text("粘贴分享文案，选择视频或音频再保存",13,MUTED,false),6,16);
        LinearLayout form=column();form.setPadding(dp(16),dp(12),dp(16),dp(16));form.setBackground(shape(SURFACE,16,LINE));form.setElevation(dp(2));if(android.os.Build.VERSION.SDK_INT>=28){form.setOutlineAmbientShadowColor(0x18000000);form.setOutlineSpotShadowColor(0x20000000);}
        addLinkRow=row();Button more=quietButton("＋ 继续添加链接");more.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);more.setTextColor(ACCENT);more.setOnClickListener(v->{linkEditorExpanded=true;updateLinkEditor();input.requestFocus();((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(input,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);});addLinkRow.addView(more,new LinearLayout.LayoutParams(0,dp(48),1));
        Button quickPaste=quietButton("粘贴链接");quickPaste.setTextSize(13);quickPaste.setTextColor(ACCENT);quickPaste.setOnClickListener(v->pasteLink());addLinkRow.addView(quickPaste,new LinearLayout.LayoutParams(dp(88),dp(48)));form.addView(addLinkRow);
        linkEditor=column();form.addView(linkEditor,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout label=row();label.addView(text("视频链接",14,INK,true),new LinearLayout.LayoutParams(0,-2,1));
        paste=quietButton("粘贴");paste.setMinWidth(0);paste.setTextColor(ACCENT);paste.setOnClickListener(v->pasteLink());label.addView(paste,new LinearLayout.LayoutParams(dp(64),dp(48)));linkEditor.addView(label);
        input=new PocketEditText(this);input.setTextSize(14);input.setTextColor(INK);input.setHintTextColor(MUTED);
        input.setHint("粘贴一个或多个链接，也支持整段分享文案");input.setGravity(Gravity.TOP);
        input.setSingleLine(false);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_VARIATION_URI);input.setMinLines(2);input.setMaxLines(4);input.setContentDescription("视频链接，可粘贴多个链接或分享文案");
        input.setBackground(fieldBackground());input.setPadding(dp(12),dp(12),dp(12),dp(12));input.setText(draft);input.setEnabled(true);input.setLineSpacing(dp(3),1);
        input.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence a,int b,int c,int d){}public void onTextChanged(CharSequence a,int b,int c,int d){}public void afterTextChanged(android.text.Editable value){if(!value.toString().equals(draft)){draft=value.toString();updateInspectionControls();}}});add(linkEditor,input,0,8);
        linkHint=text("",12,MUTED,false);linkHint.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);add(linkEditor,linkHint,0,0);
        linkPreview=column();add(linkEditor,linkPreview,6,0);
        parse=button("解析链接",true);parse.setOnClickListener(v->analyze());renderLinkHint();
        Button collection=quietButton("展开合集并选择");collection.setTextSize(13);collection.setTextColor(ACCENT);
        collection.setOnClickListener(v->showCollection());
        // One primary action; the optional collection route stays beside it, not another full-width stage.
        LinearLayout importActions=row();
        boolean stackActions=getResources().getConfiguration().screenWidthDp<360||getResources().getConfiguration().fontScale>1.2f;
        if(stackActions){importActions.setOrientation(LinearLayout.VERTICAL);importActions.addView(parse,new LinearLayout.LayoutParams(-1,-2));importActions.addView(collection,new LinearLayout.LayoutParams(-1,-2));}
        else{importActions.addView(parse,new LinearLayout.LayoutParams(0,dp(48),1));LinearLayout.LayoutParams collectionAction=new LinearLayout.LayoutParams(dp(120),dp(48));collectionAction.leftMargin=dp(8);importActions.addView(collection,collectionAction);}
        add(linkEditor,importActions,12,0);
        cancel=quietButton("取消解析");cancel.setVisibility(View.GONE);cancel.setOnClickListener(v->cancelAnalysis());linkEditor.addView(cancel,new LinearLayout.LayoutParams(-1,dp(48)));
        feedback=text(lastInspectionMessage,12,MUTED,false);feedback.setTextIsSelectable(true);
        feedback.setVisibility(!lastInspectionMessage.isEmpty()?View.VISIBLE:View.GONE);add(form,feedback,10,0);
        failureDetails=quietButton("查看 / 复制错误详情");failureDetails.setVisibility(!parsing&&!lastInspectionDetails.isEmpty()?View.VISIBLE:View.GONE);
        failureDetails.setOnClickListener(v->showInspectionDetails());form.addView(failureDetails,new LinearLayout.LayoutParams(-1,dp(48)));c.addView(form);
        inspectionResults=column();add(c,inspectionResults,12,0);renderInspection();
        homeExtras=column();c.addView(homeExtras);
        if(inspectedMedia.isEmpty()&&inspectionInbox.jobs.isEmpty()){
            c=homeExtras;
            List<JSONObject> completed=new ArrayList<>();for(JSONObject task:TaskStore.all(this))if("completed".equals(task.optString("status")))completed.add(task);
            if(!completed.isEmpty()){
                LinearLayout head=row();head.addView(text("最近保存",14,INK,true),new LinearLayout.LayoutParams(0,-2,1));boolean includesAudio=completed.stream().limit(2).anyMatch(t->!t.optString("mime","video/mp4").startsWith("video/"));Button all=quietButton(includesAudio?"查看下载":"查看视频库");all.setTextSize(12);all.setTextColor(ACCENT);all.setOnClickListener(v->showTab(includesAudio?"queue":"library"));head.addView(all,new LinearLayout.LayoutParams(dp(96),dp(48)));add(c,head,14,4);
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
        updateLinkEditor();
        clearArtwork(inspectionResults);inspectionResults.removeAllViews();
        boolean resolved=!inspectedMedia.isEmpty();if(homeExtras!=null)homeExtras.setVisibility(resolved||!inspectionInbox.jobs.isEmpty()?View.GONE:View.VISIBLE);
        int waiting=inspectionInbox.count("queued"),failed=inspectionInbox.count("failed");
        if(parsing||waiting>0||failed>0){
            LinearLayout queue=row();TextView summary=text(parsing?(activeInspection!=null&&activeInspection.cancelled?"正在取消当前解析":"正在解析 · "+waiting+" 个等待")
                    :waiting>0?waiting+" 个链接等待解析":failed+" 个链接解析失败",13,INK,true);
            queue.addView(summary,new LinearLayout.LayoutParams(0,-2,1));
            Button inspect=quietButton("查看队列");inspect.setTextSize(12);inspect.setTextColor(ACCENT);inspect.setOnClickListener(v->showInspectionQueue());queue.addView(inspect,new LinearLayout.LayoutParams(dp(88),dp(48)));add(inspectionResults,queue,0,4);
            if(activeInspection!=null&&inspectionInbox.jobs.contains(activeInspection)){
                LinearLayout current=row();TextView source=text(linkLabel(activeInspection.url),12,MUTED,false);source.setMaxLines(2);source.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);current.addView(source,new LinearLayout.LayoutParams(0,-2,1));
                Button stop=quietButton(activeInspection.cancelled?"正在取消":"取消");stop.setTextSize(12);stop.setEnabled(!activeInspection.cancelled);stop.setOnClickListener(v->cancelAnalysis());current.addView(stop,new LinearLayout.LayoutParams(dp(64),dp(48)));add(inspectionResults,current,0,8);
            }
            if(failed>1){LinearLayout retry=row();retry.addView(text("也可批量重新解析",12,MUTED,false),new LinearLayout.LayoutParams(0,-2,1));
                Button all=quietButton("重试失败");all.setTextSize(12);all.setTextColor(ACCENT);all.setOnClickListener(v->{inspectionInbox.retryFailed();persistInspectionInbox();startNextInspection();renderInspection();});retry.addView(all,new LinearLayout.LayoutParams(dp(88),dp(48)));add(inspectionResults,retry,0,8);}
            int shown=0;for(InspectionInbox.Job job:new ArrayList<>(inspectionInbox.jobs))if(job.state.equals("failed")&&shown++<3){
                LinearLayout failedItem=column();failedItem.setPadding(dp(12),dp(8),dp(12),dp(8));failedItem.setBackground(shape(SURFACE,12,0));
                TextView link=text(linkLabel(job.url).replace("\n"," · "),13,INK,true);link.setMaxLines(1);link.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);LinearLayout failedTitle=row();failedTitle.addView(link,new LinearLayout.LayoutParams(0,-2,1));Button removeFailed=quietButton("×");removeFailed.setTextSize(20);removeFailed.setMinWidth(0);removeFailed.setPadding(0,0,0,0);removeFailed.setContentDescription("删除解析任务："+job.url);removeFailed.setOnClickListener(v->removeInspectionJob(job));failedTitle.addView(removeFailed,new LinearLayout.LayoutParams(dp(48),dp(48)));failedItem.addView(failedTitle);
                add(failedItem,text(inspectionFailureMessage(job),12,MUTED,false),6,0);LinearLayout actions=row();
                Button details=quietButton("错误详情");details.setTextSize(12);details.setEnabled(!job.details.isEmpty());details.setOnClickListener(v->showMessage("解析错误详情",job.details));actions.addView(details,new LinearLayout.LayoutParams(0,dp(48),1));
                Button retry=quietButton("重新解析");retry.setTextColor(ACCENT);retry.setTextSize(13);retry.setOnClickListener(v->{inspectionInbox.retry(job);persistInspectionInbox();startNextInspection();renderInspection();});actions.addView(retry,new LinearLayout.LayoutParams(dp(96),dp(48)));failedItem.addView(actions);add(inspectionResults,failedItem,0,8);
                if(canOfferLogin(job.url,job.details)){Button login=quietButton(accountRecoveryLabel(job.url));login.setTextColor(ACCENT);login.setTextSize(13);login.setOnClickListener(v->showLoginRecovery("inspection",job.id,job.url));add(failedItem,login,0,0);}
            }
            if(failed>3)add(inspectionResults,text("另有 "+(failed-3)+" 条失败记录，可在查看队列中重试",12,MUTED,false),0,8);
        }
        if(inspectedMedia.isEmpty())return;
        LinearLayout heading=row();heading.addView(text("待下载 · "+inspectedMedia.size()+" 项",16,INK,true),new LinearLayout.LayoutParams(0,-2,1));
        if(inspectedMedia.size()>1){Button all=quietButton("全部下载");all.setTextSize(12);all.setTextColor(ACCENT);all.setOnClickListener(v->enqueueBatch(new ArrayList<>(inspectedMedia)));heading.addView(all,new LinearLayout.LayoutParams(dp(96),dp(48)));}add(inspectionResults,heading,6,4);
        LinearLayout readyTools=row();readyTools.addView(text("保存到 "+DownloadLocation.label(this),12,MUTED,false),new LinearLayout.LayoutParams(0,-2,1));
        if(inspectedMedia.size()>1){Button batch=quietButton("批量设置");batch.setTextSize(12);batch.setTextColor(ACCENT);batch.setOnClickListener(v->showBatchSettings());readyTools.addView(batch,new LinearLayout.LayoutParams(dp(88),dp(48)));}add(inspectionResults,readyTools,0,8);
        for(Engine.Media m:inspectedMedia){
            LinearLayout item=row();item.setGravity(Gravity.TOP);item.setPadding(dp(12),dp(12),dp(12),dp(12));item.setBackground(shape(SURFACE,14,0));
            item.addView(cover(m.thumbnail,"inspect:"+m.url+":"+m.id,72,96),new LinearLayout.LayoutParams(dp(72),dp(96)));
            LinearLayout words=column();TextView name=text(m.title==null||m.title.isEmpty()?"未命名视频":m.title,14,INK,true);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout readyTitle=row();readyTitle.setBaselineAligned(false);readyTitle.setGravity(Gravity.TOP);readyTitle.addView(name,new LinearLayout.LayoutParams(0,-2,1));Button remove=quietButton("×");remove.setTextSize(20);remove.setMinWidth(0);remove.setPadding(0,0,0,0);remove.setContentDescription("删除解析结果："+m.title);remove.setOnClickListener(v->{inspectedMedia.remove(m);qualitySelections.remove(InspectionBatch.key(m));persistInspectionInbox();renderInspection();});readyTitle.addView(remove,new LinearLayout.LayoutParams(dp(40),dp(40)));words.addView(readyTitle);
            TextView metadata=text(m.platform+(m.duration>0?" · "+duration(m.duration):"")+(m.uploader!=null&&!m.uploader.isEmpty()?" · "+m.uploader:""),12,MUTED,false);metadata.setMaxLines(2);metadata.setEllipsize(android.text.TextUtils.TruncateAt.END);add(words,metadata,4,0);LinearLayout.LayoutParams info=new LinearLayout.LayoutParams(0,-2,1);info.leftMargin=dp(12);item.addView(words,info);
            String key=InspectionBatch.key(m);List<Engine.Quality> choices=m.downloadChoices();int chosen=Math.max(0,Math.min(qualitySelections.getOrDefault(key,0),choices.size()-1));
            TextView details=text(mediaSummary(m,chosen),12,MUTED,false);add(words,details,4,0);
            LinearLayout actions=row();Button quality=quietButton((m.album?"图片："+m.imageCount+" 张":choices.get(chosen).audioOnly()?"音频："+choices.get(chosen).audioFormat.toUpperCase(Locale.ROOT):"画质："+choices.get(chosen).label)+"  ▾");quality.setTextSize(12);quality.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);quality.setMinWidth(0);
            quality.setOnClickListener(v->{String[] options=new String[choices.size()];for(int n=0;n<options.length;n++)options[n]=choices.get(n).label;
                PocketDialog panel=new PocketDialog.Builder(this).setTitle(m.hasAudio?"保存视频或音频":"选择画质").setSingleChoiceItems(options,chosen,(d,which)->{qualitySelections.put(key,which);persistInspectionInbox();d.dismiss();renderInspection();}).setNegativeButton("返回",null).show();bottom(panel);});
            quality.setPadding(0,0,dp(4),0);quality.setSingleLine(true);quality.setEllipsize(android.text.TextUtils.TruncateAt.END);
            actions.addView(quality,new LinearLayout.LayoutParams(0,dp(48),1));Button save=button(m.album?"保存图集":choices.get(chosen).audioOnly()?"下载音频":"下载视频",true);save.setTextSize(12);save.setPadding(dp(10),0,dp(10),0);save.setOnClickListener(v->enqueue(m,choices.get(Math.max(0,Math.min(qualitySelections.getOrDefault(key,0),choices.size()-1)))));actions.addView(save,new LinearLayout.LayoutParams(dp(88),dp(48)));add(words,actions,4,0);
            if(!m.album){Button extras=quietButton(attachmentLabel(m));extras.setTextSize(12);extras.setTextColor(MUTED);extras.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);extras.setPadding(0,0,0,0);extras.setOnClickListener(v->showAttachments(m));words.addView(extras,new LinearLayout.LayoutParams(-1,dp(40)));}
            add(inspectionResults,item,0,8);
        }
    }

    private void showInspectionQueue() {
        LinearLayout content=column();content.setPadding(dp(20),dp(8),dp(20),dp(12));
        ScrollView scroll=new ScrollView(this);scroll.addView(content);
        PocketDialog panel=new PocketDialog.Builder(this).setTitle("解析队列").setView(scroll).setNegativeButton("返回",null).create();
        for(InspectionInbox.Job job:new ArrayList<>(inspectionInbox.jobs)){
            LinearLayout line=row();LinearLayout labels=column();TextView link=text(job.url,13,INK,false);link.setMaxLines(2);link.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);labels.addView(link);
            String state=job.state.equals("failed")?inspectionFailureMessage(job):job.state.equals("parsing")?"正在解析":job.state.equals("cancelling")?"正在取消":"等待解析";
            add(labels,text(state,12,job.state.equals("failed")?palette.danger:MUTED,false),4,0);line.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            if(job.state.equals("failed")){Button retry=quietButton("重试");retry.setTextSize(12);retry.setTextColor(ACCENT);retry.setOnClickListener(v->{inspectionInbox.retry(job);persistInspectionInbox();panel.dismiss();startNextInspection();renderInspection();});line.addView(retry,new LinearLayout.LayoutParams(dp(56),dp(48)));}
            Button action=quietButton("删除");action.setTextSize(12);action.setTextColor(MUTED);action.setContentDescription("删除解析任务："+job.url);action.setOnClickListener(v->{
                panel.dismiss();removeInspectionJob(job);
            });line.addView(action,new LinearLayout.LayoutParams(dp(64),dp(48)));add(content,line,8,12);
            if(job.state.equals("failed")&&!job.details.isEmpty()){Button details=quietButton("查看错误详情");details.setTextSize(12);details.setOnClickListener(v->showMessage("解析错误详情",job.details));add(content,details,0,8);}
            if(job.state.equals("failed")&&canOfferLogin(job.url,job.details)){Button login=quietButton(accountRecoveryLabel(job.url));login.setTextColor(ACCENT);login.setOnClickListener(v->{panel.dismiss();showLoginRecovery("inspection",job.id,job.url);});add(content,login,0,8);}
        }
        if(inspectionInbox.jobs.isEmpty())content.addView(text("解析队列为空，已解析的视频仍在待下载列表。",13,MUTED,false));
        panel.show();bottom(panel);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if(focused&&foreground&&clipboardCheckPending)main.post(checkClipboard);
    }

    private void checkClipboardOffer() {
        if(!foreground||!hasWindowFocus()||!clipboardCheckPending||isDestroyed())return;
        clipboardCheckPending=false;
        if(!getSharedPreferences("config",0).getBoolean("clipboard_suggestions",true))return;
        try {
            ClipboardManager manager=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            if(manager==null||!manager.hasPrimaryClip())return;
            ClipDescription description=manager.getPrimaryClipDescription();
            if(description==null||description.getExtras()!=null&&description.getExtras().getBoolean("android.content.extra.IS_SENSITIVE",false))return;
            ClipData data=manager.getPrimaryClip();if(data==null)return;
            StringBuilder content=new StringBuilder();
            for(int n=0;n<data.getItemCount();n++){
                CharSequence text=data.getItemAt(n).getText();
                if(text==null&&data.getItemAt(n).getUri()!=null)text=data.getItemAt(n).getUri().toString();
                if(text!=null){if(content.length()+text.length()>65536)return;content.append(text).append('\n');}
            }
            List<String> urls=LinkBatch.parse(content.toString()).urls;
            String digest=ClipboardOffer.fingerprint(urls);
            if(digest.equals(getPreferences(0).getString("clipboard_seen",""))||ClipboardOffer.alreadyInDraft(urls,draft))return;
            Set<String> known=new HashSet<>();for(InspectionInbox.Job job:inspectionInbox.jobs)known.add(LinkBatch.identity(job.url));for(Engine.Media media:inspectedMedia)known.add(LinkBatch.identity(media.url));
            try{for(String url:LinkBatch.parse(draft).urls)known.add(LinkBatch.identity(url));}catch(IllegalArgumentException ignored){}
            List<String> fresh=new ArrayList<>();for(String url:urls)if(!known.contains(LinkBatch.identity(url)))fresh.add(url);
            if(fresh.isEmpty())return;
            clipboardSourceDigest=digest;clipboardUrls=new ArrayList<>(fresh);renderClipboardOffer();
        }catch(IllegalArgumentException ignored){ /* Ordinary clipboard text is not a video task. */ }
        catch(SecurityException ignored){ /* Manual paste and system Share remain available. */ }
    }

    private void renderClipboardOffer() {
        if(clipboardNotice==null||clipboardUrls.isEmpty())return;
        clipboardNotice.removeAllViews();clipboardNotice.setPadding(dp(16),dp(6),dp(16),dp(8));clipboardNotice.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        LinearLayout card=row();card.setPadding(dp(14),dp(8),dp(4),dp(8));card.setBackground(shape(SURFACE,14,LINE));
        LinearLayout labels=column();labels.addView(text("发现 "+clipboardUrls.size()+" 个新链接",14,INK,true));TextView preview=text(linkLabel(clipboardUrls.get(0)).replace('\n',' '),12,MUTED,false);preview.setMaxLines(1);preview.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);add(labels,preview,4,0);card.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        Button open=button("查看链接",true);open.setTextSize(12);open.setMinWidth(0);open.setOnClickListener(v->showClipboardReview());card.addView(open,new LinearLayout.LayoutParams(dp(88),dp(48)));
        Button dismiss=quietButton("×");dismiss.setTextSize(20);dismiss.setMinWidth(0);dismiss.setContentDescription("忽略剪贴板链接提醒");dismiss.setOnClickListener(v->{rememberClipboardDigest(clipboardSourceDigest.isEmpty()?ClipboardOffer.fingerprint(clipboardUrls):clipboardSourceDigest);hideClipboardOffer();});card.addView(dismiss,new LinearLayout.LayoutParams(dp(44),dp(48)));
        clipboardNotice.addView(card);clipboardNotice.setVisibility(View.VISIBLE);clipboardNotice.announceForAccessibility("发现 "+clipboardUrls.size()+" 个新链接，查看后选择要解析的链接");
    }

    private void showClipboardReview() {
        if(clipboardUrls.isEmpty()||clipboardReview!=null&&clipboardReview.isShowing())return;
        hideLinkKeyboard();
        List<String> snapshot=new ArrayList<>(clipboardUrls);String sourceDigest=clipboardSourceDigest.isEmpty()?ClipboardOffer.fingerprint(snapshot):clipboardSourceDigest;boolean[] selected=new boolean[snapshot.size()];Arrays.fill(selected,true);
        String[] labels=new String[snapshot.size()];for(int n=0;n<labels.length;n++)labels[n]=linkLabel(snapshot.get(n));
        clipboardReview=new PocketDialog.Builder(this).setTitle("剪贴板 · "+snapshot.size()+" 个链接").setMultiChoiceBulkActions(snapshot.size()>1)
                .setMultiChoiceItems(labels,selected,(d,which,checked)->{
                    selected[which]=checked;int count=0;for(boolean value:selected)if(value)count++;
                    Button confirm=((PocketDialog)d).getButton(PocketDialog.BUTTON_POSITIVE);confirm.setText("解析 "+count+" 个");confirm.setContentDescription("解析所选的 "+count+" 个链接");confirm.setEnabled(count>0);
                }).setNegativeButton("忽略这些链接",(d,w)->{rememberClipboardDigest(sourceDigest);hideClipboardOffer();})
                .setPositiveButton("解析 "+snapshot.size()+" 个",(d,w)->{
                    List<String> chosen=new ArrayList<>();for(int n=0;n<snapshot.size();n++)if(selected[n])chosen.add(snapshot.get(n));
                    if(!chosen.isEmpty()){clipboardUrls=chosen;acceptClipboardOffer();rememberClipboardDigest(sourceDigest);}
                }).create();
        clipboardReview.setOnDismissListener(d->{clipboardReview=null;if(foreground&&clipboardNotice!=null&&clipboardNotice.isShown())clipboardNotice.requestFocus();});
        clipboardReview.show();bottom(clipboardReview);
    }

    private void acceptClipboardOffer() {
        if(clipboardUrls.isEmpty())return;
        List<String> accepted=new ArrayList<>(clipboardUrls);rememberClipboard(accepted);hideClipboardOffer();queueInspections(accepted);showTab("new");
    }
    private void rememberClipboard(List<String> urls){rememberClipboardDigest(ClipboardOffer.fingerprint(urls));}
    private void rememberClipboardDigest(String digest){getPreferences(0).edit().putString("clipboard_seen",digest).apply();}
    private void hideClipboardOffer(){clipboardUrls=Collections.emptyList();clipboardSourceDigest="";if(clipboardNotice!=null){clipboardNotice.setVisibility(View.GONE);clipboardNotice.removeAllViews();}}

    private void pasteLink() {
        try {
            ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            if(clipboard==null||!clipboard.hasPrimaryClip())throw new IllegalArgumentException("剪贴板为空，请先复制视频链接");
            ClipData data=clipboard.getPrimaryClip();if(data==null)throw new IllegalArgumentException("暂时无法读取剪贴板，请长按输入框粘贴");
            StringBuilder content=new StringBuilder();
            for(int n=0;n<data.getItemCount();n++){
                CharSequence value=data.getItemAt(n).getText();if(value==null&&data.getItemAt(n).getUri()!=null)value=data.getItemAt(n).getUri().toString();
                if(value!=null){if(content.length()+value.length()+1>65536)throw new IllegalArgumentException("剪贴板内容太长，请分批粘贴");content.append(value).append('\n');}
            }
            LinkBatch.Result links=LinkBatch.parse(content.toString());
            Set<String> known=new HashSet<>();try{for(String url:LinkBatch.parse(draft).urls)known.add(LinkBatch.identity(url));}catch(IllegalArgumentException ignored){}
            List<String> fresh=new ArrayList<>();for(String url:links.urls)if(known.add(LinkBatch.identity(url)))fresh.add(url);
            if(fresh.isEmpty()){showLinkMessage("这些链接已在输入中，可直接解析",false);return;}
            String next=draft.trim().isEmpty()?LinkBatch.join(fresh,"\n"):draft+(draft.endsWith("\n")?"":"\n")+LinkBatch.join(fresh,"\n");
            // Validate before updating, so a full batch keeps its existing editable contents.
            LinkBatch.parse(next);linkEditorExpanded=true;input.setText(next);input.setSelection(input.length());rememberClipboard(links.urls);hideClipboardOffer();renderLinkHint();
        } catch(IllegalArgumentException invalid){showLinkMessage(invalid.getMessage());}
        catch(SecurityException denied){showLinkMessage("暂时无法读取剪贴板，请长按输入框粘贴");}
    }

    private void showLinkMessage(String message){showLinkMessage(message,true);}
    private void showLinkMessage(String message,boolean error){main.removeCallbacks(previewLinks);linkEditorExpanded=true;updateLinkEditor();if(linkHint!=null){linkHint.setText(message);linkHint.setTextColor(error?palette.danger:MUTED);linkHint.announceForAccessibility(message);}}
    private void hideLinkKeyboard(){if(input!=null){input.clearFocus();android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(input.getWindowToken(),0);}}
    private void updateLinkEditor(){if(linkEditor==null||addLinkRow==null)return;boolean work=parsing||!inspectionInbox.jobs.isEmpty()||!inspectedMedia.isEmpty();boolean expanded=!work||!draft.trim().isEmpty()||linkEditorExpanded;linkEditor.setVisibility(expanded?View.VISIBLE:View.GONE);addLinkRow.setVisibility(expanded?View.GONE:View.VISIBLE);}
    private static String linkLabel(String url){VideoPlatform channel=VideoPlatform.detect(url);String platform=channel==null?"视频链接":channel.title;if(channel==null)try{platform=new URI(url).getHost();}catch(Exception ignored){}
        String visible=url.replaceFirst("(?i)^https?://","");if(visible.length()>200)visible=visible.substring(0,140)+"…"+visible.substring(visible.length()-50);return platform+"\n"+visible;}

    private void analyze() {
        try {
            LinkBatch.Result links=LinkBatch.parse(input.getText().toString());rememberClipboard(links.urls);
            hideLinkKeyboard();queueInspections(links.urls);linkEditorExpanded=false;draft="";input.setText("");lastInspectionMessage="";lastInspectionDetails="";
            if(feedback!=null)feedback.setVisibility(View.GONE);if(failureDetails!=null)failureDetails.setVisibility(View.GONE);updateInspectionControls();
            if(links.duplicates>0||!links.invalid.isEmpty())toast("已合并 "+links.duplicates+" 个重复链接，忽略 "+links.invalid.size()+" 个无效链接");
        } catch(IllegalArgumentException bad){feedback.setText(bad.getMessage());feedback.setVisibility(View.VISIBLE);}
    }

    private void queueInspections(List<String> urls) {
        int added=inspectionInbox.add(urls);persistInspectionInbox();
        toast(added>0?"已添加 "+added+" 个链接，按顺序解析":"链接已在解析队列或待下载列表中");startNextInspection();renderInspection();
    }
    private void startNextInspection() {
        if(parsing||isDestroyed())return;
        InspectionInbox.Job job=inspectionInbox.next();if(job==null){updateInspectionControls();return;}
        parsing=true;activeInspection=job;job.state="parsing";job.message="正在读取视频信息…";job.cancelled=false;
        int generation=++parseGeneration;persistInspectionInbox();updateInspectionControls();if("new".equals(tab))renderInspection();
        io.execute(()->{
            List<Engine.Media> found=null;Exception problem=null;
            try {found=inspector.inspect(job.url,stage->main.post(()->{if(!isDestroyed()&&generation==parseGeneration&&activeInspection==job&&!job.cancelled){job.message=stage;if("new".equals(tab))renderInspection();}}),()->job.cancelled||generation!=parseGeneration||isDestroyed());}
            catch(Exception failure){problem=failure;}
            final List<Engine.Media> result=found;final Exception failure=problem;
            main.post(()->{
                if(isDestroyed()||generation!=parseGeneration)return;
                if(job.cancelled||failure instanceof InterruptedException)inspectionInbox.fail(job,new InterruptedException("解析已取消"));
                else if(failure!=null)inspectionInbox.fail(job,failure);
                else if(result==null||result.isEmpty())inspectionInbox.fail(job,new IOException("源站没有返回可用视频"));
                else inspectionInbox.complete(job,result);
                parsing=false;activeInspection=null;persistInspectionInbox();updateInspectionControls();if("new".equals(tab))renderInspection();startNextInspection();
            });
        });
    }
    private void updateInspectionControls() {
        updateInspectionButton();
        if(input!=null)input.setEnabled(true);if(paste!=null)paste.setEnabled(true);if(cancel!=null)cancel.setVisibility(View.GONE);main.removeCallbacks(previewLinks);main.postDelayed(previewLinks,180);
        updateLinkEditor();
    }
    private void renderLinkHint(){
        if(input==null||linkHint==null||!"new".equals(tab))return;
        linkHint.setTextColor(MUTED);if(linkPreview!=null)linkPreview.removeAllViews();
        updateInspectionButton();
        if(draft.trim().isEmpty()){linkHint.setText("支持 YouTube、X、B站、抖音、小红书等，可批量粘贴");return;}
        try{LinkBatch.Result links=LinkBatch.parse(draft);linkHint.setText("识别到 "+links.urls.size()+" 个链接"+(links.duplicates>0?" · "+links.duplicates+" 个重复已合并":"")+(!links.invalid.isEmpty()?" · "+links.invalid.size()+" 个无效链接已跳过":""));
            if(links.urls.size()==1)linkHint.append(" · "+linkLabel(links.urls.get(0)).split("\n",2)[0]);
            if(linkPreview!=null&&links.urls.size()>1){for(int n=0;n<Math.min(3,links.urls.size());n++){String label=linkLabel(links.urls.get(n));TextView preview=text(label.replace("\n"," · "),12,MUTED,false);preview.setContentDescription(label);preview.setMaxLines(1);preview.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);preview.setPadding(0,dp(4),0,dp(4));linkPreview.addView(preview);}
                if(links.urls.size()>3)linkPreview.addView(text("另有 "+(links.urls.size()-3)+" 个链接，按输入顺序解析",12,MUTED,false));}
        }catch(IllegalArgumentException invalid){linkHint.setText(invalid.getMessage());linkHint.setTextColor(palette.danger);}
    }
    private void updateInspectionButton(){if(parse!=null){try{LinkBatch.Result links=LinkBatch.parse(draft);parse.setText((parsing?"追加解析 · ":"解析链接 · ")+links.urls.size()+" 个");parse.setEnabled(true);}catch(IllegalArgumentException invalid){parse.setText("解析链接");parse.setEnabled(false);}}}
    private void persistInspectionInbox(){getPreferences(0).edit().putString("inspection_inbox",inspectionInbox.json()).putString("inspection_quality",new JSONObject(qualitySelections).toString()).apply();}
    private static String inspectionFailureMessage(InspectionInbox.Job job){return job.message.contains("取消")||job.details.isEmpty()?job.message:CoreRules.inspectionMessage(new Exception(job.details),job.url);}
    private void cancelAnalysis() {
        if(activeInspection==null)return;activeInspection.cancelled=true;activeInspection.state="cancelling";engine.cancelInspect();persistInspectionInbox();if("new".equals(tab))renderInspection();
    }

    private void removeInspectionJob(InspectionInbox.Job job){boolean active=job==activeInspection;inspectionInbox.remove(job);if(active)engine.cancelInspect();if(job.id.equals(loginRecoveryId))clearLoginRecovery();persistInspectionInbox();startNextInspection();renderInspection();}

    private void showInspectionDetails() {
        String details="拾流 "+BuildConfig.VERSION_NAME+"\n\n"+Engine.cleanLog(lastInspectionDetails);
        TextView view=text(details,13,INK,false);view.setTextIsSelectable(true);view.setPadding(dp(22),dp(15),dp(22),dp(12));
        ScrollView scroll=new ScrollView(this);scroll.addView(view);
        new PocketDialog.Builder(this).setTitle("解析错误详情").setView(scroll).setPositiveButton("返回",null)
                .setNeutralButton("复制详情",(d,w)->{
                    ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    if(clipboard!=null){clipboard.setPrimaryClip(ClipData.newPlainText("拾流解析错误",details));toast("错误详情已复制");}
                }).show();
    }

    private void showMedia(List<Engine.Media> media) {
        inspectionInbox.complete(null,media);persistInspectionInbox();if("new".equals(tab))renderInspection();
    }

    private void enqueue(Engine.Media media,Engine.Quality quality) {
        String key=InspectionBatch.key(media);for(int n=0;n<media.downloadChoices().size();n++)if(media.downloadChoices().get(n).id.equals(quality.id)){qualitySelections.put(key,n);break;}
        enqueueBatch(Collections.singletonList(media));
    }

    private boolean needsStoragePermission(){return Build.VERSION.SDK_INT<29&&DownloadLocation.tree(this)==null&&checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED;}

    private boolean needsStoragePermission(JSONObject task){return Build.VERSION.SDK_INT<29&&taskUsesDefaultLocation(task,DownloadLocation.tree(this)==null)&&checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED;}
    static boolean taskUsesDefaultLocation(JSONObject task,boolean defaultForNew){
        if(task==null)return defaultForNew;
        String status=task.optString("status");boolean restart=("failed".equals(status)||"interrupted".equals(status)||"cancelled".equals(status))
                &&task.optString("uri").isEmpty()&&(task.optJSONArray("attachments")==null||task.optJSONArray("attachments").length()==0);
        return task.has("destinationTree")&&!restart?task.optString("destinationTree").isEmpty():defaultForNew;
    }

    private void enqueueBatch(List<Engine.Media> media) {
        if(media.isEmpty())return;
        if(needsStoragePermission()){
            pendingBatch.clear();pendingBatch.addAll(media);requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},STORAGE_REQUEST);return;
        }
        try {
            List<Engine.Quality> qualities=new ArrayList<>();
            for(Engine.Media m:media){int chosen=qualitySelections.getOrDefault(InspectionBatch.key(m),0);qualities.add(m.downloadChoices().get(Math.max(0,Math.min(chosen,m.downloadChoices().size()-1))));}
            ArrayList<String> ids=TaskStore.createBatch(this,media,qualities);
            Intent request=new Intent(this,DownloadService.class).setAction(DownloadService.ENQUEUE).putStringArrayListExtra("ids",ids);
            try {if(Build.VERSION.SDK_INT>=26)startForegroundService(request);else startService(request);}
            catch(Exception failure){for(String id:ids)TaskStore.markStopped(this,id,"interrupted","未能启动下载，点击重试");throw failure;}
            Set<String> added=new HashSet<>();for(Engine.Media m:media)added.add(InspectionBatch.key(m));
            inspectedMedia.removeIf(m->added.contains(InspectionBatch.key(m)));for(String key:added)qualitySelections.remove(key);persistInspectionInbox();
            toast("已加入 "+ids.size()+" 个任务，按顺序下载");showTab("queue");
            if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFY_REQUEST);
        }catch(Exception failure){showMessage("暂时无法加入队列",friendly(failure));}
    }

    private void startTask(String id) {
        if(needsStoragePermission(TaskStore.get(this,id))){
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
        clearArtwork(body);body.removeAllViews();LinearLayout c=scrollContent();LinearLayout heading=row();heading.addView(text("下载",22,INK,true),new LinearLayout.LayoutParams(0,-2,1));heading.addView(text("本机任务",12,MUTED,false));c.addView(heading);
        String[] labels={"全部","进行中","已完成","失败"},keys={"all","active","completed","failed"};int selected=0;for(int n=0;n<keys.length;n++)if(keys[n].equals(queueFilter))selected=n;
        if(queueTabs==null)queueTabs=new PocketTabs(this,labels,selected,index->{queueFilter=keys[index];refreshQueue(true);});
        if(queueTabs.getParent() instanceof ViewGroup)((ViewGroup)queueTabs.getParent()).removeView(queueTabs);queueTabs.selection(selected);add(c,queueTabs,14,16);
        int queuePosition=0,shown=0;
        for(JSONObject task:tasks){
            String status=task.optString("status"),id=task.optString("id");if("queued".equals(status))queuePosition++;
            if(pendingRemoval.containsKey(id)||!matchesFilter(status))continue;shown++;
            boolean active=isActive(status),pauseable=DownloadService.canPause(task);
            LinearLayout item=row();item.setGravity(Gravity.TOP);item.setPadding(dp(12),dp(12),dp(4),dp(12));item.setBackground(shape(SURFACE,12,0));
            JSONObject media=task.optJSONObject("media");String poster=media==null?"":media.optString("thumbnail");if(poster.isEmpty()&&"completed".equals(status))poster=task.optString("uri");
            FrameLayout thumbnail=new FrameLayout(this);thumbnail.setBackground(shape(palette.control,10,0));thumbnail.setClipToOutline(true);
            ImageView cover=cover(poster,id,64,64);thumbnail.addView(cover,new FrameLayout.LayoutParams(-1,-1));
            String primary="completed".equals(status)?(task.optString("mime").equals("application/zip")?"打开":"播放"):"paused".equals(status)?"继续":"pausing".equals(status)?"暂停中":"cancelling".equals(status)?"取消中":active?(pauseable?"暂停":"处理中"):"重试";
            Button action=quietButton(primary);action.setTextColor(Color.WHITE);action.setTextSize(12);action.setPadding(0,0,0,0);action.setMinWidth(0);action.setBackground(new android.graphics.drawable.InsetDrawable(shape(0xB3171A1C,18,0),dp(6)));
            action.setEnabled((!active||pauseable)&&!"pausing".equals(status)&&!"cancelling".equals(status));action.setContentDescription(primary+"："+task.optString("title"));action.setVisibility(action.isEnabled()?View.VISIBLE:View.GONE);
            action.setOnClickListener(v->performTaskPrimary(id));thumbnail.addView(action,new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.CENTER));item.addView(thumbnail,new LinearLayout.LayoutParams(dp(64),dp(64)));
            LinearLayout content=column();LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-2,1);cp.leftMargin=dp(12);item.addView(content,cp);
            TextView name=text(task.optString("title","视频"),14,INK,true);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);content.addView(name);
            double progress=task.optDouble("progress",-1);boolean known=(media==null||!media.optBoolean("album"))&&"downloading".equals(status)&&!task.optString("phase").isEmpty()&&!task.optBoolean("transferComplete")&&progress>=0&&progress<100;
            String state="queued".equals(status)?"等待下载 · 第 "+queuePosition+" 位":taskPhaseTitle(task);
            LinearLayout stage=row();TextView stateLabel=text(state,12,"completed".equals(status)?ACCENT:MUTED,false);stage.addView(stateLabel,new LinearLayout.LayoutParams(0,-2,1));
            String prefix=task.optBoolean("progressEstimated")?"整体约 ":"整体 ";String percent="completed".equals(status)?"100%":known?prefix+(int)progress+"%":"";
            if(!percent.isEmpty())stage.addView(text(percent,12,MUTED,false));add(content,stage,6,0);
            String size=MediaSize.taskSummary(task);JSONObject outputChoice=task.optJSONObject("quality");if(outputChoice!=null&&Engine.Quality.fromJson(outputChoice).audioOnly())size=outputChoice.optString("audioFormat").toUpperCase(Locale.ROOT)+" 音频"+(size.isEmpty()?"":" · "+size);if(!size.isEmpty())add(content,text(size,12,MUTED,false),4,0);
            if(active&&!"queued".equals(status)){
                ProgressBar bar=new PocketProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(LINE));bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ACCENT));bar.setIndeterminate(!known);bar.setProgress((int)Math.min(99,Math.max(0,progress)));bar.setContentDescription(known?prefix+(int)progress+"%":state+"，整体完成时间暂无法估计");add(content,bar,6,0);bar.getLayoutParams().height=dp(4);
            }
            String error=taskDisplayError(task),summary=error.isEmpty()?("completed".equals(status)?task.optString("attachmentNote"):taskCompactDetail(task,known)):error;
            if(!summary.isEmpty()){TextView detail=text(summary,12,error.isEmpty()?MUTED:palette.danger,false);detail.setMaxLines(2);detail.setEllipsize(android.text.TextUtils.TruncateAt.END);add(content,detail,5,0);}
            if("failed".equals(status)&&canOfferLogin(task.optString("url"),task.optString("log",task.optString("userError")))){Button recover=quietButton(accountRecoveryLabel(task.optString("url")));recover.setTextSize(12);recover.setTextColor(ACCENT);recover.setOnClickListener(v->showLoginRecovery("download",id,task.optString("url")));content.addView(recover,new LinearLayout.LayoutParams(-2,dp(40)));}
            Button more=quietButton("");PocketIcon menuIcon=new PocketIcon("more",INK);menuIcon.setBounds(0,0,dp(24),dp(24));more.setCompoundDrawables(menuIcon,null,null,null);more.setContentDescription("任务更多操作："+task.optString("title"));more.setPadding(0,0,0,0);more.setMinWidth(0);more.setOnClickListener(v->showTaskActions(v,task));item.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48)));
            item.setContentDescription(task.optString("title")+("completed".equals(status)?"，点按播放，更多查看任务详情":"，点按查看进度及详情"));
            item.setOnClickListener(v->{JSONObject current=TaskStore.get(this,id);if(current!=null){if("completed".equals(current.optString("status")))openFile(current,false);else showTask(current);}});item.setOnLongClickListener(v->{showTaskActions(v,task);return true;});
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
        PocketMenu menu=new PocketMenu(this,anchor);menu.getMenu().add("详情").setOnMenuItemClickListener(i->{JSONObject fresh=TaskStore.get(this,current.optString("id"));if(fresh!=null)showTask(fresh);return true;});
        String status=task.optString("status"),id=task.optString("id");
        if(task.optJSONArray("attachments")!=null&&task.optJSONArray("attachments").length()>0)menu.getMenu().add("字幕与封面").setOnMenuItemClickListener(i->{showSavedAttachments(current);return true;});
        if("failed".equals(status)&&canOfferLogin(task.optString("url"),task.optString("log",task.optString("userError"))))
            menu.getMenu().add(accountRecoveryLabel(task.optString("url"))).setOnMenuItemClickListener(i->{showLoginRecovery("download",id,current.optString("url"));return true;});
        if("completed".equals(status))menu.getMenu().add(current.optString("mime").startsWith("audio/")?"分享音频":current.optString("mime").startsWith("video/")?"分享视频":"分享文件").setOnMenuItemClickListener(i->{openFile(current,true);return true;});
        if(isActive(status)&&!"cancelling".equals(status)&&!"pausing".equals(status))menu.getMenu().add("取消任务").setOnMenuItemClickListener(i->{startService(new Intent(this,DownloadService.class).setAction("dev.streampocket.CANCEL").putExtra("id",id));return true;});
        if(!isActive(status))menu.getMenu().add("移除记录").setOnMenuItemClickListener(i->{removeTaskWithUndo(current);return true;});menu.setOnDismissListener(d->taskMenuOpen=false);taskMenuOpen=true;menu.show();
    }

    private void removeTaskWithUndo(JSONObject task){
        JSONObject current=TaskStore.get(this,task.optString("id"));
        if(current==null||isActive(current.optString("status"))){if(current!=null)toast("任务已重新开始，记录已保留");if("queue".equals(tab))refreshQueue(true);return;}
        pendingRemoval.put(task.optString("id"),task);main.removeCallbacks(commitRemovals);main.postDelayed(commitRemovals,5000);
        refreshQueue(true);notice.removeAllViews();notice.setPadding(dp(16),dp(4),dp(16),dp(4));notice.setBackgroundColor(palette.control);
        LinearLayout row=row();row.addView(text("5秒后移除记录，文件保留",12,INK,false),new LinearLayout.LayoutParams(0,-2,1));Button undo=quietButton("撤销");undo.setTextColor(ACCENT);undo.setOnClickListener(v->{main.removeCallbacks(commitRemovals);pendingRemoval.clear();hideNotice();refreshQueue(true);});row.addView(undo,new LinearLayout.LayoutParams(dp(64),dp(48)));notice.addView(row);notice.setVisibility(View.VISIBLE);
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
                    ? "标准下载" : task.optString("transferReason");
            default:return "";
        }
    }

    private void openFile(JSONObject task,boolean share) {
        String raw=task.optString("uri"); if(raw.isEmpty()){toast("没有找到保存位置");return;}
        if(!share&&task.optString("mime","video/mp4").startsWith("video/")){
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
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);i.setClipData(ClipData.newRawUri(task.optString("mime").startsWith("audio/")?"音频":task.optString("mime").startsWith("video/")?"视频":"文件",uri));
            startActivity(Intent.createChooser(i,share?"分享文件":"选择播放器"));
        }catch(ActivityNotFoundException e){toast("没有可打开此文件的应用");}
        catch(Exception e){showMessage("无法打开", "文件可能已被移动或删除。\n"+friendly(e));}
    }

    private static String taskDisplayError(JSONObject task){String original=task.optString("userError"),log=task.optString("log");return "failed".equals(task.optString("status"))&&!log.isEmpty()&&SourceFailure.kind(log)==SourceFailure.Kind.NO_FORMATS?CoreRules.userMessage(new IOException(log)):original;}

    private void showTask(JSONObject task) {
        String message=task.optString("title")+"\n\n"+statusName(task.optString("status"))+"\n"+
                task.optString("detail")+"\n"+MediaSize.taskSummary(task)+"\n"+taskTransferDetail(task)+"\n\n"+taskDisplayError(task)+"\n\n"+task.optString("url")+
                (task.optString("uri").isEmpty()?"":"\n\n"+task.optString("uri"));
        if(!task.optString("attachmentNote").isEmpty())message+="\n\n"+task.optString("attachmentNote");
        if(!task.optString("attachmentError").isEmpty())message+="\n\n附件诊断\n"+Engine.cleanLog(task.optString("attachmentError"));
        if(!task.optString("log").isEmpty())message+="\n\n诊断记录（敏感参数已隐藏）\n"+Engine.cleanLog(task.optString("log"));
        showMessage("任务详情",message);
    }

    private void showSettings() {
        LinearLayout c=scrollContent();c.addView(text("设置",22,INK,true));
        section(c,"账户与来源");LinearLayout accounts=settingsGroup(c);TextView accountSummary=settingRow(accounts,"账号管理","读取中…",this::showLoginSettings);settingRow(accounts,"支持的平台","视频与音频",this::showSupportedPlatforms);
        io.execute(()->{String summary;try{AccountStore store=new AccountStore(this);store.prepare();int count=0;for(LoginPlatform p:LoginPlatform.values())if(store.hasSaved(p))count++;summary=count==0?"未登录":count+" 个平台已保存";}catch(Exception e){summary="点此管理";}String value=summary;main.post(()->{if(!isDestroyed()&&"settings".equals(tab)&&accountSummary.isAttachedToWindow())accountSummary.setText(value);});});
        section(c,"下载管理");LinearLayout downloads=settingsGroup(c);settingRow(downloads,"下载与速度",downloadConnections()+" 路 · 最高可用",this::showDownloadSettings);settingRow(downloads,"保存位置",DownloadLocation.label(this),this::showStorageSettings);
        switchRow(downloads,"剪贴板链接提醒","clipboard_suggestions",true);
        section(c,"播放与外观");LinearLayout appearance=settingsGroup(c);settingRow(appearance,"外观主题",themeLabel(),this::showAppearanceSettings);settingRow(appearance,"快进 / 快退",getSharedPreferences("config",0).getInt("seek_seconds",10)+" 秒",this::showSeekSettings);
        switchRow(appearance,"播放手势","player_gestures",false);switchRow(appearance,"界面动效","motion_enabled",true);
        section(c,"高级与关于");LinearLayout advanced=settingsGroup(c);String proxy=getSharedPreferences("config",0).getString("proxy","");settingRow(advanced,"网络与代理",proxy==null||proxy.isEmpty()?"跟随手机网络":"独立代理",this::showNetworkSettings);settingRow(advanced,"下载支持","更新或检查下载功能",this::showParserSettings);settingRow(advanced,"应用更新",BuildConfig.VERSION_NAME,()->startActivity(new Intent(this,UpdateActivity.class)));settingRow(advanced,"关于拾流",BuildConfig.VERSION_NAME,this::showAboutSettings);
        add(c,text("视频、账号与设置仅保存在本机",12,MUTED,false),18,0);
    }
    private void showSupportedPlatforms(){
        showMessage("支持的平台","YouTube、X / Twitter、哔哩哔哩、抖音\n\n试用渠道\n小红书、快手、TikTok、Instagram、Facebook、Pinterest、Pornhub、小黑盒\n\n复制单个视频的分享链接即可尝试。部分内容需要登录或源站验证，可能受地区和网络限制；图文、整批频道和进行中的直播暂不支持。\n\n有声音的视频可在画质选项中选择 MP3 或 M4A。音频保存在下载记录中，可打开或分享。");
    }
    private void section(LinearLayout c,String title){add(c,text(title,12,MUTED,true),16,8);}
    private LinearLayout settingsGroup(LinearLayout c){LinearLayout group=column();group.setPadding(dp(14),0,dp(14),0);group.setBackground(shape(SURFACE,12,LINE));c.addView(group,new LinearLayout.LayoutParams(-1,-2));return group;}
    private void switchRow(LinearLayout parent,String title,String key,boolean fallback){
        LinearLayout r=row();r.setMinimumHeight(dp(56));r.addView(text(title,14,INK,false),new LinearLayout.LayoutParams(0,-2,1));PocketSwitch control=new PocketSwitch(this);control.setMinHeight(dp(48));control.setContentDescription(title);control.setChecked(getSharedPreferences("config",0).getBoolean(key,fallback));control.setOnCheckedChangeListener((v,checked)->{getSharedPreferences("config",0).edit().putBoolean(key,checked).apply();if(key.equals("clipboard_suggestions")&&!checked)hideClipboardOffer();});r.addView(control);parent.addView(r);
    }
    private String themeLabel(){String t=getSharedPreferences("config",0).getString("appearance_theme","system");return "dark".equals(t)?"深色":"light".equals(t)?"浅色":"跟随系统";}
    private void showAppearanceSettings(){
        String[] labels={"跟随系统","浅色","深色"},values={"system","light","dark"};String value=getSharedPreferences("config",0).getString("appearance_theme","system");int selected=Arrays.asList(values).indexOf(value);
        PocketDialog panel=new PocketDialog.Builder(this).setTitle("外观主题").setSingleChoiceItems(labels,Math.max(0,selected),(d,which)->{getSharedPreferences("config",0).edit().putString("appearance_theme",values[which]).apply();d.dismiss();recreate();}).setNegativeButton("返回",null).show();bottom(panel);
    }
    private void showSeekSettings(){
        int[] values={5,10,15,30};String[] labels={"5 秒","10 秒","15 秒","30 秒"};int now=getSharedPreferences("config",0).getInt("seek_seconds",10),selected=1;for(int i=0;i<values.length;i++)if(values[i]==now)selected=i;
        PocketDialog panel=new PocketDialog.Builder(this).setTitle("双击快进 / 快退").setSingleChoiceItems(labels,selected,(d,which)->{getSharedPreferences("config",0).edit().putInt("seek_seconds",values[which]).apply();d.dismiss();showTab("settings");}).setNegativeButton("返回",null).show();bottom(panel);
    }

    private int downloadConnections() {
        int value=getSharedPreferences("config",0).getInt("download_connections",4);
        return value==1||value==8?value:4;
    }

    private void showDownloadSettings() {
        LinearLayout c=column();
        c.addView(text("选择同时下载的连接数",14,INK,true));
        add(c,text("可调整下载连接数，已开始的任务继续按原设置下载。",13,MUTED,false),8,12);
        RadioGroup options=new RadioGroup(this);options.setOrientation(RadioGroup.VERTICAL);
        int[] values={1,4,8};String[] labels={"标准 · 1 路连接","并行 · 4 路连接（推荐）","并行 · 8 路连接"};
        int selected=downloadConnections();
        for(int i=0;i<values.length;i++){
            PocketRadio option=new PocketRadio(this);option.setId(View.generateViewId());option.setTag(values[i]);
            option.setText(labels[i]);option.setTextSize(14);option.setTextColor(INK);option.setMinHeight(dp(52));
            option.setButtonTintList(android.content.res.ColorStateList.valueOf(INK));option.setPadding(dp(40),dp(4),dp(12),dp(4));
            options.addView(option,new RadioGroup.LayoutParams(-1,-2));if(values[i]==selected)option.setChecked(true);
        }
        c.addView(options,new LinearLayout.LayoutParams(-1,-2));
        add(c,text("更多连接不一定更快。实际速度取决于手机网络和源站，下次开始任务时生效。",12,MUTED,false),12,18);
        Button save=button("保存下载设置",true);c.addView(save,new LinearLayout.LayoutParams(-1,dp(48)));
        PocketDialog panel=settingsPanel("下载与速度",c);
        save.setOnClickListener(v->{
            View chosen=options.findViewById(options.getCheckedRadioButtonId());
            int value=chosen!=null&&chosen.getTag() instanceof Integer?(Integer)chosen.getTag():4;
            getSharedPreferences("config",0).edit().putInt("download_connections",value).apply();
            panel.dismiss();showTab("settings");toast("已保存，下次开始下载时生效");
        });
    }

    private void showStorageSettings(){
        LinearLayout c=column();c.addView(text("当前保存位置",12,MUTED,false));add(c,text(DownloadLocation.label(this),15,INK,true),6,16);
        add(c,text("新下载的视频、音频、图集和附件会保存在这里。",13,MUTED,false),0,16);
        Button choose=button("更改保存位置",true);c.addView(choose,new LinearLayout.LayoutParams(-1,dp(48)));
        Button move=button(movingFiles?"正在移动文件…":"一键剪切已有下载",false);move.setEnabled(!movingFiles);add(c,move,12,0);
        add(c,text("移动已完成的下载，保留视频库、观看进度和精彩标记。",12,MUTED,false),8,16);
        Button reset=quietButton("恢复默认位置");if(DownloadLocation.tree(this)!=null)c.addView(reset,new LinearLayout.LayoutParams(-1,dp(48)));
        PocketDialog panel=settingsPanel("保存位置",c);
        choose.setOnClickListener(v->{
            try{Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                if(Build.VERSION.SDK_INT>=26&&DownloadLocation.tree(this)!=null)picker.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,DownloadLocation.tree(this));
                startActivityForResult(picker,DIRECTORY_REQUEST);panel.dismiss();
            }catch(ActivityNotFoundException|SecurityException unavailable){showMessage("无法选择文件夹","手机未提供可用的文件夹选择器，请检查系统文件应用。");}
        });
        reset.setOnClickListener(v->{reset.setEnabled(false);fileIo.execute(()->{String error=null;try{DownloadLocation.reset(this);}catch(Exception e){error=friendly(e);}String failure=error;main.post(()->{if(isDestroyed())return;if(failure!=null){reset.setEnabled(true);showMessage("无法更改保存位置",failure);return;}panel.dismiss();showTab("settings");showStorageSettings();});});});
        move.setOnClickListener(v->{panel.dismiss();moveDownloadedFiles();});
    }

    private void moveDownloadedFiles(){
        if(needsStoragePermission()){requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},MOVE_STORAGE_REQUEST);return;}
        if(movingFiles)return;movingFiles=true;fileMoveCancelled.set(false);
        TextView progress=text("准备移动到 "+DownloadLocation.label(this),14,INK,false);progress.setPadding(dp(24),dp(16),dp(24),dp(16));
        PocketDialog panel=new PocketDialog.Builder(this).setTitle("正在移动文件").setView(progress)
                .setNegativeButton("停止移动",(d,w)->fileMoveCancelled.set(true)).setOnCancelListener(d->fileMoveCancelled.set(true)).show();bottom(panel);
        fileIo.execute(()->{
            String summary;
            try{summary=DownloadMover.moveCompleted(this,fileMoveCancelled::get,value->main.post(()->{if(!isDestroyed()&&panel.isShowing())progress.setText(value);})).summary();}
            catch(Exception failure){summary=friendly(failure);}
            String result=summary;main.post(()->{movingFiles=false;if(isDestroyed())return;panel.dismiss();if(libraryView!=null)libraryView.refresh();if("settings".equals(tab))showTab("settings");showMessage("文件移动",result);});
        });
    }

    private void showLoginSettings() {
        LinearLayout c=column();
        c.addView(text("选择平台，在官方网页中登录。",14,INK,true));
        add(c,text("登录信息只在本机使用。",13,MUTED,false),8,12);
        LinearLayout platforms=column();platforms.addView(text("正在读取本机登录状态…",13,MUTED,false));c.addView(platforms);
        Button importCookie=quietButton("导入登录信息");add(c,importCookie,16,0);
        Button clearAll=quietButton("清除全部登录信息");add(c,clearAll,0,0);
        PocketDialog panel=settingsPanel("账号管理",c);
        importCookie.setOnClickListener(v->openCookieImport(panel));
        clearAll.setOnClickListener(v->new PocketDialog.Builder(this).setTitle("清除全部本机登录信息？")
                .setMessage("后续下载将不再使用这些账号。正在进行的任务不受影响。")
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
                for(AccountState state:result){
                    boolean choose=state.saved||(state.platform!=LoginPlatform.X&&state.platform!=LoginPlatform.BILIBILI&&state.platform!=LoginPlatform.DOUYIN);
                    settingRow(platforms,state.platform.title,accountStatus(state),choose?"管理":"登录",()->{
                        if(choose){panel.dismiss();showAccountDetails(state);}
                        else launchLogin(state.platform,panel);
                    });
                }
            });
        });
    }

    private List<AccountState> readAccountStates() throws Exception {
        AccountStore store=new AccountStore(this);store.prepare();List<AccountState> states=new ArrayList<>();
        for(LoginPlatform platform:LoginPlatform.values())states.add(new AccountState(platform,store.hasSaved(platform),
                store.isEnabled(platform),store.savedAt(platform),store.source(platform)));
        return states;
    }

    private String accountStatus(AccountState state) {
        if(!state.saved)return "尚未添加账号";
        if(!state.enabled)return "登录信息已暂停";
        return "需要重新登录".equals(new AccountStore(this).requestStatus(state.platform))?"需要检查账号状态":"登录信息已启用";
    }

    private void showAccountDetails(AccountState state) {
        LoginPlatform platform=state.platform;LinearLayout c=column();
        TextView savedStatus=text(accountStatus(state),14,INK,true);c.addView(savedStatus);
        if(state.saved){
            String time=state.savedAt>0?android.text.format.DateFormat.format("yyyy-MM-dd HH:mm",state.savedAt).toString():"时间未知";
            add(c,text(state.source+" · "+time,12,MUTED,false),6,0);
        }
        add(c,text(state.saved?(state.enabled?"解析与下载会自动使用此账号。部分内容仍需要账号具备访问权限。":"此账号已暂停，启用后用于解析与下载。 "):"登录后可尝试下载账号有权访问的内容。",13,MUTED,false),8,18);
        Button savedRetry=null;
        if(loginRecoveryId!=null&&state.saved){savedRetry=button(state.enabled?"使用此账号重试":"启用账号并重试",true);c.addView(savedRetry,new LinearLayout.LayoutParams(-1,dp(48)));}
        if("inspection".equals(loginRecoveryType))add(c,text("重试只处理这条链接，不会自动开始下载。",12,MUTED,false),8,12);
        Button login=button(state.saved?"打开官方登录页":"登录"+platform.title,!state.saved);c.addView(login,new LinearLayout.LayoutParams(-1,dp(48)));
        TextView result=text("",13,MUTED,false);result.setVisibility(View.GONE);
        PocketDialog panel=new PocketDialog.Builder(this).setTitle(platform.title).setView(accountPanelContent(c))
                .setPositiveButton("返回",(d,w)->returnFromAccount()).setOnCancelListener(d->returnFromAccount()).create();
        if(savedRetry!=null){
            Button retry=savedRetry;String recoveryId=loginRecoveryId,recoveryType=loginRecoveryType;
            retry.setOnClickListener(v->{retry.setEnabled(false);io.execute(()->{
                String error=null;try{if(!state.enabled)new AccountStore(this).setEnabled(platform,true);}catch(Exception e){error=friendly(e);}String failure=error;
                main.post(()->{if(isDestroyed()||!panel.isShowing()||!recoveryId.equals(loginRecoveryId))return;
                    if(failure!=null){retry.setEnabled(true);showMessage("暂时无法启用账号",failure);return;}
                    panel.dismiss();clearLoginRecovery();resumeAfterLogin(recoveryType,recoveryId);
                });
            });});
        }
        login.setOnClickListener(v->{
            login.setEnabled(false);
            if(!launchLogin(platform,panel))login.setEnabled(true);
        });
        {
            Button importLogin=quietButton("导入登录信息");add(c,importLogin,8,0);
            importLogin.setOnClickListener(v->new PocketDialog.Builder(this).setTitle("导入 "+platform.title+" 登录信息")
                    .setMessage("选择从浏览器导出的 "+platform.title+" 登录文件（cookies.txt），仅保存在本机。")
                    .setNegativeButton("返回",null).setPositiveButton("选择文件",(d,w)->openCookieImport(panel,platform)).show());
            if(platform==LoginPlatform.YOUTUBE)add(c,text("公开视频通常无需登录。登录后仍受平台权限限制。",12,MUTED,false),8,0);
        }
        if(state.saved){
            PocketSwitch enabled=new PocketSwitch(this);enabled.setText("用于后续解析和下载");enabled.setTextSize(14);enabled.setTextColor(INK);
            enabled.setChecked(state.enabled);enabled.setMinHeight(dp(48));add(c,enabled,16,0);
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
            clear.setOnClickListener(v->new PocketDialog.Builder(this).setTitle("清除"+platform.title+"登录信息？")
                    .setMessage("后续下载将不再使用这个账号。正在进行的任务不受影响。")
                    .setNegativeButton("保留",null).setPositiveButton("清除",(d,w)->{
                        clear.setEnabled(false);login.setEnabled(false);enabled.setEnabled(false);
                        io.execute(()->{
                            String error=null;try{new AccountStore(this).clear(platform);}catch(Exception e){error=friendly(e);}
                            String failure=error;main.post(()->{
                                if(isDestroyed()||!panel.isShowing())return;
                                if(failure!=null){clear.setEnabled(true);login.setEnabled(true);enabled.setEnabled(true);result.setText(failure);result.setVisibility(View.VISIBLE);return;}
                                panel.dismiss();refreshSettingsSummary();toast("已清除"+platform.title+"的本机登录信息");returnFromAccount();
                            });
                        });
                    }).show());
        }
        add(c,result,10,0);panel.show();
    }

    private ScrollView accountPanelContent(LinearLayout content) {
        content.setPadding(dp(24),dp(12),dp(24),dp(8));ScrollView scroll=new ScrollView(this);scroll.addView(content);return scroll;
    }

    private static boolean canOfferLogin(String url,String details){
        VideoPlatform channel=VideoPlatform.detect(url);
        if(channel==null||LoginPlatform.fromId(channel.id)==null||details.contains("InterruptedException"))return false;
        SourceFailure.Kind kind=SourceFailure.kind(details);
        return kind==SourceFailure.Kind.AUTH;
    }

    private String accountRecoveryLabel(String url){VideoPlatform channel=VideoPlatform.detect(url);LoginPlatform platform=channel==null?null:LoginPlatform.fromId(channel.id);return platform!=null&&new AccountStore(this).hasSaved(platform)?"检查账号并重试":"登录并重试";}

    private void showLoginRecovery(String type,String id,String url){
        VideoPlatform channel=VideoPlatform.detect(url);if(channel==null||loginOpening)return;
        LoginPlatform platform=LoginPlatform.fromId(channel.id);if(platform==null)return;
        loginRecoveryId=id;loginRecoveryType=type;
        io.execute(()->{
            AccountState state=null;String error=null;
            try{AccountStore store=new AccountStore(this);store.prepare();state=new AccountState(platform,store.hasSaved(platform),store.isEnabled(platform),store.savedAt(platform),store.source(platform));}
            catch(Exception e){error=friendly(e);}
            AccountState result=state;String failure=error;
            main.post(()->{if(isDestroyed()||!id.equals(loginRecoveryId)||!type.equals(loginRecoveryType))return;
                if(failure!=null){clearLoginRecovery();showMessage("暂时无法读取账号",failure);}
                else showAccountDetails(result);
            });
        });
    }

    private void clearLoginRecovery(){loginRecoveryId=null;loginRecoveryType=null;}
    private boolean recoveryUses(LoginPlatform platform){
        String url="";
        if("inspection".equals(loginRecoveryType)){
            for(InspectionInbox.Job job:inspectionInbox.jobs)if(job.id.equals(loginRecoveryId)){url=job.url;break;}
        }else if("download".equals(loginRecoveryType)){
            JSONObject task=TaskStore.get(this,loginRecoveryId);if(task!=null)url=task.optString("url");
        }
        VideoPlatform channel=VideoPlatform.detect(url);return platform!=null&&channel!=null&&platform.id.equals(channel.id);
    }
    private void returnFromAccount(){
        if(loginRecoveryId==null){showLoginSettings();return;}
        String type=loginRecoveryType;clearLoginRecovery();showTab("download".equals(type)?"queue":"new");
    }

    private void resumeAfterLogin(String type,String id){
        if("inspection".equals(type)){
            showTab("new");
            for(InspectionInbox.Job job:new ArrayList<>(inspectionInbox.jobs))if(job.id.equals(id)){
                if(inspectionInbox.retry(job)){persistInspectionInbox();startNextInspection();renderInspection();}
                return;
            }
        }else if("download".equals(type)){
            JSONObject task=TaskStore.get(this,id);showTab("queue");
            if(task!=null&&"failed".equals(task.optString("status")))startTask(id);
        }
    }

    private boolean launchLogin(LoginPlatform platform,PocketDialog panel) {
        if(loginOpening)return false;
        if(loginRecoveryId!=null&&!recoveryUses(platform))clearLoginRecovery();
        loginOpening=true;
        try{startActivityForResult(new Intent(this,LoginActivity.class).putExtra("platform",platform.id),LOGIN_REQUEST);panel.dismiss();return true;}
        catch(ActivityNotFoundException|SecurityException e){
            loginOpening=false;showMessage("无法打开登录页","请重新安装完整版本后重试。");return false;
        }
    }

    private void openCookieImport(PocketDialog panel) {
        openCookieImport(panel,null);
    }
    private void openCookieImport(PocketDialog panel,LoginPlatform platform) {
        if(loginRecoveryId!=null&&!recoveryUses(platform))clearLoginRecovery();
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/*");
        try{cookieImportPlatform=platform==null?null:platform.id;startActivityForResult(intent,COOKIE_REQUEST);panel.dismiss();}
        catch(ActivityNotFoundException e){cookieImportPlatform=null;toast("请先安装文件管理器");}
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

    private static String attachmentLabel(Engine.Media media) {
        List<String> selected=new ArrayList<>();if(media.saveCover)selected.add("封面");if(media.saveSubtitles)selected.add("字幕");
        return selected.isEmpty()?"字幕与封面  ›":"同时保存："+String.join("、",selected)+"  ›";
    }

    private void showAttachments(Engine.Media media) {
        List<String> labels=new ArrayList<>();List<Boolean> values=new ArrayList<>();
        if(media.subtitleAvailable&&!media.subtitleLanguage.isEmpty()){labels.add("字幕（"+media.subtitleLanguage+"）");values.add(media.saveSubtitles);}
        if(!media.thumbnail.isEmpty()){labels.add("封面图片");values.add(media.saveCover);}
        if(labels.isEmpty()){showMessage("暂无附件","源站没有返回可保存的字幕或封面。");return;}
        boolean[] checked=new boolean[values.size()];for(int i=0;i<checked.length;i++)checked[i]=values.get(i);
        PocketDialog panel=new PocketDialog.Builder(this).setTitle("同时保存")
                .setMultiChoiceItems(labels.toArray(new String[0]),checked,(d,n,value)->checked[n]=value)
                .setNegativeButton("返回",null).setPositiveButton("应用",(d,w)->{
                    int index=0;if(media.subtitleAvailable&&!media.subtitleLanguage.isEmpty())media.saveSubtitles=checked[index++];
                    if(!media.thumbnail.isEmpty())media.saveCover=checked[index];persistInspectionInbox();renderInspection();
                }).show();bottom(panel);
    }

    private void showBatchSettings() {
        List<Engine.Media> items=new ArrayList<>(inspectedMedia);boolean[] chosen=new boolean[items.size()];Arrays.fill(chosen,true);
        String[] labels=new String[items.size()];for(int i=0;i<labels.length;i++)labels[i]=items.get(i).title;
        new PocketDialog.Builder(this).setTitle("选择要统一设置的内容").setMultiChoiceItems(labels,chosen,(d,n,value)->chosen[n]=value)
                .setMultiChoiceBulkActions(true).setNegativeButton("返回",null).setPositiveButton("选择画质",(d,w)->{
                    List<Engine.Media> selected=new ArrayList<>();for(int i=0;i<chosen.length;i++)if(chosen[i])selected.add(items.get(i));
                    if(selected.isEmpty()){toast("尚未选择内容");return;}
                    String[] rules={"最高可用画质","1080p 以内","720p 以内","仅音频 · M4A","仅音频 · MP3"};
                    new PocketDialog.Builder(this).setTitle("应用到 "+selected.size()+" 项").setItems(rules,(dialog,rule)->{
                        int applied=0;for(Engine.Media media:selected){
                            if(media.album||!inspectedMedia.contains(media)||(rule>=3&&!media.hasAudio))continue;
                            String target=rule==0?"best":rule==1?"cap-1080":rule==2?"cap-720":rule==3?"audio-m4a":"audio-mp3";
                            if(rule==1||rule==2){int height=rule==1?1080:720;if(media.qualities.stream().noneMatch(q->q.id.equals(target)))media.qualities.add(new Engine.Quality(target,height+"p 以内","bv*[height<="+height+"]+ba/b[height<="+height+"]"));}
                            List<Engine.Quality> choices=media.downloadChoices();for(int n=0;n<choices.size();n++)if(choices.get(n).id.equals(target)){qualitySelections.put(InspectionBatch.key(media),n);applied++;break;}
                        }persistInspectionInbox();renderInspection();toast("已设置 "+applied+" 项；图集与不适用的内容保留原设置");
                    }).setNegativeButton("返回",null).show();
                }).show();
    }

    private void showCollection() {
        String source=input==null?draft:input.getText().toString();
        try{source=CollectionLinks.normalize(source);}catch(Exception error){showMessage("展开合集",error.getMessage());return;}
        if(collectionEngine!=null){toast("正在展开合集，请稍候");return;}
        Engine collector=new Engine(this);collectionEngine=collector;java.util.concurrent.atomic.AtomicBoolean cancelled=new java.util.concurrent.atomic.AtomicBoolean();TextView status=text("正在读取条目，最多 100 项…",14,MUTED,false);status.setPadding(dp(20),dp(16),dp(20),dp(16));
        PocketDialog waiting=new PocketDialog.Builder(this).setTitle("展开合集").setView(status).setNegativeButton("取消",(d,w)->{cancelled.set(true);collector.cancelInspect();collectionEngine=null;})
                .setOnCancelListener(d->{cancelled.set(true);collector.cancelInspect();collectionEngine=null;}).show();String url=source;
        io.execute(()->{try{
            List<Engine.CollectionItem> items=collector.collection(url,message->main.post(()->{if(waiting.isShowing())status.setText(message);}),cancelled::get);
            main.post(()->{if(isDestroyed()||cancelled.get()||!waiting.isShowing())return;collectionEngine=null;waiting.dismiss();showCollectionItems(items);});
        }catch(Exception error){main.post(()->{if(isDestroyed()||cancelled.get()||!waiting.isShowing())return;collectionEngine=null;waiting.dismiss();showMessage("未能展开合集",friendly(error));});}});
    }

    private void showCollectionItems(List<Engine.CollectionItem> items) {
        if(items.isEmpty()){showMessage("没有可选条目","源站没有返回可用条目；请确认合集可访问。");return;}
        boolean[] selected=new boolean[items.size()];String[] labels=new String[items.size()];
        for(int i=0;i<labels.length;i++){Engine.CollectionItem item=items.get(i);labels[i]=item.title+(item.duration>0?" · "+duration(item.duration):"")+(item.bytes>0?" · "+MediaSize.format(item.bytes):" · 大小待解析");}
        PocketDialog[] holder=new PocketDialog[1];
        PocketDialog panel=new PocketDialog.Builder(this).setTitle("选择合集条目 · "+items.size()+" 项")
                .setMessage("仅展开前 100 项。确认后解析所选内容，选择画质后再下载。")
                .setMultiChoiceItems(labels,selected,(d,n,value)->{selected[n]=value;int count=0;for(boolean chosen:selected)if(chosen)count++;if(holder[0]!=null){holder[0].getButton(PocketDialog.BUTTON_POSITIVE).setEnabled(count>0);holder[0].getButton(PocketDialog.BUTTON_POSITIVE).setText("解析 "+count+" 项");}}).setMultiChoiceBulkActions(true)
                .setNegativeButton("返回",null).setPositiveButton("解析所选",(d,w)->{
                    List<String> urls=new ArrayList<>();for(int i=0;i<selected.length;i++)if(selected[i])urls.add(items.get(i).url);
                    if(urls.isEmpty()){toast("尚未选择条目");return;}
                    int count=inspectionInbox.add(urls);persistInspectionInbox();startNextInspection();renderInspection();toast("已加入 "+count+" 个条目进行解析");
                }).show();holder[0]=panel;panel.getButton(PocketDialog.BUTTON_POSITIVE).setEnabled(false);bottom(panel);
    }

    private void showSavedAttachments(JSONObject task) {
        org.json.JSONArray files=task.optJSONArray("attachments");if(files==null||files.length()==0)return;
        String[] labels=new String[files.length()];for(int i=0;i<labels.length;i++)labels[i]=files.optJSONObject(i).optString("name","附件");
        new PocketDialog.Builder(this).setTitle("已保存的附件").setItems(labels,(d,n)->{
            JSONObject file=files.optJSONObject(n);if(file!=null)openFile(file,false);
        }).setNegativeButton("返回",null).show();
    }

    private void showNetworkSettings() {
        LinearLayout c=column();
        c.addView(text("默认跟随手机网络",14,INK,true));
        add(c,text("使用手机已有 VPN 时通常无需填写。需要独立代理时再设置。",13,MUTED,false),8,18);
        add(c,text("代理地址（可留空）",13,INK,true),0,8);
        EditText proxy=new PocketEditText(this);proxy.setSingleLine(true);proxy.setTextSize(14);proxy.setTextColor(INK);proxy.setHintTextColor(MUTED);
        proxy.setHint("http://地址:端口 或 socks5://…");proxy.setBackground(fieldBackground());proxy.setPadding(dp(12),dp(12),dp(12),dp(12));
        proxy.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        proxy.setText(getSharedPreferences("config",0).getString("proxy",""));c.addView(proxy);
        Button saveProxy=button("保存网络设置",true);add(c,saveProxy,16,0);
        PocketDialog panel=settingsPanel("网络与代理",c);
        saveProxy.setOnClickListener(v->{
            String value=proxy.getText().toString().trim();
            if(!value.isEmpty())try{URI u=new URI(value);if(!Arrays.asList("http","https","socks5","socks5h").contains(u.getScheme())||u.getHost()==null||u.getPort()<1||u.getPort()>65535)throw new IllegalArgumentException();}
            catch(Exception e){proxy.setError("请输入带端口的有效地址，或留空");return;}
            getSharedPreferences("config",0).edit().putString("proxy",value).apply();panel.dismiss();showTab("settings");toast("已保存，下次解析和下载生效");
        });
    }

    private void showParserSettings() {
        LinearLayout c=column();
        c.addView(text("遇到链接无法解析时，可以先更新下载支持。",14,INK,true));
        add(c,text("更新后仍有问题，可以检查下载功能。",13,MUTED,false),8,18);
        Button update=button("更新下载支持",true);update.setOnClickListener(v->background(update,"正在更新…",()->engine.update(),"下载支持"));c.addView(update,new LinearLayout.LayoutParams(-1,dp(48)));
        Button diagnose=button("检查下载功能",false);diagnose.setOnClickListener(v->background(diagnose,"检查中…",()->engine.diagnose(),"下载功能检查"));add(c,diagnose,10,0);
        settingsPanel("下载支持",c);
    }

    private void showAboutSettings() {
        LinearLayout c=column();
        c.addView(text("拾流 "+BuildConfig.VERSION_NAME,16,INK,true));
        add(c,text("Android 7.0+ · 本地下载\n默认最高可用画质，无广告。",13,MUTED,false),8,16);
        add(c,text("更新会保留账号、下载记录和视频库。",13,MUTED,false),0,12);
        c.addView(text("可用画质取决于视频和账号权限。部分抖音链接可能无法解析。",13,MUTED,false));
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
        if(arrowOnly&&getResources().getConfiguration().screenWidthDp>=360&&getResources().getConfiguration().fontScale<=1.2f){r.addView(text(title,14,INK,false),new LinearLayout.LayoutParams(0,-2,1));summary.setGravity(Gravity.END);LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(dp(142),-2);vp.leftMargin=dp(8);r.addView(summary,vp);}
        else{LinearLayout labels=column();labels.addView(text(title,14,INK,true));add(labels,summary,4,0);r.addView(labels,new LinearLayout.LayoutParams(0,-2,1));}
        TextView arrow=text(actionLabel,arrowOnly?20:13,arrowOnly?MUTED:ACCENT,false);arrow.setGravity(Gravity.CENTER);r.addView(arrow,new LinearLayout.LayoutParams(dp(arrowOnly?22:48),dp(40)));
        r.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(palette.dark?0x16FFFFFF:0x14000000),null,shape(Color.WHITE,10,0)));r.setFocusable(true);r.setClickable(true);r.setContentDescription(title+"，"+subtitle+(arrowOnly?"":"，"+actionLabel));r.setOnClickListener(v->click.run());UiTheme.press(r);parent.addView(r,new LinearLayout.LayoutParams(-1,-2));return summary;
    }

    private PocketDialog settingsPanel(String title,LinearLayout content) {
        content.setPadding(0,dp(8),0,dp(8));ScrollView scroll=new ScrollView(this);scroll.addView(content);PocketDialog panel=new PocketDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("完成",null).show();bottom(panel);return panel;
    }
    private void bottom(PocketDialog panel){if(panel.getWindow()!=null)panel.getWindow().setGravity(Gravity.BOTTOM);}

    private void background(Button button,String pending,Callable<String> work,String title) {
        CharSequence original=button.getText();button.setEnabled(false);button.setText(pending);
        io.execute(()->{String message;try{message=work.call();}catch(Exception e){message=friendly(e);}String result=message;
            main.post(()->{if(isDestroyed())return;button.setEnabled(true);button.setText(original);showMessage(title,result);});});
    }

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==DIRECTORY_REQUEST){
            if(result!=RESULT_OK||data==null||data.getData()==null){showStorageSettings();return;}
            Uri selected=data.getData();int flags=data.getFlags();
            fileIo.execute(()->{String error=null;try{DownloadLocation.remember(this,selected,flags);}catch(Exception e){error=friendly(e);}String failure=error;
                main.post(()->{if(isDestroyed())return;if(failure!=null){showMessage("无法保存位置",failure);return;}showTab("settings");showStorageSettings();});
            });return;
        }
        if(request==LOGIN_REQUEST){
            loginOpening=false;
            if(loginRecoveryId!=null){String id=loginRecoveryId,type=loginRecoveryType;clearLoginRecovery();
                if(result==RESULT_OK)resumeAfterLogin(type,id);else showTab("download".equals(type)?"queue":"new");
            }else{refreshSettingsSummary();showLoginSettings();}return;
        }
        if(request!=COOKIE_REQUEST)return;
        LoginPlatform importPlatform=LoginPlatform.fromId(cookieImportPlatform);cookieImportPlatform=null;
        String recoveryId=loginRecoveryId,recoveryType=loginRecoveryType;
        if(result!=RESULT_OK||data==null||data.getData()==null){if(recoveryId!=null)returnFromAccount();return;}
        Uri selected=data.getData();
        io.execute(()->{
            String message;boolean saved=false;
            try(InputStream source=getContentResolver().openInputStream(selected)) {
                if(source==null)throw new IOException("无法读取所选文件");
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
                while((count=source.read(buffer))!=-1){if(bytes.size()+count>2*1024*1024)throw new IOException("Cookie 文件过大（最多 2 MB）");bytes.write(buffer,0,count);}
                String contents=bytes.toString(StandardCharsets.UTF_8.name());
                AccountStore accounts=new AccountStore(this);
                if(importPlatform==null)accounts.importLegacy(contents);else accounts.importForPlatform(importPlatform,contents);
                saved=true;
                message="登录信息已保存，请重新解析视频链接。";
            }catch(Exception e){message=friendly(e);}String finalMessage=message;boolean success=saved;
            main.post(()->{if(!isDestroyed()){
                if(recoveryId!=null&&recoveryId.equals(loginRecoveryId)&&recoveryType.equals(loginRecoveryType)){
                    clearLoginRecovery();if(success){resumeAfterLogin(recoveryType,recoveryId);return;}
                }
                if("settings".equals(tab))showTab("settings");showMessage("导入登录信息",finalMessage);
            }});
        });
    }

    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==MOVE_STORAGE_REQUEST){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)moveDownloadedFiles();else showMessage("没有移动文件","请允许保存文件所需的存储权限，原文件保持不变。");return;}
        if(request==STORAGE_REQUEST) {
            if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED){if(pendingRetryId!=null)startTask(pendingRetryId);else if(!pendingBatch.isEmpty()){List<Engine.Media> batch=new ArrayList<>(pendingBatch);pendingBatch.clear();enqueueBatch(batch);}}
            else showMessage("没有开始下载","Android 7–9 需要存储权限才能把视频保存到公共下载目录。链接已保留。");
            pendingBatch.clear();pendingRetryId=null;
        }
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);readShare(intent);}
    private void readShare(Intent intent) {
        if(intent!=null&&Intent.ACTION_SEND.equals(intent.getAction())) {
            String value=intent.getStringExtra(Intent.EXTRA_TEXT);
            if(value!=null&&!value.isEmpty()){try{queueInspections(LinkBatch.parse(value).urls);showTab("new");}catch(IllegalArgumentException invalid){toast(invalid.getMessage());}intent.setAction(null);}
        }
    }
    private final AppUpdates.Listener appUpdateListener=state->{
        boolean ready=state.phase==AppUpdates.Phase.READY;
        if((!ready&&state.phase!=AppUpdates.Phase.AVAILABLE)||state.offer==null||isFinishing()||isDestroyed())return;
        AppUpdates updates=AppUpdates.get(this);
        if(ready){if(!updates.shouldNotifyReady(state.offer.code))return;updates.notifiedReady(state.offer.code);}
        else{if(!updates.shouldNotify(state.offer.code))return;updates.notified(state.offer.code);}
        new PocketDialog.Builder(this).setTitle(ready?"拾流更新已下载":"拾流有新版本 "+state.offer.version)
                .setMessage(ready?"打开更新页即可继续安装，现有数据会保留。":"安装包 "+UpdateActivity.size(state.offer.size)+"。可查看更新内容后再下载。")
                .setNegativeButton("稍后",null).setPositiveButton("查看更新",(d,w)->startActivity(new Intent(this,UpdateActivity.class))).show();
    };
    private final Runnable appUpdateCheck=()->AppUpdates.get(this).checkIfDue();
    @Override protected void onResume(){super.onResume();foreground=true;clipboardCheckPending=true;main.post(checkClipboard);AppUpdates.get(this).listen(appUpdateListener);main.postDelayed(appUpdateCheck,1500);main.removeCallbacks(poll);main.post(poll);if(libraryView!=null)libraryView.refresh();}
    @Override protected void onPause(){foreground=false;main.removeCallbacks(checkClipboard);hideClipboardOffer();AppUpdates.get(this).unlisten(appUpdateListener);main.removeCallbacks(appUpdateCheck);if(openSwipeRow!=null)openSwipeRow.close(false);queueTouchActive=false;super.onPause();main.removeCallbacks(poll);if(input!=null)draft=input.getText().toString();getPreferences(0).edit().putString("draft",draft).apply();persistInspectionInbox();}
    @Override protected void onSaveInstanceState(Bundle state){
        state.putString("cookie_import_platform",cookieImportPlatform);
        state.putString("login_recovery_id",loginRecoveryId);state.putString("login_recovery_type",loginRecoveryType);
        super.onSaveInstanceState(state);state.putBoolean("link_editor_expanded",linkEditorExpanded);state.putString("inspection_message",lastInspectionMessage);state.putString("inspection_details",lastInspectionDetails);state.putString("draft",input!=null?input.getText().toString():draft);state.putString("tab",tab);state.putString("queue_filter",queueFilter);state.putString("quality_selection",new JSONObject(qualitySelections).toString());
        if(libraryView!=null)libraryState=libraryView.saveUiState();if(libraryState!=null)state.putBundle("library_state",libraryState);
        try{org.json.JSONArray media=new org.json.JSONArray();for(Engine.Media m:inspectedMedia)media.put(m.toJson());state.putString("inspection_media",media.toString());}catch(Exception ignored){}
    }
    @Override protected void onDestroy(){super.onDestroy();foreground=false;fileMoveCancelled.set(true);fileIo.shutdownNow();if(clipboardReview!=null)clipboardReview.dismiss();main.removeCallbacks(checkClipboard);main.removeCallbacks(previewLinks);main.removeCallbacks(poll);main.removeCallbacks(commitRemovals);if(!isChangingConfigurations())commitPendingRemovals(false);pendingRemoval.clear();if(libraryView!=null)libraryView.release();if(artwork!=null)artwork.release();++parseGeneration;engine.cancelInspect();if(collectionEngine!=null)collectionEngine.cancelInspect();io.shutdownNow();}

    private static boolean isActive(String s){return Arrays.asList("queued","downloading","merging","saving","cancelling","pausing").contains(s);}
    private static String statusName(String s){switch(s){case"queued":return"等待下载";case"downloading":return"正在下载";case"merging":return"正在合并音视频";case"saving":return"正在保存到手机";case"completed":return"已保存";case"cancelling":return"正在取消";case"cancelled":return"已取消";case"pausing":return"正在暂停";case"paused":return"已暂停，断点保留";case"interrupted":return"上次下载中断，可重试";default:return"下载失败";}}
    private String friendly(Exception e){String m=e.getMessage();return e instanceof IllegalArgumentException&&m!=null&&!m.trim().isEmpty()?Engine.cleanLog(m):CoreRules.userMessage(e);}
    private void action(LinearLayout row,String label,Runnable click){Button b=quietButton(label);b.setMinWidth(0);b.setPadding(dp(10),0,dp(10),0);b.setTextSize(12);b.setOnClickListener(v->click.run());row.addView(b,new LinearLayout.LayoutParams(-2,dp(44)));}
    private void divider(LinearLayout c){View v=new View(this);v.setBackgroundColor(LINE);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(1));p.topMargin=dp(23);p.bottomMargin=dp(23);c.addView(v,p);}
    private void showMessage(String title,String message){TextView t=text(message,14,INK,false);t.setTextIsSelectable(true);t.setPadding(dp(22),dp(15),dp(22),dp(12));ScrollView s=new ScrollView(this);s.addView(t);new PocketDialog.Builder(this).setTitle(title).setView(s).setPositiveButton("知道了",null).show();}
    private void toast(String s){PocketNotice.show(this,s);}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(3),1);if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    private Button button(String title,boolean primary){return PocketButton.of(this,title,primary?PocketButton.Role.PRIMARY:PocketButton.Role.SECONDARY);}
    private Button quietButton(String label){return PocketButton.of(this,label,PocketButton.Role.QUIET);}
    private StateListDrawable fieldBackground(){StateListDrawable states=new StateListDrawable();GradientDrawable focused=shape(SURFACE,10,ACCENT);focused.setStroke(dp(2),ACCENT);states.addState(new int[]{android.R.attr.state_focused},focused);states.addState(new int[]{},shape(palette.control,10,0));return states;}
    private GradientDrawable shape(int color,int radius,int border){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(border!=0)d.setStroke(dp(1),border);return d;}
    private void add(LinearLayout parent,View child,int top,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);p.bottomMargin=dp(bottom);parent.addView(child,p);}
    private static String mediaSummary(Engine.Media media,int selection){
        Engine.Quality choice=media.downloadChoices().get(Math.max(0,Math.min(selection,media.downloadChoices().size()-1)));
        if(media.album)return media.imageCount+" 张图片 · 打包为 ZIP，解压后查看";
        if(choice.audioOnly())return choice.details;
        if(selection>0)return (choice.details.isEmpty()?choice.label:choice.details)+" · 大小待下载时确定";
        String value=media.width>0&&media.height>0?media.width+" × "+media.height:"";
        if(media.filesize>0)value+=(value.isEmpty()?"":" · ")+(media.filesizeEstimated?"预计 ":"")+MediaSize.format(media.filesize);
        return value.isEmpty()?"实际大小在开始下载后确定":value;
    }
    private static String duration(long seconds){return String.format(Locale.ROOT,"%d:%02d",Math.max(0,seconds)/60,Math.max(0,seconds)%60);}
    private ImageView cover(String source,String key,int width,int height){ImageView image=new ImageView(this);image.setBackground(shape(palette.control,10,0));image.setClipToOutline(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setContentDescription("视频封面");artwork.load(image,source,key);return image;}
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
