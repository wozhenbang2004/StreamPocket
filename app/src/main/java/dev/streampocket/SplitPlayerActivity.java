package dev.streampocket;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import org.json.JSONObject;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Two local players with one shared audio focus, independent transforms and bounded resources. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
@SuppressWarnings("deprecation")
public final class SplitPlayerActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private LibraryStore store;
    private LinearLayout root,panes;
    private final Pane[] videos=new Pane[2];
    private int audible=0,pickerRequest;
    private boolean active,destroyed,horizontal,manualLayout,focusHeld,focusResume,focusBlocked,ducked,focusRequested;
    private AudioManager audio;
    private PocketDialog picker;
    private Button layoutButton;
    private final AudioManager.OnAudioFocusChangeListener audioChanges=change->{
        if(change==AudioManager.AUDIOFOCUS_LOSS||change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT){
            focusResume=change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT;focusHeld=false;focusBlocked=focusResume;
            for(Pane p:videos)if(p!=null&&p.player!=null){p.wantsPlay=p.player.getPlayWhenReady();p.player.pause();}
            if(change==AudioManager.AUDIOFOCUS_LOSS)focusHeld=false;
        }else if(change==AudioManager.AUDIOFOCUS_GAIN){focusHeld=true;focusBlocked=false;ducked=false;if(active&&focusResume){for(Pane p:videos)if(p.player!=null)p.player.setPlayWhenReady(p.wantsPlay);focusResume=false;}}
        if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)ducked=true;
        sound(audible);
    };
    private final Runnable checkpoint=new Runnable(){@Override public void run(){if(!active)return;for(Pane p:videos)p.save();main.postDelayed(this,5000);}};

    @Override public void onCreate(Bundle state){
        UiTheme.apply(this);super.onCreate(state);store=new LibraryStore(getApplicationContext());audio=(AudioManager)getSystemService(AUDIO_SERVICE);
        horizontal=state!=null?state.getBoolean("horizontal"):getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
        manualLayout=state!=null&&state.getBoolean("manual_layout");audible=state==null?0:state.getInt("audible",0);
        root=column();root.setBackgroundColor(0xFF0A0C0F);root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        LinearLayout toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);
        Button back=button("返回");back.setContentDescription("退出双视频分屏");back.setOnClickListener(v->finish());toolbar.addView(back,new LinearLayout.LayoutParams(dp(64),dp(48)));
        TextView heading=text("双视频分屏");toolbar.addView(heading,new LinearLayout.LayoutParams(0,dp(48),1));
        layoutButton=button("");layoutButton.setOnClickListener(v->{manualLayout=true;horizontal=!horizontal;arrange();});toolbar.addView(layoutButton,new LinearLayout.LayoutParams(dp(92),dp(48)));root.addView(toolbar);
        panes=new LinearLayout(this);root.addView(panes,new LinearLayout.LayoutParams(-1,0,1));
        videos[0]=new Pane(0);videos[1]=new Pane(1);for(Pane p:videos)panes.addView(p.frame);arrange();
        TextView help=text("两路独立播放 · 点声音切换 · 双指放大后可拖动");help.setTextSize(12);help.setTextColor(0xFFA1A1AA);help.setGravity(Gravity.CENTER);help.setPadding(dp(6),dp(8),dp(6),dp(8));root.addView(help);setContentView(root);
        if(state!=null){for(Pane p:videos){p.position=state.getLong("position"+p.index);p.wantsPlay=state.getBoolean("play"+p.index,true);p.load(state.getLong("video"+p.index),false);}}
        else {videos[0].position=getIntent().getLongExtra("position_ms",0);videos[0].load(getIntent().getLongExtra("video_id",0),false);}
        if(android.os.Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::finish);
    }
    private void arrange(){
        panes.setOrientation(horizontal?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);layoutButton.setText(horizontal?"改为上下":"改为左右");
        for(Pane p:videos)if(p!=null){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(horizontal?0:-1,horizontal?-1:0,1);params.setMargins(dp(2),dp(2),dp(2),dp(2));p.frame.setLayoutParams(params);if(p.zoom!=null)p.zoom.reset();}
    }
    private boolean gainFocus(){if(focusBlocked)return false;if(focusHeld)return true;focusHeld=audio.requestAudioFocus(audioChanges,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;focusRequested=focusHeld;return focusHeld;}
    private void sound(int index){audible=index;for(Pane p:videos){if(p.player!=null)p.player.setVolume(p.index==audible?(ducked?.2f:1f):0f);p.sound.setText(p.index==audible?"声音：开":"声音：关");}}
    private void pick(int index,String query,int page){
        final int request=++pickerRequest;
        io.execute(()->{List<JSONObject> list=store.listVideos(query,"all",0,51,page*50);
            main.post(()->{if(destroyed||!active||request!=pickerRequest)return;if(picker!=null)picker.dismiss();
                LinearLayout body=column();PocketEditText search=new PocketEditText(this);search.dark();search.setSingleLine(true);search.setHint("搜索视频名称");search.setText(query);body.addView(search,new LinearLayout.LayoutParams(-1,dp(52)));
                Button searchButton=button("搜索");searchButton.setOnClickListener(v->pick(index,search.getText().toString(),0));body.addView(searchButton);
                ScrollView scroll=new ScrollView(this);LinearLayout rows=column();scroll.addView(rows);body.addView(scroll,new LinearLayout.LayoutParams(-1,dp(260)));
                int count=Math.min(50,list.size());for(int n=0;n<count;n++){JSONObject item=list.get(n);long id=item.optLong("id");Button choice=button(item.optString("title","本地视频"));choice.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);choice.setSingleLine(false);choice.setEnabled(id!=videos[1-index].id);choice.setOnClickListener(v->{picker.dismiss();videos[index].load(id,true);});rows.addView(choice,new LinearLayout.LayoutParams(-1,-2));}
                if(count==0)rows.addView(text("没有匹配的视频，可更换关键词"));
                PocketDialog.Builder builder=new PocketDialog.Builder(this).setTitle("选择第 "+(index+1)+" 路视频").setView(body).setNegativeButton("取消",null);
                if(page>0)builder.setNeutralButton("上一页",(d,w)->pick(index,query,page-1));if(list.size()>50)builder.setPositiveButton("下一页",(d,w)->pick(index,query,page+1));picker=builder.show();
            });
        });
    }
    private final class Pane {
        final int index;
        final FrameLayout frame;
        final CanvasPlayerView view;
        final TextView name,message;
        final Button play,sound,reset;
        final VideoZoom zoom;
        ExoPlayer player;
        Uri uri;
        long id,position;
        int generation;
        boolean wantsPlay=true;
        Pane(int index){
            this.index=index;frame=new FrameLayout(SplitPlayerActivity.this);frame.setBackgroundColor(Color.BLACK);
            view=(CanvasPlayerView)getLayoutInflater().inflate(R.layout.streampocket_player_surface,frame,false);view.setUseController(true);view.setControllerShowTimeoutMs(2500);view.setControllerAutoShow(false);
            // Split mode has its own compact controls; keep Media3's time bar and time labels.
            View controls=view.findViewById(R.id.sp_player_more);((View)controls.getParent()).setVisibility(View.GONE);
            view.findViewById(androidx.media3.ui.R.id.exo_play_pause).setVisibility(View.GONE);view.findViewById(R.id.sp_player_marks).setVisibility(View.GONE);view.findViewById(R.id.sp_player_rotate).setVisibility(View.GONE);view.findViewById(R.id.sp_player_speed).setVisibility(View.GONE);
            frame.addView(view,new FrameLayout.LayoutParams(-1,-1));
            LinearLayout top=column();top.setBackgroundColor(0xB30A0C0F);name=text("第 "+(index+1)+" 路 · 请选择视频");name.setMaxLines(1);name.setEllipsize(android.text.TextUtils.TruncateAt.END);top.addView(name);
            LinearLayout buttons=new LinearLayout(SplitPlayerActivity.this);buttons.setBaselineAligned(false);buttons.setGravity(Gravity.CENTER_VERTICAL);Button choose=button("选择视频");choose.setOnClickListener(v->pick(index,"",0));buttons.addView(choose,new LinearLayout.LayoutParams(0,dp(48),1));
            play=button("暂停");play.setOnClickListener(v->{boolean ended=player!=null&&player.getPlaybackState()==Player.STATE_ENDED;if(ended)player.seekTo(0);wantsPlay=ended||player==null&&!wantsPlay||player!=null&&!player.getPlayWhenReady();if(player!=null&&(!wantsPlay||gainFocus()))player.setPlayWhenReady(wantsPlay);updatePlay();});buttons.addView(play,new LinearLayout.LayoutParams(0,dp(48),1));
            sound=button(index==audible?"声音：开":"声音：关");sound.setOnClickListener(v->sound(index));buttons.addView(sound,new LinearLayout.LayoutParams(0,dp(48),1));top.addView(buttons);
            frame.addView(top,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
            message=text("选择一个本地视频");message.setGravity(Gravity.CENTER);message.setBackgroundColor(0xCC0A0C0F);frame.addView(message,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER));message.setOnClickListener(v->{if(uri==null)pick(index,"",0);else{release();wantsPlay=true;message.setText("正在重新打开视频…");ensure();}});
            reset=button("复原画面");reset.setVisibility(View.GONE);FrameLayout.LayoutParams rp=new FrameLayout.LayoutParams(-2,dp(48),Gravity.END|Gravity.TOP);rp.topMargin=dp(80);frame.addView(reset,rp);
            zoom=new VideoZoom(view,()->reset.setVisibility(zoomed()?View.VISIBLE:View.GONE));reset.setOnClickListener(v->zoom.reset());updatePlay();
            view.setCanvasTouchListener((v,e)->{if(zoom.touch(e))return true;if(e.getActionMasked()==MotionEvent.ACTION_UP){if(view.isControllerFullyVisible())view.hideController();else view.showController();}return true;});
        }
        private boolean zoomed(){return zoom!=null&&zoom.transformed();}
        void load(long target,boolean fresh){
            release();id=target;uri=null;if(fresh){position=0;wantsPlay=true;}zoom.reset();final int request=++generation;
            if(target<=0){name.setText("第 "+(index+1)+" 路 · 请选择视频");return;}
            message.setText("正在打开视频…");message.setVisibility(View.VISIBLE);
            io.execute(()->{JSONObject item=store.video(target);String problem=null;Uri resolved=null;
                if(item!=null){resolved=Uri.parse(item.optString("uri"));try(android.content.res.AssetFileDescriptor file=getContentResolver().openAssetFileDescriptor(resolved,"r")){if(file==null)problem="找不到视频文件";}catch(Exception error){problem=CoreRules.userMessage(error);}}
                final Uri source=resolved;final String failure=problem;
                main.post(()->{if(destroyed||request!=generation)return;if(item==null||failure!=null){message.setText(item==null?"视频记录不存在，请选择其他视频":failure+"\n点击重试，或选择其他视频");return;}
                    name.setText(item.optString("title","本地视频"));uri=source;if(fresh)position=item.optLong("playbackMs",0);ensure();});
            });
        }
        void ensure(){
            if(!active||uri==null||player!=null)return;
            try{ExoPlayer current=new ExoPlayer.Builder(SplitPlayerActivity.this,new DefaultRenderersFactory(SplitPlayerActivity.this).setEnableDecoderFallback(true)).build();player=current;MediaWorkload.playerStarted();
                current.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),false);current.setHandleAudioBecomingNoisy(true);
                current.addListener(new Player.Listener(){
                    @Override public void onRenderedFirstFrame(){if(player==current)message.setVisibility(View.GONE);}
                    @Override public void onIsPlayingChanged(boolean playing){updatePlay();}
                    @Override public void onPlaybackStateChanged(int state){if(player!=current)return;if(state==Player.STATE_ENDED){wantsPlay=false;save();updatePlay();}}
                    @Override public void onPlayerError(PlaybackException error){if(player!=current)return;current.pause();message.setText("此路播放失败，设备可能无法同时解码两路视频。\n点击重试，或选择其他视频");message.setVisibility(View.VISIBLE);}
                });
                view.setPlayer(current);current.setMediaItem(MediaItem.fromUri(uri));current.setVolume(index==audible?(ducked?.2f:1f):0f);current.seekTo(position);current.prepare();current.setPlayWhenReady(wantsPlay&&gainFocus());view.showController();updatePlay();
            }catch(RuntimeException error){release();message.setText("此路播放器启动失败，点击重试或选择其他视频");message.setVisibility(View.VISIBLE);}
        }
        void updatePlay(){play.setEnabled(player!=null);play.setText(player!=null&&player.getPlaybackState()==Player.STATE_ENDED?"重播":player!=null&&player.getPlayWhenReady()?"暂停":"播放");}
        void save(){if(player==null||id<=0)return;position=Math.max(0,player.getCurrentPosition());final long video=id,at=position;io.execute(()->{try{store.savePlayback(video,at);}catch(RuntimeException ignored){}});}
        void release(){if(player==null)return;save();wantsPlay=player.getPlayWhenReady();ExoPlayer old=player;player=null;view.setPlayer(null);old.release();MediaWorkload.playerStopped();updatePlay();}
    }
    @Override public void onConfigurationChanged(Configuration configuration){super.onConfigurationChanged(configuration);if(!manualLayout)horizontal=configuration.orientation==Configuration.ORIENTATION_LANDSCAPE;arrange();}
    @Override public void finish(){
        Pane first=videos[0];if(first!=null&&first.id>0)setResult(RESULT_OK,new android.content.Intent().putExtra("video_id",first.id)
            .putExtra("position_ms",first.player==null?first.position:first.player.getCurrentPosition())
            .putExtra("should_play",first.player==null?first.wantsPlay:first.player.getPlayWhenReady()));
        super.finish();
    }
    @Override protected void onResume(){super.onResume();active=true;for(Pane p:videos)p.ensure();main.postDelayed(checkpoint,5000);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    @Override protected void onPause(){active=false;pickerRequest++;if(picker!=null)picker.dismiss();main.removeCallbacks(checkpoint);for(Pane p:videos)p.release();if(focusRequested){audio.abandonAudioFocus(audioChanges);focusRequested=false;}focusHeld=focusBlocked=ducked=focusResume=false;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);super.onPause();}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putInt("audible",audible);state.putBoolean("horizontal",horizontal);state.putBoolean("manual_layout",manualLayout);for(Pane p:videos){state.putLong("video"+p.index,p.id);state.putLong("position"+p.index,p.player==null?p.position:p.player.getCurrentPosition());state.putBoolean("play"+p.index,p.player==null?p.wantsPlay:p.player.getPlayWhenReady());}}
    @Override protected void onDestroy(){destroyed=true;pickerRequest++;for(Pane p:videos)p.release();main.removeCallbacksAndMessages(null);io.execute(store::close);io.shutdown();super.onDestroy();}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private TextView text(String value){TextView v=new TextView(this);v.setText(value);v.setTextColor(0xFFFAFAFA);v.setTextSize(14);v.setGravity(Gravity.CENTER_VERTICAL);v.setPadding(dp(10),dp(6),dp(10),dp(6));return v;}
    private Button button(String label){Button v=new Button(this);v.setText(label);v.setTextSize(12);v.setAllCaps(false);v.setTextColor(0xFF70B8A8);v.setMinWidth(0);v.setMinimumWidth(0);v.setMinHeight(dp(48));v.setPadding(dp(4),0,dp(4),0);v.setBackgroundColor(0xFF17191D);return v;}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
