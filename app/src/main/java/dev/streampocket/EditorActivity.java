package dev.streampocket;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongConsumer;
import org.json.JSONObject;

/** Local range selection and persistent ordered clip drafts. Originals are read only. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class EditorActivity extends Activity {
    private int WHITE,INK,MUTED,LINE,BG,ACCENT,CONTROL;
    private static final int PROJECTS=0, TRIM=1, PROJECT=2, PICKER=3;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private LibraryStore store;
    private ThumbnailRepository covers;
    private int screen=PROJECTS, generation, pickerPage;
    private long projectId, videoId, durationMs, positionMs, requestedStart, requestedEnd=-1;
    private String startDraft="", endDraft="", pickerQuery="";
    private boolean resumed, destroyed, sourceLoaded, previewClaim, rangePlaying, busy;
    private long previewEnd;
    private JSONObject source, project;
    private List<JSONObject> clips=new ArrayList<>();
    private ExoPlayer player;
    private PlayerView playerView;
    private LinearLayout root, body, pickerItems;
    private EditText startInput, endInput;
    private TextView feedback, positionLabel, exportDetail;
    private TextView sourceMeta;
    private TextView rangeSummary;
    private ClipRangeView rangeView;
    private LinearLayout preciseFields;
    private boolean preciseOpen,syncingRange;
    private long lastRangeSeek;
    private ProgressBar exportProgress;
    private Button addButton, exportButton, cancelButton, outputButton;
    private Button addClipButton;
    private final List<Button> clipMenus=new ArrayList<>();
    private final List<ImageView> boundCovers=new ArrayList<>();
    private final Runnable searchDebounce=()->loadPicker(false);
    private final Runnable projectPoll=new Runnable() {
        @Override public void run() {
            if (!resumed || destroyed || screen!=PROJECT || projectId<1) return;
            long id=projectId; int token=generation;
            io.execute(()->{
                try {
                    JSONObject latest=store.project(id);
                    main.post(()->{
                        if (!current(token,PROJECT)) return;
                        if (latest!=null) { project=latest; updateExportStatus(); }
                        if (resumed) main.postDelayed(this,800);
                    });
                } catch (Exception failure) { error(token,"状态读取失败，请返回后重新打开草稿。"); }
            });
        }
    };
    private final Runnable previewTick=new Runnable() {
        @Override public void run() {
            if (!resumed || player==null || screen!=TRIM) return;
            positionMs=Math.max(0,player.getCurrentPosition());
            if (positionLabel!=null) positionLabel.setText("当前位置  "+time(positionMs));
            if (rangePlaying && positionMs>=previewEnd) { player.pause(); rangePlaying=false; }
            main.postDelayed(this,150);
        }
    };

    @Override public void onCreate(Bundle state) {
        UiTheme.apply(this); super.onCreate(state); UiTheme.systemBars(this);
        UiTheme.Palette palette=UiTheme.get(this);WHITE=palette.surface;INK=palette.ink;MUTED=palette.muted;LINE=palette.line;BG=palette.bg;ACCENT=palette.accent;CONTROL=palette.control;
        store=new LibraryStore(getApplicationContext()); covers=new ThumbnailRepository(this);
        if (state!=null) {
            screen=state.getInt("screen",PROJECTS); projectId=state.getLong("project_id"); videoId=state.getLong("video_id");
            positionMs=state.getLong("position_ms"); requestedStart=state.getLong("start_ms"); requestedEnd=state.getLong("end_ms",-1);
            startDraft=state.getString("start_draft",""); endDraft=state.getString("end_draft","");
            preciseOpen=state.getBoolean("precise_open",false);
        } else {
            projectId=getIntent().getLongExtra("project_id",0); videoId=getIntent().getLongExtra("video_id",0);
            positionMs=Math.max(0,getIntent().getLongExtra("position_ms",0));
            requestedStart=Math.max(0,getIntent().getLongExtra("start_ms",positionMs)); requestedEnd=getIntent().getLongExtra("end_ms",-1);
            screen=videoId>0?TRIM:projectId>0?PROJECT:PROJECTS;
        }
        if (screen==TRIM && videoId>0) showTrim(videoId,true);
        else if (screen==PROJECT && projectId>0) showProject(projectId);
        else if (screen==PICKER) showPicker(); else showProjects();
        if (Build.VERSION.SDK_INT>=33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::back);
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        long id=intent.getLongExtra("project_id",0); if (id>0) showProject(id);
    }
    private void page(String heading) {
        clearCovers();
        root=column(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view,insets)->{
            view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()); return insets;
        });
        LinearLayout header=row(); header.setPadding(dp(8),dp(4),dp(20),dp(4));
        Button back=quietButton("返回"); back.setOnClickListener(v->back());
        header.addView(back,new LinearLayout.LayoutParams(dp(64),dp(48)));
        header.addView(text(heading,20,INK,true),new LinearLayout.LayoutParams(0,-2,1)); root.addView(header);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);
        body=column(); body.setPadding(dp(20),dp(10),dp(20),dp(24)); scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1)); setContentView(root);root.requestApplyInsets();
        startInput=null; endInput=null; feedback=null; playerView=null;rangeView=null;rangeSummary=null;preciseFields=null;sourceMeta=null;
    }
    private void showProjects() {
        rememberRange(); releasePreview(); screen=PROJECTS; int token=++generation;
        main.removeCallbacks(projectPoll); page("剪辑草稿");
        add(body,text("将精彩片段拼成一个新视频",13,MUTED,false),0);
        Button create=button("新建剪辑",true); create.setOnClickListener(v->createProject(this::showProject)); add(body,create,16);
        TextView loading=text("正在读取草稿…",13,MUTED,false); add(body,loading,20);
        io.execute(()->{
            try {
                store.recoverInterruptedExports(); List<JSONObject> projects=store.projects();
                main.post(()->{
                    if (!current(token,PROJECTS)) return; body.removeView(loading);
                    if (projects.isEmpty()) { add(body,text("还没有草稿\n先新建剪辑，再从视频库挑选片段。",15,MUTED,false),28); return; }
                    for (JSONObject item:projects) {
                        String detail=item.optString("exportDetail");
                        Button entry=button(item.optString("title","我的剪辑")+"\n"+(detail.isEmpty()?"本机草稿":detail),false);
                        entry.setGravity(Gravity.START|Gravity.CENTER_VERTICAL); entry.setMaxLines(3); entry.setBackgroundColor(BG);
                        entry.setOnClickListener(v->showProject(item.optLong("id"))); add(body,entry,12); divider(body);
                    }
                });
            } catch (Exception failure) { error(token,"草稿读取失败，请返回后重试。"); }
        });
    }
    private void showTrim(long id,boolean keepRange) {
        rememberRange(); releasePreview(); screen=TRIM; videoId=id; sourceLoaded=false; source=null;
        if (!keepRange) { requestedStart=0; requestedEnd=-1; positionMs=0; startDraft=""; endDraft="";preciseOpen=false; }
        int token=++generation; main.removeCallbacks(projectPoll); page("截取片段");
        feedback=text("正在读取本地视频…",14,MUTED,false); body.addView(feedback);
        io.execute(()->{
            try {
                JSONObject item=store.video(id);
                if (item==null || item.optBoolean("missing")) throw new IllegalArgumentException("原视频不可用，文件可能已被移走。已有草稿会保留。");
                long duration=item.optLong("durationMs");
                if (duration<=0) {
                    MediaMetadataRetriever probe=new MediaMetadataRetriever();
                    try { probe.setDataSource(this,Uri.parse(item.optString("uri"))); duration=Long.parseLong(probe.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)); store.updateMetadata(id,duration,0,0); }
                    finally { probe.release(); }
                }
                if (duration<=0) throw new IllegalArgumentException("无法读取视频时长，请先确认原片能正常播放。");
                final long actualDuration=duration;
                main.post(()->{
                    if (!current(token,TRIM)) return;
                    source=item; durationMs=actualDuration; sourceLoaded=true;
                    requestedStart=Math.min(Math.max(0,requestedStart),Math.max(0,durationMs-1));
                    if (requestedEnd<0) requestedEnd=Math.min(durationMs,requestedStart+15000);
                    if (startDraft.isEmpty()) startDraft=time(requestedStart); if (endDraft.isEmpty()) endDraft=time(requestedEnd);
                    renderTrim(); ensurePreview();
                });
            } catch (Exception failure) { error(token,message(failure,"无法读取原视频，请返回后重试。")); }
        });
    }
    private void renderTrim() {
        body.removeAllViews();
        playerView=new PlayerView(this); playerView.setBackgroundColor(0xFF0A0C0F); playerView.setUseController(true);
        playerView.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setShowNextButton(false); playerView.setShowPreviousButton(false);
        int viewport=getResources().getConfiguration().screenHeightDp;
        int previewHeight=Math.max(180,Math.min(340,Math.round(viewport*.37f)));
        add(body,playerView,0,new LinearLayout.LayoutParams(-1,dp(previewHeight)));
        TextView name=text(source.optString("title","本地视频"),15,INK,true); name.setMaxLines(1);name.setEllipsize(android.text.TextUtils.TruncateAt.END);add(body,name,12);
        name.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("原视频").setMessage(source.optString("title","本地视频")).setPositiveButton("返回",null).show());
        long bytes=source.optLong("bytes",-1);
        sourceMeta=text("原片 "+time(durationMs)+(bytes>0?" · "+MediaSize.format(bytes):""),12,MUTED,false);add(body,sourceMeta,3);
        LinearLayout cursor=row();positionLabel=text("当前位置  "+time(positionMs),12,MUTED,false);cursor.addView(positionLabel,new LinearLayout.LayoutParams(0,-2,1));
        Button setStart=quietButton("设为起点"),setEnd=quietButton("设为终点");
        setStart.setContentDescription("将当前播放位置设为片段起点");setEnd.setContentDescription("将当前播放位置设为片段终点");
        setStart.setOnClickListener(v->{if(player!=null)startInput.setText(time(player.getCurrentPosition()));else toast("视频准备好后可定位起点");});
        setEnd.setOnClickListener(v->{if(player!=null)endInput.setText(time(player.getCurrentPosition()));else toast("视频准备好后可定位终点");});
        cursor.addView(setStart,new LinearLayout.LayoutParams(dp(80),dp(48)));cursor.addView(setEnd,new LinearLayout.LayoutParams(dp(80),dp(48)));add(body,cursor,4);
        LinearLayout heading=row();rangeSummary=text("",13,ACCENT,true);rangeSummary.setMaxLines(2);heading.addView(rangeSummary,new LinearLayout.LayoutParams(0,-2,1));
        Button precise=quietButton("精确时间");precise.setContentDescription("展开或收起毫秒起止时间输入");heading.addView(precise,new LinearLayout.LayoutParams(dp(84),dp(48)));add(body,heading,0);
        rangeView=new ClipRangeView(this);add(body,rangeView,0,new LinearLayout.LayoutParams(-1,dp(96)));
        preciseFields=column();LinearLayout fields=row(),start=column(),end=column();start.addView(text("起点",12,MUTED,false));end.addView(text("终点",12,MUTED,false));
        startInput=input("00:00.000");endInput=input("00:15.000");startInput.setContentDescription("片段起点，秒数或分秒，最多三位小数");endInput.setContentDescription("片段终点，秒数或分秒，最多三位小数");
        startInput.setText(startDraft);endInput.setText(endDraft);add(start,startInput,6);add(end,endInput,6);
        fields.addView(start,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams second=new LinearLayout.LayoutParams(0,-2,1);second.leftMargin=dp(12);fields.addView(end,second);preciseFields.addView(fields);
        add(preciseFields,text("秒数或 分:秒，可精确到毫秒",12,MUTED,false),6);add(body,preciseFields,0);preciseFields.setVisibility(preciseOpen?View.VISIBLE:View.GONE);
        precise.setSelected(preciseOpen);precise.setOnClickListener(v->{preciseOpen=!preciseOpen;precise.setSelected(preciseOpen);preciseFields.setVisibility(preciseOpen?View.VISIBLE:View.GONE);if(preciseOpen)UiTheme.enter(preciseFields);});
        TextWatcher watcher=new TextWatcher(){public void beforeTextChanged(CharSequence value,int st,int count,int after){}public void onTextChanged(CharSequence value,int st,int before,int count){if(!syncingRange&&player!=null){rangePlaying=false;player.pause();}syncRangeFromFields();}public void afterTextChanged(Editable value){}};
        startInput.addTextChangedListener(watcher);endInput.addTextChangedListener(watcher);
        rangeView.setListener((from,to,handle,tracking)->{
            syncingRange=true;startInput.setText(time(from));endInput.setText(time(to));syncingRange=false;
            startDraft=startInput.getText().toString();endDraft=endInput.getText().toString();requestedStart=from;requestedEnd=to;updateRangeSummary(from,to);
            long target=handle==ClipRangeView.START?from:to;positionMs=target;
            if(positionLabel!=null)positionLabel.setText("预览位置  "+time(target));
            long now=SystemClock.uptimeMillis();if(player!=null&&(!tracking||now-lastRangeSeek>=80)){lastRangeSeek=now;rangePlaying=false;player.pause();player.seekTo(target);}
        });
        long[] initial;try{initial=validatedRange(startDraft,endDraft,durationMs);}catch(IllegalArgumentException invalid){initial=new long[]{Math.max(0,Math.min(requestedStart,durationMs-1)),Math.max(1,Math.min(requestedEnd,durationMs))};}
        rangeView.setRange(durationMs,initial[0],initial[1]);updateRangeSummary(rangeView.getStartMs(),rangeView.getEndMs());
        feedback=text("片段 "+time(rangeView.getEndMs()-rangeView.getStartMs())+" · 原片保持不变",12,MUTED,false);add(body,feedback,0);
        LinearLayout actions=row();Button preview=button("预览所选范围",false);preview.setOnClickListener(v->previewRange());actions.addView(preview,new LinearLayout.LayoutParams(0,dp(48),1));
        addButton=button(projectId>0?"加入当前草稿":"加入剪辑草稿",true);addButton.setOnClickListener(v->addRange());LinearLayout.LayoutParams addParams=new LinearLayout.LayoutParams(0,dp(48),1);addParams.leftMargin=dp(12);actions.addView(addButton,addParams);add(body,actions,14);
    }
    private void syncRangeFromFields() {
        if(syncingRange||rangeView==null||startInput==null||endInput==null)return;
        try{long[] range=validatedRange(startInput.getText().toString(),endInput.getText().toString(),durationMs);rangeView.setRange(durationMs,range[0],range[1]);requestedStart=range[0];requestedEnd=range[1];updateRangeSummary(range[0],range[1]);}
        catch(IllegalArgumentException invalid){if(rangeSummary!=null)rangeSummary.setText("请检查起止时间");}
    }
    private void updateRangeSummary(long from,long to) { if(rangeSummary!=null)rangeSummary.setText(time(from)+" — "+time(to));if(feedback!=null&&!rangePlaying)feedback.setText("片段 "+time(to-from)+" · 原片保持不变"); }
    private void previewRange() {
        long[] range=readRange(); if (range==null) return;
        if (player==null) { feedback.setText("视频尚未准备好，请稍后再预览。"); return; }
        previewEnd=range[1]; rangePlaying=true; player.seekTo(range[0]); player.play(); feedback.setText("正在预览 "+time(range[0])+" — "+time(range[1]));
    }
    private long[] readRange() {
        try { return validatedRange(startInput.getText().toString(),endInput.getText().toString(),durationMs); }
        catch (IllegalArgumentException invalid) { feedback.setText(invalid.getMessage()); return null; }
    }
    private void addRange() {
        if (busy) return; long[] range=readRange(); if (range==null) return; rememberRange();
        if (projectId>0) saveRange(projectId,range); else chooseProject(id->saveRange(id,range));
    }
    private void saveRange(long target,long[] range) {
        busy=true; if (addButton!=null) addButton.setEnabled(false); final long sourceId=videoId; int token=generation;
        io.execute(()->{
            try {
                if (ExportService.isRunning(target)) throw new IllegalArgumentException("这个草稿正在导出，请结束后再添加片段。");
                store.addClip(target,sourceId,range[0],range[1]);
                store.updateProjectExport(target,"draft",-1,"草稿已更新，可导出新成片。",null);
                main.post(()->{ busy=false; if (!destroyed && token==generation) { projectId=target; showProject(target); } });
            } catch (Exception failure) { main.post(()->{ busy=false; if (!destroyed && token==generation) { addButton.setEnabled(true); feedback.setText(message(failure,"片段未能保存，请重试。")); } }); }
        });
    }
    private void chooseProject(LongConsumer selected) {
        int token=generation;
        io.execute(()->{
            try {
                List<JSONObject> drafts=store.projects();
                main.post(()->{
                    if (destroyed || token!=generation) return; String[] choices=new String[drafts.size()+1]; choices[0]="新建剪辑草稿";
                    for (int i=0;i<drafts.size();i++) choices[i+1]=drafts.get(i).optString("title","我的剪辑");
                    new AlertDialog.Builder(this).setTitle("加入哪个剪辑？").setItems(choices,(dialog,which)->{
                        if (which==0) createProject(selected); else selected.accept(drafts.get(which-1).optLong("id"));
                    }).setNegativeButton("取消",null).show();
                });
            } catch (Exception failure) { error(token,"无法读取草稿，请重试。"); }
        });
    }
    private void createProject(LongConsumer selected) {
        EditText title=input("给剪辑起个名字"); title.setText("我的剪辑"); title.setSelectAllOnFocus(true);
        LinearLayout wrap=column(); wrap.setPadding(dp(24),dp(8),dp(24),0); wrap.addView(title);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("新建剪辑").setView(wrap).setNegativeButton("取消",null).setPositiveButton("创建",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String name=title.getText().toString().trim(); if (name.isEmpty()) { title.setError("请输入名称"); return; }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false); int token=generation;
            io.execute(()->{
                try { long id=store.createProject(name); main.post(()->{ if (!destroyed) { dialog.dismiss(); if (token==generation) selected.accept(id); } }); }
                catch (Exception failure) { main.post(()->{ if (!destroyed) { title.setError("创建失败，请重试"); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); } }); }
            });
        })); dialog.show();
    }
    private void showProject(long id) {
        rememberRange(); releasePreview(); screen=PROJECT; projectId=id; int token=++generation;
        main.removeCallbacks(projectPoll); page("剪辑草稿"); feedback=text("正在读取片段…",14,MUTED,false); body.addView(feedback);
        io.execute(()->{
            try {
                store.recoverInterruptedExports(); JSONObject item=store.project(id); List<JSONObject> parts=store.clips(id);
                if (item==null) throw new IllegalArgumentException("这个草稿已不存在。");
                main.post(()->{ if (current(token,PROJECT)) { project=item; clips=parts; renderProject(); schedulePoll(); } });
            } catch (Exception failure) { error(token,message(failure,"草稿读取失败，请重试。")); }
        });
    }
    private void renderProject() {
        clearCovers();body.removeAllViews();clipMenus.clear();body.addView(text(project.optString("title","我的剪辑"),20,INK,true));
        long total=0;for(JSONObject clip:clips)total+=clip.optLong("durationMs");add(body,text(clips.size()+" 个片段 · 合计 "+time(total),13,MUTED,false),5);
        boolean running=ExportService.isRunning(projectId);
        if(clips.isEmpty())add(body,text("挑选原视频，留下精彩的那一段",14,MUTED,false),24);
        for(int i=0;i<clips.size();i++){
            JSONObject clip=clips.get(i);int index=i;LinearLayout line=row(),labels=column();line.setPadding(0,dp(4),0,dp(4));
            ImageView cover=cover(clip.optLong("videoId"),clip.optString("uri"),"第 "+(i+1)+" 个片段封面");LinearLayout.LayoutParams poster=new LinearLayout.LayoutParams(dp(76),dp(58));poster.rightMargin=dp(12);line.addView(cover,poster);
            TextView name=text((i+1)+"  "+clip.optString("title","视频片段"),14,INK,true);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);labels.addView(name);
            add(labels,text(time(clip.optLong("startMs"))+" — "+time(clip.optLong("endMs")),12,MUTED,false),4);line.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            Button more=quietButton("⋯");more.setTextSize(22);more.setContentDescription("管理第 "+(i+1)+" 个片段");more.setEnabled(!running);clipMenus.add(more);
            more.setOnClickListener(v->clipMenu(more,clip,index));line.addView(more,new LinearLayout.LayoutParams(dp(48),dp(52)));add(body,line,14);divider(body);
        }
        addClipButton=button("添加视频片段",false);addClipButton.setEnabled(!running);addClipButton.setOnClickListener(v->showPicker());add(body,addClipButton,16);
        LinearLayout preset=row();preset.addView(text("MP4 · 1080p · 原片保留",12,MUTED,false),new LinearLayout.LayoutParams(0,-2,1));
        Button settings=quietButton("导出设置");settings.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("导出设置").setMessage(ExportService.PRESET_LABEL+"\n最长边不超过1920像素，不放大小视频；不同画幅完整显示并留边。另存新视频，原片保留。").setPositiveButton("知道了",null).show());preset.addView(settings,new LinearLayout.LayoutParams(dp(88),dp(48)));add(body,preset,10);
        exportDetail=text("",13,MUTED,false);add(body,exportDetail,6);
        exportProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);exportProgress.setMax(100);exportProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));exportProgress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(LINE));add(body,exportProgress,10,new LinearLayout.LayoutParams(-1,dp(4)));
        exportButton=button("导出新视频",true);exportButton.setOnClickListener(v->startExport());add(body,exportButton,14);
        cancelButton=button("取消导出",false);cancelButton.setOnClickListener(v->cancelExport());add(body,cancelButton,10);
        outputButton=button("播放成片",false);outputButton.setOnClickListener(v->openOutput());add(body,outputButton,10);
        feedback=text("",12,MUTED,false);add(body,feedback,10);updateExportStatus();
    }
    private void clipMenu(View anchor,JSONObject clip,int index) {
        if (ExportService.isRunning(projectId)) return; PopupMenu menu=new PopupMenu(this,anchor);
        if (index>0) menu.getMenu().add("上移"); if (index<clips.size()-1) menu.getMenu().add("下移"); menu.getMenu().add("移除片段");
        menu.setOnMenuItemClickListener(item->{
            String action=item.getTitle().toString(); long id=projectId,clipId=clip.optLong("id"); int token=generation;
            io.execute(()->{
                try {
                    if (ExportService.isRunning(id)) throw new IllegalArgumentException("草稿正在导出，暂时无法调整。");
                    if ("移除片段".equals(action)) store.removeClip(clipId); else store.moveClip(id,clipId,"上移".equals(action)?-1:1);
                    store.updateProjectExport(id,"draft",-1,"草稿已更新，可导出新成片。",null);
                    main.post(()->{ if (!destroyed && token==generation) showProject(id); });
                } catch (Exception failure) { error(token,message(failure,"片段未能更新，请重试。")); }
            }); return true;
        }); menu.show();
    }
    private void showPicker() {
        rememberRange(); releasePreview(); screen=PICKER; ++generation; main.removeCallbacks(projectPoll); page("选择视频素材");
        EditText search=input("搜索视频标题"); body.addView(search); pickerItems=column(); add(body,pickerItems,16); pickerPage=0; pickerQuery="";
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) { pickerQuery=s.toString(); main.removeCallbacks(searchDebounce); main.postDelayed(searchDebounce,250); }
            public void afterTextChanged(Editable value) {}
        }); loadPicker(false);
    }
    private void loadPicker(boolean more) {
        if (destroyed || screen!=PICKER) return; int token=++generation;
        if (!more) { pickerPage=0;clearCovers(); pickerItems.removeAllViews(); } int offset=pickerPage*40; String query=pickerQuery;
        io.execute(()->{
            try {
                List<JSONObject> videos=store.listVideos(query,"",0,40,offset);
                main.post(()->{
                    if (!current(token,PICKER)) return;
                    if (!more && videos.isEmpty()) { pickerItems.addView(text("没有找到视频。先完成一个下载，再来挑选素材。",15,MUTED,false)); return; }
                    for(JSONObject item:videos){
                        LinearLayout entry=row();entry.setPadding(0,dp(8),0,dp(8));ImageView cover=cover(item.optLong("id"),item.optString("uri"),"视频素材封面");LinearLayout.LayoutParams poster=new LinearLayout.LayoutParams(dp(82),dp(62));poster.rightMargin=dp(12);entry.addView(cover,poster);
                        LinearLayout labels=column();TextView title=text(item.optString("title","本地视频"),14,INK,true);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);labels.addView(title);
                        long size=item.optLong("bytes",-1);add(labels,text(item.optBoolean("missing")?"原文件不可用":time(item.optLong("durationMs"))+(size>0?" · "+MediaSize.format(size):""),12,MUTED,false),4);entry.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
                        entry.setEnabled(!item.optBoolean("missing"));entry.setFocusable(true);entry.setContentDescription("选择素材："+item.optString("title","本地视频"));entry.setOnClickListener(v->{if(!item.optBoolean("missing"))showTrim(item.optLong("id"),false);});UiTheme.press(entry);add(pickerItems,entry,4);divider(pickerItems);
                    }
                    if (videos.size()==40) { Button next=button("加载更多视频",false); next.setOnClickListener(v->{ pickerItems.removeView(next); pickerPage++; loadPicker(true); }); add(pickerItems,next,16); }
                });
            } catch (Exception failure) { error(token,"视频库读取失败，请返回后重试。"); }
        });
    }
    private void startExport() {
        if (clips.isEmpty() || ExportService.isRunning(projectId)) return; releasePreview();
        try {
            Intent request=new Intent(this,ExportService.class).setAction(ExportService.ENQUEUE).putExtra(ExportService.PROJECT_ID,projectId);
            if (Build.VERSION.SDK_INT>=26) startForegroundService(request); else startService(request);
            feedback.setText("已请求导出。请查看上方真实处理状态。"); exportButton.setEnabled(false); schedulePoll();
        } catch (RuntimeException failure) { feedback.setText("暂时无法启动导出，请保持拾流在前台后重试。"); exportButton.setEnabled(true); }
    }
    private void cancelExport() {
        try {
            startService(new Intent(this,ExportService.class).setAction(ExportService.CANCEL).putExtra(ExportService.PROJECT_ID,projectId));
            feedback.setText("正在请求取消；原片和草稿会保留。");
        } catch (RuntimeException failure) { feedback.setText("取消请求未能送达，请返回后查看状态。"); }
    }
    private void updateExportStatus() {
        if (exportDetail==null || project==null) return;
        boolean running=ExportService.isRunning(projectId); String state=project.optString("exportState"),detail=project.optString("exportDetail");
        if (detail.isEmpty()) detail=clips.isEmpty()?"先添加片段，再导出。":"草稿已保存在本机，随时可以继续编辑。"; exportDetail.setText(detail);
        int progress=project.optInt("exportProgress",-1); exportProgress.setVisibility(running?View.VISIBLE:View.GONE); exportProgress.setIndeterminate(progress<0); if (progress>=0) exportProgress.setProgress(progress);
        exportButton.setEnabled(!running && !clips.isEmpty()); exportButton.setText("failed".equals(state)||"interrupted".equals(state)||"cancelled".equals(state)?"重新导出":"导出新视频");
        addClipButton.setEnabled(!running); for (Button menu:clipMenus) menu.setEnabled(!running);
        cancelButton.setVisibility(running?View.VISIBLE:View.GONE); outputButton.setVisibility(!project.optString("outputUri").isEmpty()?View.VISIBLE:View.GONE);
        outputButton.setText("completed".equals(state)?"播放成片":"播放上次成片");
    }
    private void openOutput() {
        if (project==null || project.optString("outputUri").isEmpty()) return;
        JSONObject output=project; int token=generation; long duration=0; for (JSONObject clip:clips) duration+=clip.optLong("durationMs"); final long total=duration;
        io.execute(()->{
            try {
                long id=store.addExportedVideo(output.optString("title","我的剪辑"),output.optString("outputUri"),"video/mp4",0,total);
                main.post(()->{ if (!destroyed && token==generation) startActivity(new Intent(this,PlayerActivity.class).putExtra("video_id",id)); });
            } catch (Exception failure) { error(token,"成片暂时无法打开，请在系统下载目录查看。"); }
        });
    }
    private void schedulePoll() { main.removeCallbacks(projectPoll); if (resumed && screen==PROJECT) main.postDelayed(projectPoll,300); }
    private void ensurePreview() {
        if (!resumed || destroyed || screen!=TRIM || !sourceLoaded || player!=null || playerView==null) return;
        try {
            player=new ExoPlayer.Builder(this,new DefaultRenderersFactory(this).setEnableDecoderFallback(true)).build();
            MediaWorkload.playerStarted(); previewClaim=true; ExoPlayer current=player;
            current.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (player!=current) return; if (state==Player.STATE_READY) onMeasuredDuration(current.getDuration());
                }
                @Override public void onPlayerError(PlaybackException failure) {
                    if (player==current) { releasePreview(); if (feedback!=null) feedback.setText("预览未能播放。请确认原片可播放，或返回后重试；已保存的草稿仍保留。"); }
                }
            });
            playerView.setPlayer(current); current.setMediaItem(MediaItem.fromUri(source.optString("uri"))); current.seekTo(Math.max(0,positionMs));
            current.prepare(); current.setPlayWhenReady(false); main.post(previewTick);
        } catch (RuntimeException failure) { releasePreview(); if (feedback!=null) feedback.setText("预览初始化失败，请返回后重试。"); }
    }
    void onMeasuredDuration(long actual) {
        if (actual<=0 || actual==C.TIME_UNSET) return;
        durationMs=actual;
        if(rangeView!=null){rangeView.setRange(actual,rangeView.getStartMs(),rangeView.getEndMs());syncRangeFromFields();}
        if(sourceMeta!=null){long bytes=source==null?-1:source.optLong("bytes",-1);sourceMeta.setText("原片 "+time(actual)+(bytes>0?" · "+MediaSize.format(bytes):""));}
        final long measuredVideoId=videoId;
        // Queue before addClip on the same worker. Download metadata may have
        // rounded away the final fraction of a second, unlike the local file.
        io.execute(()->{ try { store.updateMetadata(measuredVideoId,actual,0,0); } catch (RuntimeException ignored) {} });
    }
    private void releasePreview() {
        main.removeCallbacks(previewTick); rangePlaying=false;
        if (player!=null) { positionMs=Math.max(0,player.getCurrentPosition()); if (playerView!=null) playerView.setPlayer(null); player.release(); player=null; }
        if (previewClaim) { previewClaim=false; MediaWorkload.playerStopped(); }
    }
    private void rememberRange() { if (screen==TRIM && startInput!=null && endInput!=null) { startDraft=startInput.getText().toString(); endDraft=endInput.getText().toString(); } }
    private void back() {
        if (screen==TRIM || screen==PICKER) { if (projectId>0) showProject(projectId); else showProjects(); }
        else if (screen==PROJECT) showProjects(); else finish();
    }
    @Override public void onBackPressed() { back(); }
    @Override protected void onResume() { super.onResume(); resumed=true; ensurePreview(); schedulePoll(); }
    @Override protected void onPause() { resumed=false; rememberRange(); releasePreview(); main.removeCallbacks(projectPoll); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        rememberRange(); if (player!=null) positionMs=Math.max(0,player.getCurrentPosition()); out.putInt("screen",screen); out.putLong("project_id",projectId); out.putLong("video_id",videoId); out.putLong("position_ms",positionMs);
        out.putLong("start_ms",requestedStart); out.putLong("end_ms",requestedEnd); out.putString("start_draft",startDraft); out.putString("end_draft",endDraft);out.putBoolean("precise_open",preciseOpen); super.onSaveInstanceState(out);
    }
    @Override protected void onDestroy() { releasePreview();if(covers!=null)covers.release(); destroyed=true; generation++; main.removeCallbacksAndMessages(null); io.execute(store::close); io.shutdown(); super.onDestroy(); }

    static long[] validatedRange(String start,String end,long duration) {
        long from=parseTime(start),to=parseTime(end); if (duration<=0) throw new IllegalArgumentException("尚未取得视频时长。");
        if (to<=from) throw new IllegalArgumentException("终点必须晚于起点。"); if (to>duration || from>=duration) throw new IllegalArgumentException("起止时间不能超过原片时长。"); return new long[]{from,to};
    }
    static long parseTime(String text) {
        try {
            String raw=text.trim(); if (raw.isEmpty()) throw new NumberFormatException(); String[] parts=raw.split(":",-1); if (parts.length>3) throw new NumberFormatException();
            BigDecimal total=BigDecimal.ZERO;
            for (int i=0;i<parts.length;i++) {
                if (!parts[i].matches(i==parts.length-1?"[0-9]+(?:\\.[0-9]{1,3})?":"[0-9]+")) throw new NumberFormatException();
                BigDecimal value=new BigDecimal(parts[i]); if (i>0 && value.compareTo(BigDecimal.valueOf(60))>=0) throw new NumberFormatException(); total=total.multiply(BigDecimal.valueOf(60)).add(value);
            }
            return total.movePointRight(3).longValueExact();
        } catch (Exception invalid) { throw new IllegalArgumentException("时间格式无效，请输入秒数或 分:秒，最多三位小数。"); }
    }
    static String time(long value) { long ms=Math.max(0,value); return String.format(Locale.ROOT,"%02d:%02d.%03d",ms/60000,(ms/1000)%60,ms%1000); }
    private boolean current(int token,int destination) { return !destroyed && generation==token && screen==destination; }
    private void error(int token,String detail) { main.post(()->{ if (!destroyed && token==generation) { if (feedback!=null) feedback.setText(detail); else add(body,text(detail,14,MUTED,false),20); } }); }
    private static String message(Exception failure,String fallback) { return failure.getMessage()==null?fallback:Engine.cleanLog(failure.getMessage()); }
    private void toast(String value) { Toast.makeText(this,value,Toast.LENGTH_SHORT).show(); }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout value=new LinearLayout(this); value.setOrientation(LinearLayout.VERTICAL); return value; }
    private LinearLayout row() { LinearLayout value=new LinearLayout(this); value.setOrientation(LinearLayout.HORIZONTAL); value.setGravity(Gravity.CENTER_VERTICAL); return value; }
    private TextView text(String value,int size,int color,boolean bold) { TextView view=new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setLineSpacing(dp(3),1); if (bold) view.setTypeface(null,Typeface.BOLD); return view; }
    private Button button(String label,boolean primary) {
        Button value=new Button(this); value.setText(label); value.setTextSize(14); value.setAllCaps(false); value.setMinHeight(dp(48)); value.setMinimumHeight(dp(48)); value.setPadding(dp(14),dp(10),dp(14),dp(10));
        value.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{MUTED,primary?WHITE:INK}));
        GradientDrawable shape=new GradientDrawable(); shape.setColor(primary?INK:WHITE); shape.setCornerRadius(dp(12)); if (!primary) shape.setStroke(dp(1),LINE);
        GradientDrawable disabled=new GradientDrawable(); disabled.setColor(CONTROL); disabled.setCornerRadius(dp(12)); if (!primary) disabled.setStroke(dp(1),LINE);
        android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled},disabled); states.addState(new int[]{},shape);
        value.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x18397D72),states,null));UiTheme.press(value); return value;
    }
    private Button quietButton(String label){Button value=button(label,false);value.setTextColor(MUTED);value.setBackgroundColor(android.graphics.Color.TRANSPARENT);value.setPadding(dp(8),0,dp(8),0);value.setTextSize(12);return value;}
    private void clearCovers(){for(ImageView image:boundCovers)covers.cancel(image);boundCovers.clear();}
    private ImageView cover(long id,String uri,String description){
        ImageView value=new ImageView(this);value.setScaleType(ImageView.ScaleType.CENTER_CROP);value.setContentDescription(description);GradientDrawable background=new GradientDrawable();background.setColor(CONTROL);background.setCornerRadius(dp(12));value.setBackground(background);value.setClipToOutline(true);boundCovers.add(value);
        if(!uri.isEmpty())covers.load(value,id,uri,id+"-"+Integer.toHexString(uri.hashCode()),0);return value;
    }
    private EditText input(String hint) {
        EditText value=new EditText(this); value.setSingleLine(true); value.setTextSize(15); value.setTextColor(INK); value.setHintTextColor(MUTED); value.setHint(hint); value.setPadding(dp(12),dp(12),dp(12),dp(12)); value.setMinHeight(dp(48));
        GradientDrawable shape=new GradientDrawable(); shape.setColor(WHITE); shape.setCornerRadius(dp(12)); shape.setStroke(dp(1),LINE); value.setBackground(shape); return value;
    }
    private void add(LinearLayout parent,View child,int margin) { add(parent,child,margin,new LinearLayout.LayoutParams(-1,-2)); }
    private void add(LinearLayout parent,View child,int margin,LinearLayout.LayoutParams params) { params.topMargin=dp(margin); parent.addView(child,params); }
    private void divider(LinearLayout parent) { View line=new View(this); line.setBackgroundColor(LINE); add(parent,line,12,new LinearLayout.LayoutParams(-1,dp(1))); }
}
