package dev.streampocket;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.Switch;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import java.io.File;
import java.util.Locale;

/** Explicit download and system-confirmed overwrite install; not silent installation or OS push. */
public final class UpdateActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private AppUpdates updates;
    private UiTheme.Palette palette;
    private TextView status,version,notes,detail;
    private Button primary,cancel;
    private ProgressBar progress;
    private boolean visible;
    private final AppUpdates.Listener listener=this::render;
    private final Runnable poll=new Runnable(){@Override public void run(){if(!visible)return;updates.refresh();main.postDelayed(this,1000);}};

    @Override public void onCreate(Bundle saved){
        UiTheme.apply(this);super.onCreate(saved);palette=UiTheme.get(this);updates=AppUpdates.get(this);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(palette.bg);root.setPadding(dp(20),dp(12),dp(20),dp(20));
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(dp(20),insets.getSystemWindowInsetTop()+dp(12),dp(20),insets.getSystemWindowInsetBottom()+dp(20));return insets;});
        Button back=button("‹ 返回",false);back.setOnClickListener(v->finish());root.addView(back,new LinearLayout.LayoutParams(dp(80),dp(48)));
        ScrollView scroll=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        add(body,text("应用更新",26,true),16);add(body,text("当前版本 "+BuildConfig.VERSION_NAME,13,false),8);
        status=text("",18,true);add(body,status,28);
        version=text("",15,true);add(body,version,14);detail=text("",13,false);add(body,detail,6);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);add(body,progress,16);
        primary=button("检查更新",true);add(body,primary,24);primary.setOnClickListener(v->primaryAction());
        cancel=button("取消下载",false);add(body,cancel,8);cancel.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(updates.snapshot().phase==AppUpdates.Phase.READY?"删除已下载的安装包？":"取消本次更新下载？")
                .setMessage("只删除本次更新缓存，不影响现有应用、登录信息或视频。")
                .setNegativeButton("保留",null).setPositiveButton("确认取消",(d,w)->updates.cancel()).show());
        notes=text("",14,false);notes.setTextIsSelectable(true);add(body,notes,24);
        Switch automatic=new Switch(this);automatic.setText("打开应用时检查更新");automatic.setTextColor(palette.ink);automatic.setTextSize(14);automatic.setMinHeight(dp(56));automatic.setChecked(updates.automatic());automatic.setOnCheckedChangeListener((v,checked)->updates.automatic(checked));add(body,automatic,28);
        add(body,text("每天最多自动检查一次，只读取 GitHub 公开发布。不会自动下载、静默安装，也不是系统推送；安装由你在系统界面确认。",12,false),4);
        Button source=button("查看 GitHub 发布页 ↗",false);add(body,source,14);source.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(UpdateOffer.REPO+"/releases")));}catch(RuntimeException unavailable){new AlertDialog.Builder(this).setMessage("没有可打开网页的应用").setPositiveButton("知道了",null).show();}});
        setContentView(root);UiTheme.systemBars(this);root.requestApplyInsets();render(updates.snapshot());
    }
    private void primaryAction(){
        AppUpdates.Snapshot state=updates.snapshot();
        if(state.phase==AppUpdates.Phase.AVAILABLE&&state.offer!=null){
            new AlertDialog.Builder(this).setTitle("下载 "+state.offer.version+"？")
                    .setMessage("安装包 "+size(state.offer.size)+"，使用当前网络（可能产生移动流量）。下载完成后仍需确认覆盖安装，现有数据保留。")
                    .setNegativeButton("暂不下载",null).setPositiveButton("下载更新",(d,w)->updates.download()).show();
        }else if(state.phase==AppUpdates.Phase.READY)install();
        else if(!state.active()&&state.phase!=AppUpdates.Phase.CHECKING)updates.check();
    }
    private void install(){
        if(Build.VERSION.SDK_INT>=26&&!getPackageManager().canRequestPackageInstalls()){
            new AlertDialog.Builder(this).setTitle("允许拾流请求安装更新")
                    .setMessage("需要在系统设置中允许此来源。不会自动安装；返回本页后请再次点击安装。不要卸载旧版本。")
                    .setNegativeButton("暂不",null).setPositiveButton("打开系统设置",(d,w)->{
                        try{startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())));}
                        catch(RuntimeException unavailable){showInstallError();}
                    }).show();return;
        }
        updates.prepareInstall(file->{if(!visible||isFinishing()||isDestroyed())return;launchInstaller(file);});
    }
    private void launchInstaller(File file){
        try{
            Uri uri=FileProvider.getUriForFile(this,getPackageName()+".updates",file);
            Intent install=new Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri,"application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT,true);
            install.setClipData(ClipData.newRawUri("拾流更新",uri));startActivityForResult(install,71);
        }catch(RuntimeException unavailable){showInstallError();}
    }
    private void showInstallError(){new AlertDialog.Builder(this).setTitle("暂时无法打开系统安装")
            .setMessage("安装包已保留，可返回后重试或检查此来源的安装权限。现有应用和数据未删除。")
            .setPositiveButton("知道了",null).show();}
    private void render(AppUpdates.Snapshot state){
        if(status==null)return;if(visible&&state.phase==AppUpdates.Phase.READY&&state.offer!=null)updates.notifiedReady(state.offer.code);status.setText(state.message);boolean offered=state.offer!=null;
        version.setVisibility(offered?View.VISIBLE:View.GONE);detail.setVisibility(offered?View.VISIBLE:View.GONE);notes.setVisibility(offered?View.VISIBLE:View.GONE);
        if(offered){version.setText(state.offer.version+" · "+size(state.offer.size));detail.setText("GitHub 正式发布 · arm64 · 同包名、同签名覆盖升级");notes.setText("更新内容\n\n"+(state.offer.notes.isEmpty()?"此版本未提供更新说明。":state.offer.notes));}
        boolean downloading=state.phase==AppUpdates.Phase.DOWNLOADING||state.phase==AppUpdates.Phase.PAUSED;
        progress.setVisibility(downloading||state.phase==AppUpdates.Phase.VERIFYING||state.phase==AppUpdates.Phase.CHECKING?View.VISIBLE:View.GONE);
        progress.setIndeterminate(!downloading||!offered);if(downloading&&offered){progress.setProgress((int)Math.min(1000,state.downloaded*1000/state.offer.size));detail.setText(size(state.downloaded)+" / "+size(state.offer.size));}
        primary.setEnabled(!downloading&&state.phase!=AppUpdates.Phase.CHECKING&&state.phase!=AppUpdates.Phase.VERIFYING);
        primary.setAlpha(primary.isEnabled()?1f:.5f);
        primary.setText(state.phase==AppUpdates.Phase.AVAILABLE?"下载更新":state.phase==AppUpdates.Phase.READY?"交给系统安装":state.phase==AppUpdates.Phase.CHECKING?"正在检查…":state.phase==AppUpdates.Phase.VERIFYING?"正在校验…":downloading?"等待下载完成":"检查更新");
        cancel.setVisibility(state.active()?View.VISIBLE:View.GONE);cancel.setText(state.phase==AppUpdates.Phase.READY?"删除安装包":"取消下载");
    }
    @Override protected void onResume(){super.onResume();visible=true;updates.listen(listener);main.post(poll);}
    @Override protected void onPause(){visible=false;updates.unlisten(listener);main.removeCallbacks(poll);super.onPause();}
    private TextView text(String value,int size,boolean bold){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(bold?palette.ink:palette.muted);view.setLineSpacing(dp(3),1);if(bold)view.setTypeface(null,Typeface.BOLD);return view;}
    private Button button(String label,boolean primary){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(primary?palette.surface:palette.ink);b.setMinHeight(dp(48));GradientDrawable shape=new GradientDrawable();shape.setColor(primary?palette.accent:palette.surface);shape.setCornerRadius(dp(12));b.setBackground(shape);UiTheme.press(b);return b;}
    private void add(LinearLayout parent,View child,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,child instanceof Button?dp(50):-2);p.topMargin=dp(top);parent.addView(child,p);}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    static String size(long bytes){return String.format(Locale.ROOT,"%.1f MB",Math.max(0,bytes)/1048576d);}
}
