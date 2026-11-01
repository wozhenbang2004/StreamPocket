package dev.streampocket;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.util.Locale;

/** Explicit download and system-confirmed overwrite install; not silent installation or OS push. */
public final class UpdateActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private AppUpdates updates;
    private UiTheme.Palette palette;
    private TextView status,version,notes,detail,continuation;
    private Button primary,cancel;
    private ProgressBar progress;
    private boolean visible,preparingInstall;
    private PocketDialog sourceDialog;
    private final AppUpdates.Listener listener=this::render;
    private final Runnable poll=new Runnable(){@Override public void run(){if(!visible)return;updates.refresh();main.postDelayed(this,1000);}};

    @Override public void onCreate(Bundle saved){
        UiTheme.apply(this);super.onCreate(saved);palette=UiTheme.get(this);updates=AppUpdates.get(this);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(palette.bg);root.setPadding(dp(20),dp(12),dp(20),dp(20));
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(dp(20),insets.getSystemWindowInsetTop()+dp(12),dp(20),insets.getSystemWindowInsetBottom()+dp(20));return insets;});
        LinearLayout bar=new LinearLayout(this);bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        Button back=PocketButton.of(this,"",PocketButton.Role.QUIET);PocketIcon arrow=new PocketIcon("back",palette.ink);arrow.setBounds(0,0,dp(22),dp(22));back.setCompoundDrawables(arrow,null,null,null);back.setPadding(dp(12),0,dp(12),0);back.setContentDescription("返回");back.setOnClickListener(v->finish());bar.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView title=text("应用更新",20,true);bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));root.addView(bar);
        ScrollView scroll=new ScrollView(this);scroll.setClipToPadding(false);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        add(body,text(updates.installedLabel(),13,false),8);
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(18),dp(18),dp(18),dp(18));GradientDrawable face=new GradientDrawable();face.setColor(palette.surface);face.setCornerRadius(dp(16));card.setBackground(face);add(body,card,20);
        status=text("",17,true);add(card,status,0);
        version=text("",24,true);add(card,version,16);detail=text("",13,false);add(card,detail,6);
        progress=new PocketProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);add(card,progress,16);progress.getLayoutParams().height=dp(5);
        continuation=text("下载完成后会提示安装",12,false);add(card,continuation,10);
        primary=button("检查更新",true);add(card,primary,20);primary.setOnClickListener(v->primaryAction());
        cancel=PocketButton.of(this,"取消下载",PocketButton.Role.QUIET);add(card,cancel,6);cancel.setOnClickListener(v->new PocketDialog.Builder(this).setTitle(updates.snapshot().phase==AppUpdates.Phase.READY?"删除已下载的安装包？":"取消本次更新下载？")
                .setMessage("不会影响当前应用和已保存的视频。")
                .setNegativeButton("保留",null).setPositiveButton("确认取消",(d,w)->updates.cancel()).show());
        notes=text("",14,false);notes.setTextIsSelectable(true);add(body,notes,24);
        PocketSwitch automatic=new PocketSwitch(this);automatic.setText("打开应用时检查更新");automatic.setChecked(updates.automatic());automatic.setOnCheckedChangeListener((v,checked)->updates.automatic(checked));add(body,automatic,20);
        Button help=PocketButton.of(this,"更新帮助",PocketButton.Role.QUIET);add(body,help,12);help.setOnClickListener(v->showUpdateHelp());
        setContentView(root);UiTheme.systemBars(this);root.requestApplyInsets();render(updates.snapshot());
    }
    private void primaryAction(){
        AppUpdates.Snapshot state=updates.snapshot();
        if(state.phase==AppUpdates.Phase.AVAILABLE&&state.offer!=null){
            new PocketDialog.Builder(this).setTitle("下载 "+state.offer.version+"？")
                    .setMessage("下载大小 "+size(state.offer.size)+"。建议使用 Wi-Fi，更新会保留现有数据。")
                    .setNegativeButton("暂不下载",null).setPositiveButton("下载更新",(d,w)->updates.download()).show();
        }else if(state.phase==AppUpdates.Phase.READY&&state.offer!=null){
            if(updates.installerLaunched(state.offer))updates.retryInstaller(state.offer);
            if(needsSourcePermission())requestSourcePermission(state.offer);else continueInstall(state);
        }
        else if(!state.active()&&state.phase!=AppUpdates.Phase.CHECKING)updates.check();
    }
    private boolean needsSourcePermission(){return Build.VERSION.SDK_INT>=26&&!getPackageManager().canRequestPackageInstalls();}
    private void requestSourcePermission(UpdateOffer offer){
        if(sourceDialog!=null||!visible)return;updates.markSourcePrompted(offer);
        sourceDialog=new PocketDialog.Builder(this).setTitle("允许安装更新")
                .setMessage("请在系统设置中允许拾流安装应用，开启后返回即可继续。")
                .setNegativeButton("暂不",null).setPositiveButton("打开系统设置",(d,w)->{
                    try{startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())),72);}
                    catch(RuntimeException unavailable){showInstallError();}
                }).create();
        sourceDialog.setOnDismissListener(d->{sourceDialog=null;render(updates.snapshot());});sourceDialog.show();
    }
    private void continueInstall(AppUpdates.Snapshot state){
        if(!visible||isFinishing()||isDestroyed()||preparingInstall||state.phase!=AppUpdates.Phase.READY||state.offer==null||updates.installerLaunched(state.offer))return;
        if(needsSourcePermission()){if(!updates.sourcePrompted(state.offer))requestSourcePermission(state.offer);return;}
        UpdateOffer offer=state.offer;preparingInstall=true;
        updates.prepareInstall(offer,file->{
            preparingInstall=false;
            if(!visible||isFinishing()||isDestroyed())return;
            if(updates.claimInstaller(offer))launchInstaller(file);
        });
    }
    private void launchInstaller(File file){AppUpdates.Snapshot state=updates.snapshot();if(state.offer!=null)updates.stageInstall(state.offer,file);}
    private void showPendingConfirmation(AppUpdates.Snapshot state){
        if(!visible||state.offer==null)return;Intent confirm=updates.takeInstallConfirmation(state.offer);if(confirm==null)return;
        try{startActivityForResult(confirm,71);}catch(RuntimeException unavailable){updates.installationLaunchFailed(state.offer);showInstallError();}
    }
    private void showInstallError(){new PocketDialog.Builder(this).setTitle("暂时无法打开系统安装")
            .setMessage("安装包已保留。请允许拾流安装应用后重试。")
            .setPositiveButton("知道了",null).show();}
    private void showUpdateHelp(){
        new PocketDialog.Builder(this).setTitle("更新帮助").setItems(new String[]{"查看版本记录","复制问题信息"},(dialog,which)->{
            if(which==0){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(UpdateOffer.REPO+"/releases")));}
                catch(RuntimeException unavailable){PocketNotice.show(this,"没有可打开网页的应用");}}
            else{android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("拾流安装信息",updates.installationDetails()));PocketNotice.show(this,"问题信息已复制");}
        }).setNegativeButton("返回",null).show();
    }
    private void render(AppUpdates.Snapshot state){
        if(status==null)return;if(!state.active())preparingInstall=false;if(visible)updates.clearDownloadReminder();
        if(visible&&state.phase==AppUpdates.Phase.READY&&state.offer!=null)updates.notifiedReady(state.offer.code);status.setText(state.message);status.setVisibility(state.phase==AppUpdates.Phase.IDLE?View.GONE:View.VISIBLE);boolean offered=state.offer!=null;
        version.setVisibility(offered?View.VISIBLE:View.GONE);detail.setVisibility(offered?View.VISIBLE:View.GONE);notes.setVisibility(offered?View.VISIBLE:View.GONE);
        if(offered){version.setText(state.offer.version);detail.setText(size(state.offer.size)+" · 覆盖更新，保留现有数据");notes.setText(ReleaseNotes.format(state.offer.notes));}
        boolean downloading=state.phase==AppUpdates.Phase.DOWNLOADING||state.phase==AppUpdates.Phase.PAUSED;
        progress.setVisibility(downloading||state.phase==AppUpdates.Phase.VERIFYING||state.phase==AppUpdates.Phase.CHECKING?View.VISIBLE:View.GONE);
        progress.setIndeterminate(!downloading||!offered);if(downloading&&offered){int percent=(int)Math.min(100,state.downloaded*100/state.offer.size);progress.setProgress((int)Math.min(1000,state.downloaded*1000/state.offer.size));progress.setContentDescription("更新下载 "+percent+"%");detail.setText(percent+"% · "+size(state.downloaded)+" / "+size(state.offer.size));}
        boolean ready=state.phase==AppUpdates.Phase.READY&&offered;
        boolean launched=ready&&updates.installerLaunched(state.offer),permission=ready&&needsSourcePermission();
        if(ready)status.setText(launched?(!updates.installationMessage(state.offer).isEmpty()?updates.installationMessage(state.offer):"等待安装确认"):permission?"需要允许拾流安装更新":"正在准备安装…");
        primary.setVisibility(downloading||ready&&!launched&&!permission?View.GONE:View.VISIBLE);continuation.setVisibility(downloading?View.VISIBLE:View.GONE);
        primary.setEnabled(!downloading&&state.phase!=AppUpdates.Phase.CHECKING&&state.phase!=AppUpdates.Phase.VERIFYING);
        primary.setAlpha(primary.isEnabled()?1f:.5f);
        primary.setText(state.phase==AppUpdates.Phase.AVAILABLE?"下载更新":launched?"继续安装":permission?"允许安装更新":state.phase==AppUpdates.Phase.CHECKING?"正在检查…":state.phase==AppUpdates.Phase.VERIFYING?"正在准备安装…":downloading?"下载完成后自动继续":"检查更新");
        cancel.setVisibility(state.active()?View.VISIBLE:View.GONE);cancel.setText(state.phase==AppUpdates.Phase.READY?"删除安装包":"取消下载");
        showPendingConfirmation(state);continueInstall(state);
    }
    @Override protected void onResume(){super.onResume();visible=true;updates.clearDownloadReminder();updates.resumeInstallation();updates.listen(listener);main.post(poll);}
    @Override protected void onPause(){visible=false;updates.unlisten(listener);main.removeCallbacks(poll);super.onPause();}
    @Override protected void onDestroy(){if(sourceDialog!=null)sourceDialog.dismiss();super.onDestroy();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==71){AppUpdates.Snapshot state=updates.snapshot();if(state.offer!=null)updates.installationReturned(state.offer,result==RESULT_OK);}if(request==71||request==72)render(updates.snapshot());}
    private TextView text(String value,int size,boolean bold){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(bold?palette.ink:palette.muted);view.setLineSpacing(dp(3),1);if(bold)view.setTypeface(null,Typeface.BOLD);return view;}
    private Button button(String label,boolean primary){return PocketButton.of(this,label,primary?PocketButton.Role.PRIMARY:PocketButton.Role.SECONDARY);}
    private void add(LinearLayout parent,View child,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,child instanceof Button?dp(50):-2);p.topMargin=dp(top);parent.addView(child,p);}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    static String size(long bytes){return String.format(Locale.ROOT,"%.1f MB",Math.max(0,bytes)/1048576d);}
}
