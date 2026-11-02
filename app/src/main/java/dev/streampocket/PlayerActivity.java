package dev.streampocket;

import android.app.Activity;
import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.Window;
import android.transition.ChangeBounds;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerControlView;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.TimeBar;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full video canvas, a window-composited Media3 player, and tools disclosed in dark overlays. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class PlayerActivity extends Activity {
    private static final int BLACK=0xFF0C0C0C, WHITE=0xFFFAFAFA, MUTED=0xFFAAAAAA, ACCENT=0xFFE8E8E8,
            PANEL=0xFF1A1A1A, LINE=0xFF383838;
    private static final float[] SPEEDS={.5f,.75f,1f,1.25f,1.5f,2f};
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private LibraryStore store;
    private long videoId,positionMs,durationMs,lastQueuedPosition=Long.MIN_VALUE,measuredDuration;
    private int videoWidth,videoHeight,measuredWidth,measuredHeight,loadGeneration,visibleMarkers=40;
    private float speed=1f,heldSpeed=1f;
    private boolean pressBoost;
    private boolean nextPending;
    private int nextRequest;
    private boolean started,resumed,destroyed,loaded,missing,fullscreen,bookmarkPending,rangePending;
    private boolean shouldPlay=true,restoredPosition,playbackResourceClaimed,scrubbing;
    private String sheetMode="";
    private Uri mediaUri;
    private JSONObject video;
    private List<JSONObject> markers=new ArrayList<>();
    private ExoPlayer player;
    private PlayerView playerView;
    private PlayerControlView controller;
    private FrameLayout root,videoFrame,sheetOverlay,previewBubble;
    private LinearLayout sheetBody,rangePanel,markerPanel,markerList,menuPanel,errorPanel;
    private ScrollView details;
    private TextView title,status,sheetTitle,markerHeading,rangeFeedback,speedButton,previewTime,markerListButton;
    private ImageView previewImage;
    private View markerOptionsButton,fullscreenButton;
    private Button bookmarkButton,clipButton,retryButton,saveRangeButton;
    private EditText rangeStart,rangeEnd,rangeName;
    private TimeBar timeBar;
    private View timeBarView;
    private ScrubPreviewLoader previewLoader;
    private TimestampThumbnails markerThumbnails;
    private final List<MarkerFrame> markerFrames=new ArrayList<>();
    private View sheetHandle;
    private long scrubPosition, previewSampleMs=-1;
    private boolean fillVideo,firstFrameRendered,coverTransition,enterTransitionReleased;
    private int coverGeneration,gestureMode;
    private float gestureDownX,gestureDownY,gestureBrightness,gestureVolume=1f;
    private long gesturePosition,gestureTarget;
    private ImageView transitionCover;
    private TextView gestureHint;
    private HighlightTicks highlightTicks;
    private GestureDetector gestures;
    private VideoZoom zoom;
    private Button resetZoom;
    private boolean splitReturning;
    private ValueAnimator sheetAnimator;
    private VelocityTracker canvasVelocity;
    private boolean canvasSheetDrag;
    private float sheetOffset;
    private final Runnable clearHint=()->{ if (gestureHint!=null) gestureHint.setVisibility(View.GONE); };

    private final Runnable videoStartupCheck=()->{
        if(player==null||!resumed||firstFrameRendered||player.getPlaybackState()!=Player.STATE_READY||!player.getPlayWhenReady())return;
        player.pause();shouldPlay=false;showError("视频画面未能启动，已暂停播放。请点击重试，或检查视频文件是否完整。",true);
    };

    private final Runnable checkpoint=new Runnable() {
        @Override public void run() {
            if (!resumed || player==null || !player.isPlaying()) return;
            savePosition(); main.postDelayed(this,5000);
        }
    };

    @Override public void onCreate(Bundle state) {
        UiTheme.apply(this);
        getWindow().requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS);
        super.onCreate(state); videoId=state==null?getIntent().getLongExtra("video_id",0):state.getLong("video_id",getIntent().getLongExtra("video_id",0));
        String transitionName=getIntent().getStringExtra("transition_name");
        coverTransition=UiTheme.motionEnabled(this) && ("sp-video-"+videoId).equals(transitionName);
        if (coverTransition) {
            ChangeBounds bounds=new ChangeBounds(); bounds.setDuration(240);
            getWindow().setSharedElementEnterTransition(bounds); getWindow().setSharedElementReturnTransition(bounds);
            postponeEnterTransition(); main.postDelayed(this::releaseEnterTransition,450);
        }
        if (state!=null) {
            splitReturning=state.getBoolean("split_returning",false);
            positionMs=Math.max(0,state.getLong("position_ms",0)); speed=validSpeed(state.getFloat("speed",1f));
            shouldPlay=state.getBoolean("should_play",true); restoredPosition=true; fillVideo=state.getBoolean("fill_video",false);
        }
        store=new LibraryStore(getApplicationContext()); buildUi(); loadTransitionCover(); immersive();
        if (Build.VERSION.SDK_INT>=33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::handleBack);
        if (state!=null && state.getBoolean("range_open",false)) {
            rangeStart.setText(state.getString("range_start","")); rangeEnd.setText(state.getString("range_end",""));
            rangeName.setText(state.getString("range_name","")); showSheet("range");
        }
        loadVideo();
    }

    private void buildUi() {
        root=new FrameLayout(this); root.setBackgroundColor(BLACK); videoFrame=root;
        root.setOnApplyWindowInsetsListener((view,insets)->{
            view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()); return insets;
        });
        playerView=(PlayerView)getLayoutInflater().inflate(R.layout.streampocket_player_surface,root,false);
        root.addView(playerView,new FrameLayout.LayoutParams(-1,-1));
        applyAspect();
        playerView.setUseController(true); playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        // Media3 1.9.4's animated layout manager retains a progress-only stage for
        // another 2 seconds. Our compact overlay must disappear as one object at
        // 2.5s, so use its public single-stage timer and animate only appearance.
        playerView.setControllerAnimationEnabled(false); playerView.setControllerAutoShow(false);
        playerView.setControllerHideOnTouch(true); playerView.setControllerShowTimeoutMs(2500);
        controller=playerView.findViewById(androidx.media3.ui.R.id.exo_controller);
        playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener)visibility->{
            controller.animate().cancel(); controller.setAlpha(1f);
            if (visibility==View.VISIBLE && controller.isAttachedToWindow() && UiTheme.motionEnabled(this)) {
                controller.setAlpha(0f); controller.animate().alpha(1f).setDuration(160).start();
            }
        });
        title=playerView.findViewById(R.id.sp_player_title); title.setText("正在打开视频…");
        playerView.findViewById(R.id.sp_player_back).setOnClickListener(v->finishPlayback());
        playerView.findViewById(R.id.sp_player_more).setOnClickListener(v->showSheet("more"));
        speedButton=playerView.findViewById(R.id.sp_player_speed); speedButton.setText(speedLabel(speed));
        speedButton.setOnClickListener(v->showSpeeds());
        markerOptionsButton=playerView.findViewById(R.id.sp_player_marks);
        markerOptionsButton.setOnClickListener(v->{ if (player!=null && !missing) addMarker(); else showMarkerOptions(v); });
        markerOptionsButton.setOnLongClickListener(v->{showMarkerOptions(v);return true;});
        markerOptionsButton.setContentDescription("标记此刻");
        markerListButton=playerView.findViewById(R.id.sp_player_marker_list);
        markerListButton.setOnClickListener(v->showSheet("markers"));
        markerThumbnails=new TimestampThumbnails(this);
        fullscreenButton=playerView.findViewById(R.id.sp_player_rotate); fullscreenButton.setOnClickListener(v->rotateVideo());
        timeBarView=playerView.findViewById(androidx.media3.ui.R.id.exo_progress); timeBar=(TimeBar)timeBarView;
        FrameLayout progressStack=playerView.findViewById(R.id.sp_player_progress_stack);
        highlightTicks=new HighlightTicks(); progressStack.addView(highlightTicks,new FrameLayout.LayoutParams(-1,-1));
        buildTransitionCover(); buildPreview(); buildError(); buildSheet(); buildGestureHint(); bindGestures();
        resetZoom=button("复原画面",false);resetZoom.setContentDescription("一键复原视频缩放");resetZoom.setVisibility(View.GONE);
        FrameLayout.LayoutParams resetParams=new FrameLayout.LayoutParams(-2,dp(48),Gravity.END|Gravity.TOP);resetParams.topMargin=dp(68);resetParams.rightMargin=dp(16);root.addView(resetZoom,resetParams);
        zoom=new VideoZoom(playerView,()->resetZoom.setVisibility(zoom!=null&&zoom.transformed()?View.VISIBLE:View.GONE));resetZoom.setOnClickListener(v->zoom.reset());
        previewLoader=new ScrubPreviewLoader(getApplicationContext(),new ScrubPreviewLoader.Listener() {
            @Override public void onPreview(long requestedAt,long sampleAt,Bitmap bitmap) {
                // Loader callbacks already run on main and validate source/scrub
                // generation. Do not repost: that could cross a later reset.
                if (destroyed || !scrubbing) return;
                previewSampleMs=sampleAt; previewImage.setImageBitmap(bitmap); updatePreviewTime();
            }
            @Override public void onUnavailable(long at) {
                if (destroyed || !scrubbing || at!=scrubPosition) return;
                previewSampleMs=-1; previewImage.setImageDrawable(null); previewTime.setText(time(at)+" · 暂无预览");
            }
            @Override public void onReset() {
                previewSampleMs=-1; previewImage.setImageDrawable(null);
                if (scrubbing) updatePreviewTime();
            }
        });
        timeBar.addListener(new TimeBar.OnScrubListener() {
            @Override public void onScrubStart(TimeBar bar,long at) {
                scrubbing=true; playerView.setControllerShowTimeoutMs(0); updateScrubPreview(at);
            }
            @Override public void onScrubMove(TimeBar bar,long at) { updateScrubPreview(at); }
            @Override public void onScrubStop(TimeBar bar,long at,boolean canceled) {
                scrubbing=false; hidePreview(); resetControllerTimeout();
            }
        });
        setContentView(root); root.requestApplyInsets(); setMediaActions(false); renderMarkers(); playerView.showController();
    }


    private void applyAspect() {
        if (playerView!=null) playerView.setResizeMode(fillVideo?AspectRatioFrameLayout.RESIZE_MODE_ZOOM:AspectRatioFrameLayout.RESIZE_MODE_FIT);
        if (transitionCover!=null) transitionCover.setScaleType(fillVideo?ImageView.ScaleType.CENTER_CROP:ImageView.ScaleType.FIT_CENTER);
    }
    private void buildTransitionCover() {
        transitionCover=new ImageView(this); transitionCover.setBackgroundColor(BLACK);
        transitionCover.setScaleType(fillVideo?ImageView.ScaleType.CENTER_CROP:ImageView.ScaleType.FIT_CENTER);
        transitionCover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        transitionCover.setVisibility(View.GONE); transitionCover.setTransitionName("sp-video-"+videoId);
        root.addView(transitionCover,new FrameLayout.LayoutParams(-1,-1));
    }
    static File validatedCoverFile(String raw,File cache) {
        if (raw==null || raw.isEmpty()) return null;
        try {
            File directory=new File(cache,"video-covers").getCanonicalFile(),file=new File(raw).getCanonicalFile();
            return directory.equals(file.getParentFile()) && file.getName().endsWith(".jpg") && file.isFile() && file.length()>0 && file.length()<=4*1024*1024?file:null;
        } catch (IOException invalid) { return null; }
    }
    private void loadTransitionCover() {
        if (!coverTransition) return;
        String raw=getIntent().getStringExtra("transition_cover_path"); final int generation=++coverGeneration;
        io.execute(()->{
            Bitmap decoded=null;
            try {
                File file=validatedCoverFile(raw,getCacheDir());
                if (file!=null) {
                    BitmapFactory.Options bounds=new BitmapFactory.Options(); bounds.inJustDecodeBounds=true; BitmapFactory.decodeFile(file.getPath(),bounds);
                    if (bounds.outWidth>0 && bounds.outHeight>0) {
                        BitmapFactory.Options options=new BitmapFactory.Options(); int sample=1;
                        while (bounds.outWidth/sample>640 || bounds.outHeight/sample>640) sample*=2;
                        options.inSampleSize=sample; decoded=BitmapFactory.decodeFile(file.getPath(),options);
                    }
                }
            } catch (RuntimeException|OutOfMemoryError unavailable) { decoded=null; }
            final Bitmap bitmap=decoded;
            main.post(()->{
                if (destroyed || generation!=coverGeneration) { if(bitmap!=null)bitmap.recycle(); return; }
                if (bitmap!=null) { transitionCover.setImageBitmap(bitmap); if (!firstFrameRendered && !missing) transitionCover.setVisibility(View.VISIBLE); }
                releaseEnterTransition();
            });
        });
    }
    private void releaseEnterTransition() {
        if (enterTransitionReleased) return; enterTransitionReleased=true; startPostponedEnterTransition();
    }
    private void revealVideo() {
        releaseEnterTransition();main.removeCallbacks(videoStartupCheck);
        // The first decoded frame owns visibility immediately. Shared-element and
        // property animations must never keep a still image above a playing video.
        if(transitionCover!=null){transitionCover.animate().cancel();transitionCover.setVisibility(View.GONE);transitionCover.setAlpha(1f);}
    }
    private void refreshPreviewPreparation() {
        if(previewLoader==null)return;
        // A ready/playing player already owns the native video decoder. Optional
        // storyboard warming is allowed while paused, never on the first live frame.
        if(player!=null&&resumed&&firstFrameRendered&&player.getPlaybackState()==Player.STATE_READY&&!player.getPlayWhenReady())
            previewLoader.prepare(player.getCurrentPosition());
        else previewLoader.pausePreparation();
    }
    private void finishPlayback() {
        savePosition(); releaseEnterTransition();
        if (coverTransition && transitionCover.getDrawable()!=null) {
            transitionCover.animate().cancel(); transitionCover.setAlpha(1f); transitionCover.setVisibility(View.VISIBLE); finishAfterTransition();
        } else finish();
    }
    private void buildGestureHint() {
        gestureHint=text("",13,WHITE,false); gestureHint.setGravity(Gravity.CENTER); gestureHint.setPadding(dp(14),dp(9),dp(14),dp(9));
        gestureHint.setBackground(shape(0xE6202020,12)); gestureHint.setVisibility(View.GONE);
        gestureHint.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        FrameLayout.LayoutParams hint=new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER_HORIZONTAL|Gravity.TOP);hint.topMargin=dp(72);
        root.addView(gestureHint,hint);
    }
    private void showHint(String message) {
        main.removeCallbacks(clearHint); gestureHint.setText(message); gestureHint.setVisibility(View.VISIBLE); main.postDelayed(clearHint,1300);
    }
    private int seekSeconds() { int value=getSharedPreferences("config",0).getInt("seek_seconds",10);return value==5 || value==10 || value==15 || value==30?value:10; }
    private long playbackDuration() { long actual=player==null?0:player.getDuration(); return actual>0 && actual!=C.TIME_UNSET?actual:durationMs; }
    private long boundedPosition(long value) { long total=playbackDuration();return Math.max(0,total>0?Math.min(value,total):value); }
    private void seekRelative(boolean forward) {
        if (player==null || missing) return; long delta=seekSeconds()*1000L,at=boundedPosition(player.getCurrentPosition()+(forward?delta:-delta));
        player.seekTo(at); positionMs=at; savePosition(); showHint((forward?"快进 ":"快退 ")+seekSeconds()+" 秒 · "+time(at));
    }
    private void bindGestures() {
        gestures=new GestureDetector(this,new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) { if (sheetMode.isEmpty()) playerView.performClick(); return true; }
            @Override public void onLongPress(MotionEvent event){if(gestureMode==0&&(zoom==null||!zoom.transformed())&&sheetMode.isEmpty()&&player!=null&&!missing&&player.getPlayWhenReady()){heldSpeed=player.getPlaybackParameters().speed;pressBoost=true;player.setPlaybackSpeed(2f);showHint("临时 2× · 松手恢复");}}
            @Override public boolean onDoubleTap(MotionEvent event) { seekRelative(event.getX()>=playerView.getWidth()/2f);return true; }
        });
        ((CanvasPlayerView)playerView).setCanvasTouchListener((view,event)->{
            if (!sheetMode.isEmpty()&&!canvasSheetDrag) return false;
            if (zoom!=null&&zoom.touch(event)) { cancelCanvasGesture();MotionEvent cancel=MotionEvent.obtain(event);cancel.setAction(MotionEvent.ACTION_CANCEL);gestures.onTouchEvent(cancel);cancel.recycle();return true; }
            int action=event.getActionMasked();
            if(pressBoost){if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){endPressBoost();cancelCanvasGesture();gestures.onTouchEvent(event);}return true;}
            if (action==MotionEvent.ACTION_DOWN) {
                recycleCanvasVelocity();canvasVelocity=VelocityTracker.obtain();canvasVelocity.addMovement(event);
                gestureMode=0;gestureDownX=event.getX();gestureDownY=event.getY();gesturePosition=player==null?0:player.getCurrentPosition();gestureTarget=gesturePosition;
                gestureBrightness=getWindow().getAttributes().screenBrightness;
                if (gestureBrightness<0) { try {gestureBrightness=android.provider.Settings.System.getInt(getContentResolver(),android.provider.Settings.System.SCREEN_BRIGHTNESS)/255f;} catch (android.provider.Settings.SettingNotFoundException unavailable) {gestureBrightness=.5f;} }
                gestureVolume=player==null?1f:player.getVolume();
            } else if (action==MotionEvent.ACTION_MOVE && loaded) {
                if(canvasVelocity!=null)canvasVelocity.addMovement(event);
                float dx=event.getX()-gestureDownX,dy=event.getY()-gestureDownY;
                if (gestureMode==0 && Math.max(Math.abs(dx),Math.abs(dy))>dp(14)) {
                    if (Math.abs(dx)>Math.abs(dy)*1.3f && player!=null&&!missing) gestureMode=1;
                    else if (Math.abs(dy)>Math.abs(dx)*1.3f) {
                        boolean side=gestureDownX<view.getWidth()*.12f||gestureDownX>view.getWidth()*.88f;
                        if (side&&player!=null&&!missing&&getSharedPreferences("config",0).getBoolean("player_gestures",false)) gestureMode=2;
                        else {gestureMode=4;playerView.animate().cancel();}
                    }
                    if (gestureMode!=0) { MotionEvent cancel=MotionEvent.obtain(event);cancel.setAction(MotionEvent.ACTION_CANCEL);gestures.onTouchEvent(cancel);cancel.recycle(); }
                }
                if (gestureMode==1) {
                    long span=Math.min(180000,Math.max(1000,playbackDuration()));gestureTarget=boundedPosition(gesturePosition+(long)(dx/Math.max(1,view.getWidth())*span));
                    scrubbing=true;playerView.setControllerShowTimeoutMs(0);playerView.showController();updateScrubPreview(gestureTarget);showHint("定位 · "+time(gestureTarget));
                } else if(gestureMode==4){playerView.setTranslationY(Math.max(-dp(96),Math.min(dp(96),dy*.25f)));showHint((Math.abs(dy)>=nextSwipeDistance()?"松手播放":"继续滑动，切换")+(dy>=0?"下一个视频":"上一个视频"));
                } else if(gestureMode==3) {setSheetOffset(sheetTravel()+dy);
                } else if (gestureMode==2) {
                    float delta=-dy/Math.max(1,view.getHeight());
                    if (gestureDownX<view.getWidth()/2f) {
                        float brightness=Math.max(.02f,Math.min(1f,gestureBrightness+delta));WindowManager.LayoutParams attrs=getWindow().getAttributes();attrs.screenBrightness=brightness;getWindow().setAttributes(attrs);showHint("屏幕亮度 · "+Math.round(brightness*100)+"%");
                    } else {float volume=Math.max(0f,Math.min(1f,gestureVolume+delta));player.setVolume(volume);showHint("视频音量 · "+Math.round(volume*100)+"%");}
                }
            } else if (action==MotionEvent.ACTION_UP) {
                int mode=gestureMode;
                if(mode==4){float distance=event.getY()-gestureDownY;cancelCanvasGesture();if(Math.abs(distance)>=nextSwipeDistance())playAdjacentVideo(distance>0);return true;}
                if (mode==1 && player!=null && !missing) {player.seekTo(gestureTarget);positionMs=gestureTarget;savePosition();}
                if(mode==3){float velocity=0;if(canvasVelocity!=null){canvasVelocity.addMovement(event);canvasVelocity.computeCurrentVelocity(1000);velocity=canvasVelocity.getYVelocity();}settleSheet(velocity<-dp(650)||(velocity<dp(650)&&sheetOffset<sheetTravel()*.70f),true);}
                if (mode!=0) {canvasSheetDrag=false;cancelCanvasGesture();return true;}
                recycleCanvasVelocity();
            } else if (action==MotionEvent.ACTION_CANCEL) { cancelCanvasGesture();gestures.onTouchEvent(event);return true; }
            if (gestureMode==0) gestures.onTouchEvent(event);return true;
        });
    }
    private void recycleCanvasVelocity(){if(canvasVelocity!=null){canvasVelocity.recycle();canvasVelocity=null;}}
    private void endPressBoost(){if(pressBoost){pressBoost=false;if(player!=null)player.setPlaybackSpeed(heldSpeed);}}
    private void cancelCanvasGesture() { endPressBoost();if(gestureMode==4){playerView.animate().cancel();playerView.animate().translationY(0).setDuration(UiTheme.motionEnabled(this)?160:0).start();} if(canvasSheetDrag&&gestureMode==3&&sheetAnimator==null)settleSheet(false,true);canvasSheetDrag=false;recycleCanvasVelocity();gestureMode=0; if(scrubbing){scrubbing=false;hidePreview();resetControllerTimeout();} }
    private float nextSwipeDistance(){return Math.max(dp(72),playerView.getHeight()*.12f);}
    private void playNextVideo(){playAdjacentVideo(true);}
    private void playAdjacentVideo(boolean forward){
        if(nextPending||destroyed||!loaded)return;
        if(bookmarkPending||rangePending){showHint("正在保存标记，请稍候再切换");return;}
        nextPending=true;final int request=++nextRequest;final long currentId=videoId;
        String query=getIntent().getStringExtra("library_query"),order=getIntent().getStringExtra("library_order");long collection=getIntent().getLongExtra("library_collection",0);
        List<String> sources=getIntent().getStringArrayListExtra("library_sources");
        io.execute(()->{long next=0;Exception failure=null;try{next=store.adjacentVideoId(currentId,query,sources,collection,order,forward);}catch(Exception error){failure=error;}final long target=next;final Exception problem=failure;
            main.post(()->{if(destroyed||request!=nextRequest)return;nextPending=false;if(!resumed||videoId!=currentId)return;
                if(problem!=null){showHint("暂时无法读取下一个视频，请返回视频库刷新");return;}
                if(target<=0){showHint(forward?"已经是当前列表的最后一个视频":"已经是当前列表的第一个视频");return;}
                releasePlayer();clearMarkerFrames();markers=new ArrayList<>();video=null;mediaUri=null;videoId=target;getIntent().putExtra("video_id",target);
                zoom.reset();
                positionMs=0;durationMs=0;lastQueuedPosition=Long.MIN_VALUE;restoredPosition=false;shouldPlay=true;firstFrameRendered=false;coverTransition=false;coverGeneration++;
                if(transitionCover!=null){transitionCover.setImageDrawable(null);transitionCover.setVisibility(View.GONE);}loadVideo();showHint("正在打开下一个视频");
            });
        });
    }
    /** Highlight ticks are a visual overlay only; they never change media/ad state. */
    private final class HighlightTicks extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private float[] starts=new float[0],ends=new float[0];
        HighlightTicks() { super(PlayerActivity.this);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);setFocusable(false);setClickable(false); }
        void setMarkers(List<JSONObject> source,long duration) {
            int count=duration>0?Math.min(400,source.size()):0;starts=new float[count];ends=new float[count];
            for(int i=0;i<count;i++) {long at=markerStart(source.get(i)),end=source.get(i).optLong("endMs");starts[i]=Math.min(1f,(float)at/duration);ends[i]=end>at?Math.min(1f,(float)end/duration):starts[i];}
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);float start=dp(6),width=Math.max(0,getWidth()-dp(12)),y=getHeight()/2f;
            paint.setColor(ACCENT);paint.setStrokeWidth(dp(2));
            for(int i=0;i<starts.length;i++) {float x=start+width*starts[i];canvas.drawLine(x,y-dp(5),x,y+dp(5),paint);if(ends[i]>starts[i])canvas.drawLine(x,y-dp(5),start+width*ends[i],y-dp(5),paint);}
        }
    }

    private void buildError() {
        errorPanel=column(); errorPanel.setGravity(Gravity.CENTER); errorPanel.setPadding(dp(24),dp(20),dp(24),dp(20));
        errorPanel.setBackground(shape(0xDD1A1A1A,12));
        status=text("正在读取本地视频…",14,WHITE,false); status.setGravity(Gravity.CENTER);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); errorPanel.addView(status,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout actions=row(); actions.setGravity(Gravity.CENTER);
        retryButton=button("重试",false); retryButton.setOnClickListener(v->loadVideo());
        actions.addView(retryButton,new LinearLayout.LayoutParams(dp(96),dp(48)));
        Button back=button("返回视频库",false); back.setOnClickListener(v->finishPlayback());
        actions.addView(back,new LinearLayout.LayoutParams(dp(120),dp(48))); add(errorPanel,actions,12,0);
        int width=Math.min(dp(320),getResources().getDisplayMetrics().widthPixels-dp(32));
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(width,-2,Gravity.CENTER); p.setMargins(dp(16),dp(16),dp(16),dp(16)); root.addView(errorPanel,p);
    }

    private void buildPreview() {
        previewBubble=new FrameLayout(this); previewBubble.setBackground(shape(PANEL,8)); previewBubble.setVisibility(View.GONE);
        LinearLayout contents=column(); previewImage=new ImageView(this); previewImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        contents.addView(previewImage,new LinearLayout.LayoutParams(dp(160),dp(90)));
        previewTime=text("",12,WHITE,false); previewTime.setGravity(Gravity.CENTER); contents.addView(previewTime,new LinearLayout.LayoutParams(-1,dp(24)));
        previewBubble.addView(contents); root.addView(previewBubble,new FrameLayout.LayoutParams(dp(160),dp(114),Gravity.TOP|Gravity.START));
    }

    private void updateScrubPreview(long at) {
        if (!scrubbing) return; scrubPosition=Math.max(0,at); updatePreviewTime();
        previewBubble.setVisibility(View.VISIBLE); positionPreview(scrubPosition); previewLoader.request(scrubPosition);
    }
    private void updatePreviewTime() {
        // Keep the seek target immediate, but identify the image as an approximate
        // earlier keyframe instead of silently relabelling it as the latest time.
        previewTime.setText(time(scrubPosition)+(previewSampleMs>=0?" · 预览≈"+time(previewSampleMs):" · 正在预览"));
    }
    private void positionPreview(long at) {
        int[] bar=new int[2],parent=new int[2]; timeBarView.getLocationInWindow(bar); root.getLocationInWindow(parent);
        int width=Math.min(dp(160),Math.max(dp(80),root.getWidth()-root.getPaddingLeft()-root.getPaddingRight()));
        float fraction=durationMs>0?Math.min(1f,(float)at/durationMs):0;
        int left=bar[0]-parent[0]+(int)(timeBarView.getWidth()*fraction)-width/2;
        left=Math.max(root.getPaddingLeft()+dp(8),Math.min(left,root.getWidth()-root.getPaddingRight()-width-dp(8)));
        int top=Math.max(root.getPaddingTop()+dp(8),bar[1]-parent[1]-dp(122));
        FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)previewBubble.getLayoutParams(); p.width=width; p.leftMargin=left-root.getPaddingLeft(); p.topMargin=top-root.getPaddingTop(); previewBubble.setLayoutParams(p);
    }
    private void hidePreview() { if (previewLoader!=null) previewLoader.clear(); previewImage.setImageDrawable(null); previewBubble.setVisibility(View.GONE); }

    private void buildSheet() {
        sheetOverlay=new ToolOverlay(); sheetOverlay.setBackgroundColor(0x66000000); sheetOverlay.setVisibility(View.GONE);
        sheetOverlay.setOnClickListener(v->closeSheet());
        sheetBody=column(); sheetBody.setPadding(dp(20),dp(10),dp(20),dp(16)); sheetBody.setBackground(shape(PANEL,16));
        sheetBody.setOnClickListener(v->{});
        FrameLayout handle=new FrameLayout(this);sheetHandle=handle;handle.setContentDescription("向下拖动收起工具面板");View grip=new View(this);grip.setBackground(shape(0xFF777777,3));handle.addView(grip,new FrameLayout.LayoutParams(dp(36),dp(4),Gravity.CENTER));sheetBody.addView(handle,new LinearLayout.LayoutParams(-1,dp(20)));
        LinearLayout heading=row(); sheetTitle=text("",16,WHITE,true); heading.addView(sheetTitle,new LinearLayout.LayoutParams(0,dp(48),1));
        ImageButton close=new ImageButton(this); close.setImageResource(R.drawable.sp_player_close); close.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        close.setPadding(dp(10),dp(10),dp(10),dp(10)); close.setContentDescription("关闭工具面板"); close.setOnClickListener(v->closeSheet()); heading.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48))); sheetBody.addView(heading);
        details=new ToolScrollView(); details.setFillViewport(false); LinearLayout contents=column();
        menuPanel=column(); contents.addView(menuPanel);
        markerPanel=column(); LinearLayout markerActions=row();
        bookmarkButton=button("＋ 标记此刻",true); bookmarkButton.setContentDescription("标记此刻");bookmarkButton.setOnClickListener(v->addMarker());markerActions.addView(bookmarkButton,new LinearLayout.LayoutParams(0,dp(48),1));
        Button range=button("标记精彩片段",false);range.setContentDescription("标记精彩片段");range.setOnClickListener(v->openRangePanel());LinearLayout.LayoutParams rangeParams=new LinearLayout.LayoutParams(0,dp(48),1);rangeParams.leftMargin=dp(8);markerActions.addView(range,rangeParams);markerPanel.addView(markerActions);
        markerHeading=text("已保存的标记",13,MUTED,false); add(markerPanel,markerHeading,16,6); markerList=column(); markerPanel.addView(markerList); contents.addView(markerPanel);
        buildRangePanel(contents); details.addView(contents,new ScrollView.LayoutParams(-1,-2)); sheetBody.addView(details,new LinearLayout.LayoutParams(-1,-2));
        sheetOverlay.addView(sheetBody,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM)); root.addView(sheetOverlay,new FrameLayout.LayoutParams(-1,-1));
        details.setOnScrollChangeListener((view,x,y,oldX,oldY)->bindVisibleMarkerFrames());
    }

    private void showSheet(String mode) { showSheet(mode,false); }
    private void showSheet(String mode,boolean dragging) {
        boolean fresh=sheetMode.isEmpty(),interrupted=sheetAnimator!=null;cancelSheetAnimation();
        if(!"markers".equals(mode))clearMarkerFrames();
        sheetMode=mode; if("markers".equals(mode)&&previewLoader!=null)previewLoader.pausePreparation(); sheetOverlay.setVisibility(View.VISIBLE); playerView.setControllerShowTimeoutMs(0); playerView.showController();
        menuPanel.setVisibility(View.GONE); markerPanel.setVisibility(View.GONE); rangePanel.setVisibility(View.GONE);
        if ("range".equals(mode)) { sheetTitle.setText("标记精彩片段"); rangePanel.setVisibility(View.VISIBLE); }
        else if ("markers".equals(mode)) { sheetTitle.setText("精彩标记"); markerPanel.setVisibility(View.VISIBLE); renderMarkers(); }
        else {
            menuPanel.setVisibility(View.VISIBLE); menuPanel.removeAllViews();
            if ("speed".equals(mode)) {
                sheetTitle.setText("播放倍速");
                LinearLayout speeds=null;for(int n=0;n<SPEEDS.length;n++){if(n%3==0){speeds=row();add(menuPanel,speeds,8,0);}final float option=SPEEDS[n];Button choose=PocketButton.dark(this,speedLabel(option),option==speed?PocketButton.Role.PRIMARY:PocketButton.Role.QUIET);choose.setSelected(option==speed);choose.setContentDescription(speedLabel(option)+(option==speed?"，当前倍速":""));choose.setOnClickListener(v->{speed=option;if(player!=null)player.setPlaybackSpeed(speed);speedButton.setText(speedLabel(speed));closeSheet();});LinearLayout.LayoutParams cell=new LinearLayout.LayoutParams(0,dp(48),1);if(n%3>0)cell.leftMargin=dp(8);speeds.addView(choose,cell);}
            } else if ("aspect".equals(mode)) {
                sheetTitle.setText("画面比例");
                action(menuPanel,"完整显示"+(!fillVideo?"    当前":""),()->{fillVideo=false;applyAspect();closeSheet();});
                action(menuPanel,"填充裁切"+(fillVideo?"    当前":""),()->{fillVideo=true;applyAspect();closeSheet();});
                add(menuPanel,text("填充会裁去画面边缘，不会拉伸原视频。",12,MUTED,false),8,8);
            } else if ("gestures".equals(mode)) {
                sheetTitle.setText("播放手势");
                info("单击","显示或隐藏控制"); info("双击左右","快退 / 快进 "+seekSeconds()+" 秒");
                info("横向滑动","预览并调整播放位置");info("按住画面","临时 2×，松手恢复原倍速");
                info("上下刷视频","下滑下一个，上滑上一个");
                info("双指缩放","最大 5×，放大后单指移动；点复原画面恢复原比例");
                info("屏幕边缘上下滑",getSharedPreferences("config",0).getBoolean("player_gestures",false)?"左侧亮度 · 右侧视频音量":"已关闭，可在设置开启");
                info("精彩标记","记录喜欢的时刻，随时回来继续看");
            } else if ("info".equals(mode)) {
                sheetTitle.setText("视频信息"); String name=video==null?"本地视频":video.optString("title","本地视频");
                add(menuPanel,text(name,15,WHITE,true),10,16);
                long bytes=video==null?0:video.optLong("bytes");
                info("文件大小",bytes>0?MediaSize.format(bytes):"大小待获取"); info("时长",durationMs>0?preciseTime(durationMs):"未知");
                info("画面",videoWidth>0 && videoHeight>0?videoWidth+" × "+videoHeight:"未知");
                if (video!=null) { info("文件名",video.optString("fileName","")); info("来源",sourceLabel(video.optString("source"))); }
            } else {
                sheetTitle.setText("更多操作");LinearLayout creative=actionGroup(menuPanel);
                actionRow(creative,"精彩标记","查看、跳转和管理已保存的时间点","bookmark",()->showSheet("markers"));
                clipButton=actionRow(creative,"截取片段","选择时间范围并导出","scissors",this::openEditor);clipButton.setEnabled(player!=null&&!missing);
                LinearLayout viewing=actionGroup(menuPanel);actionRow(viewing,"视频信息","来源、画面与本地文件","info",()->showSheet("info"));
                actionRow(viewing,"下一个视频","也可在画面向下滑动","play",()->{closeSheet();playNextVideo();});
                actionRow(viewing,"双视频分屏","同时播放两路，独立缩放与声音切换","play",()->{closeSheet();splitReturning=true;startActivityForResult(new Intent(this,SplitPlayerActivity.class).putExtra("video_id",videoId).putExtra("position_ms",player==null?positionMs:player.getCurrentPosition()),74);});
                actionRow(viewing,"画面比例 · "+(fillVideo?"填充裁切":"完整显示"),"保留完整画幅，按需切换","crop",()->showSheet("aspect"));
                actionRow(viewing,"播放手势","点击、滑动与按住倍速","gesture",()->showSheet("gestures"));
            }
        }
        layoutSheet(); details.scrollTo(0,0);
        int width=sheetBody.getLayoutParams().width>0?sheetBody.getLayoutParams().width:Math.max(1,root.getWidth()-root.getPaddingLeft()-root.getPaddingRight());
        sheetBody.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(Math.max(1,root.getHeight()),View.MeasureSpec.AT_MOST));
        playerView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        if(fresh)setSheetOffset(sheetTravel());
        if(!dragging)settleSheet(true,fresh||interrupted);
    }
    private LinearLayout actionGroup(LinearLayout parent){LinearLayout group=column();group.setBackground(shape(0xFF282828,16));add(parent,group,12,0);return group;}
    private Button actionRow(LinearLayout parent,String label,String subtitle,String icon,Runnable run){
        FrameLayout row=new FrameLayout(this);Button hit=PocketButton.dark(this,label,PocketButton.Role.QUIET);hit.setGravity(Gravity.TOP|Gravity.START);hit.setPadding(dp(58),dp(14),dp(36),dp(30));hit.setTextSize(14);hit.setContentDescription(label+"，"+subtitle);hit.setOnClickListener(v->run.run());row.addView(hit,new FrameLayout.LayoutParams(-1,-1));
        ImageView glyph=new ImageView(this);glyph.setImageDrawable(new PocketIcon(icon,0xFFDDDDDD));glyph.setPadding(dp(8),dp(8),dp(8),dp(8));glyph.setBackground(shape(0xFF363636,10));glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);FrameLayout.LayoutParams gp=new FrameLayout.LayoutParams(dp(34),dp(34),Gravity.CENTER_VERTICAL|Gravity.START);gp.leftMargin=dp(12);row.addView(glyph,gp);
        TextView hint=text(subtitle,12,MUTED,false);hint.setSingleLine(true);hint.setEllipsize(TextUtils.TruncateAt.END);hint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(-1,dp(24),Gravity.BOTTOM);hp.leftMargin=dp(58);hp.rightMargin=dp(32);hp.bottomMargin=dp(9);row.addView(hint,hp);
        ImageView arrow=new ImageView(this);arrow.setImageDrawable(new PocketIcon("chevron",MUTED));arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);FrameLayout.LayoutParams ap=new FrameLayout.LayoutParams(dp(16),dp(16),Gravity.END|Gravity.CENTER_VERTICAL);ap.rightMargin=dp(14);row.addView(arrow,ap);parent.addView(row,new LinearLayout.LayoutParams(-1,dp(72)));return hit;
    }
    private void info(String label,String value) { if (value.isEmpty()) return; add(menuPanel,text(label+"  "+value,13,MUTED,false),8,8); }
    private void closeSheet() { hideKeyboard();settleSheet(false,true); }
    private boolean sideDrawer(){return "markers".equals(sheetMode)&&getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;}
    private float sheetTravel(){return Math.max(dp(80),sideDrawer()?sheetBody.getMeasuredWidth()+dp(12):sheetBody.getMeasuredHeight()+((FrameLayout.LayoutParams)sheetBody.getLayoutParams()).bottomMargin);}
    private void setSheetOffset(float value){sheetOffset=Math.max(0,Math.min(sheetTravel(),value));sheetBody.setTranslationX(sideDrawer()?sheetOffset:0);sheetBody.setTranslationY(sideDrawer()?0:sheetOffset);sheetOverlay.setBackgroundColor(android.graphics.Color.argb(Math.round(102*(1-sheetOffset/sheetTravel())),0,0,0));}
    private void cancelSheetAnimation(){if(sheetAnimator!=null){ValueAnimator old=sheetAnimator;sheetAnimator=null;old.cancel();}}
    private void settleSheet(boolean open,boolean animate){
        cancelSheetAnimation();float target=open?0:sheetTravel();
        if(!animate||!UiTheme.motionEnabled(this)){setSheetOffset(target);finishSheetSettle(open);return;}
        ValueAnimator animation=ValueAnimator.ofFloat(sheetOffset,target);sheetAnimator=animation;
        animation.setDuration(220);animation.setInterpolator(new android.view.animation.DecelerateInterpolator());
        animation.addUpdateListener(value->setSheetOffset((float)value.getAnimatedValue()));
        animation.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator ended){if(sheetAnimator!=ended)return;sheetAnimator=null;finishSheetSettle(open);}});animation.start();
    }
    private void finishSheetSettle(boolean open){
        if(open){bindVisibleMarkerFrames();sheetTitle.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);return;}
        clearMarkerFrames();sheetMode="";sheetOverlay.setVisibility(View.GONE);sheetBody.setTranslationY(0);sheetBody.setTranslationX(0);sheetOffset=0;
        refreshPreviewPreparation();
        playerView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);resetControllerTimeout();
    }
    private void resetControllerTimeout() { playerView.setControllerShowTimeoutMs(scrubbing || !sheetMode.isEmpty()?0:2500); if (!scrubbing) playerView.showController(); }
    private void layoutSheet() {
        boolean landscape=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
        boolean side=sideDrawer();
        int maxHeight=side?getResources().getDisplayMetrics().heightPixels-dp(16):(int)(getResources().getDisplayMetrics().heightPixels*(landscape?.82f:.64f));
        int width=landscape?Math.min(dp(side?360:440),getResources().getDisplayMetrics().widthPixels-dp(32)):-1;
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(width,side?maxHeight:-2,Gravity.BOTTOM|(landscape?Gravity.END:Gravity.CENTER_HORIZONTAL));
        if (landscape) p.setMargins(dp(12),dp(8),dp(12),dp(8)); sheetBody.setLayoutParams(p);
        sheetHandle.setVisibility(side?View.GONE:View.VISIBLE);
        ((ToolScrollView)details).maximumHeight=Math.max(dp(96),maxHeight-dp(side?74:94));
        details.setLayoutParams(new LinearLayout.LayoutParams(-1,-2)); details.requestLayout();
    }

    private void buildRangePanel(LinearLayout parent) {
        rangePanel=column(); rangePanel.setVisibility(View.GONE);
        add(rangePanel,text("按原视频时间保存，不受观看倍速影响。",12,MUTED,false),4,12);
        rangeStart=rangeTimeRow("开始",rangePanel); rangeEnd=rangeTimeRow("结束",rangePanel);
        rangeName=new PocketEditText(this); styleInput(rangeName); rangeName.setHint("片段名称（可选）"); rangeName.setContentDescription("精彩片段名称，可选");
        rangeName.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)}); rangePanel.addView(rangeName,new LinearLayout.LayoutParams(-1,dp(48)));
        rangeFeedback=text("例如 01:20.250，也可输入秒数。",12,MUTED,false); rangeFeedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); add(rangePanel,rangeFeedback,10,12);
        saveRangeButton=button("保存精彩片段",true); saveRangeButton.setOnClickListener(v->saveRange()); rangePanel.addView(saveRangeButton,new LinearLayout.LayoutParams(-1,dp(48))); parent.addView(rangePanel);
    }
    private EditText rangeTimeRow(String label,LinearLayout parent) {
        LinearLayout line=row(); TextView caption=text(label,13,MUTED,false); line.addView(caption,new LinearLayout.LayoutParams(dp(48),dp(48))); caption.setGravity(Gravity.CENTER_VERTICAL);
        EditText input=new PocketEditText(this); styleInput(input); input.setHint("00:00.000"); input.setSelectAllOnFocus(true); input.setContentDescription("精彩片段"+label+"时间");
        input.setInputType(android.text.InputType.TYPE_CLASS_DATETIME|android.text.InputType.TYPE_DATETIME_VARIATION_TIME); input.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789:.")); line.addView(input,new LinearLayout.LayoutParams(0,dp(48),1));
        Button current=button("取当前",false); current.setContentDescription("将"+label+"时间设为当前播放位置"); current.setOnClickListener(v->{
            if (player==null || missing) { rangeFeedback.setText("原视频当前无法打开。"); return; }
            input.setText(preciseTime(Math.max(0,player.getCurrentPosition()))); rangeFeedback.setText("已更新"+label+"时间。");
        }); line.addView(current,new LinearLayout.LayoutParams(dp(80),dp(48))); parent.addView(line); return input;
    }
    private void styleInput(EditText input) {
        if(input instanceof PocketEditText)((PocketEditText)input).dark();
        input.setSingleLine(true); input.setTextSize(14); input.setTextColor(WHITE); input.setHintTextColor(MUTED);
        input.setBackgroundTintList(android.content.res.ColorStateList.valueOf(LINE));
    }
    private void showMarkerOptions(View anchor) { showSheet("markers"); }
    private void showSpeeds() { showSheet("speed"); }
    private void openRangePanel() {
        if (player==null || missing) { toast("请先打开可播放的本地视频"); return; }
        if (!"range".equals(sheetMode)) {
            long at=Math.max(0,player.getCurrentPosition()),actual=player.getDuration(),total=actual>0 && actual!=C.TIME_UNSET?actual:durationMs;
            rangeStart.setText(preciseTime(at)); rangeEnd.setText(preciseTime(total>0?Math.min(total,at+10000):at+10000)); rangeName.setText("");
            rangeFeedback.setText("播放到结束位置后，可点结束行的“取当前”。");
        }
        showSheet("range");
    }
    private void saveRange() {
        if (rangePending || player==null || missing) return; final long start,end;
        try { start=parseTime(rangeStart.getText().toString()); end=parseTime(rangeEnd.getText().toString()); }
        catch (IllegalArgumentException invalid) { rangeFeedback.setText("请输入有效时间，例如 01:20.250。"); return; }
        long actual=player.getDuration(),total=actual>0 && actual!=C.TIME_UNSET?actual:durationMs;
        if (total<=0) { rangeFeedback.setText("正在读取完整视频时长，请稍后再保存。"); return; }
        if (end<=start) { rangeFeedback.setText("结束时间必须晚于开始时间。"); return; }
        if (start>=total || end>total) { rangeFeedback.setText("片段时间不能超过视频时长 "+preciseTime(total)+"。"); return; }
        final String name=rangeName.getText().toString().trim();final long id=videoId; rangePending=true; saveRangeButton.setEnabled(false); rangeFeedback.setText("正在保存…");
        io.execute(()->{ try {
            store.addRangeMarker(id,start,end,name); List<JSONObject> saved=store.markers(id);
            main.post(()->{ if (!destroyed&&videoId==id) { rangePending=false; saveRangeButton.setEnabled(true); markers=saved; renderMarkers(); closeSheet(); toast("已保存精彩片段"); } });
        } catch (RuntimeException error) { main.post(()->{ if (!destroyed) { rangePending=false; saveRangeButton.setEnabled(true); rangeFeedback.setText("保存失败，输入已保留，请重试。"); } }); } });
    }

    private void loadVideo() {
        int generation=++loadGeneration;final long requestedId=videoId; releasePlayer(); loaded=false; missing=false; measuredDuration=0; measuredWidth=0; measuredHeight=0;
        setMediaActions(false); errorPanel.setVisibility(View.VISIBLE); status.setText("正在读取本地视频…"); retryButton.setVisibility(View.GONE);
        io.execute(()->{ try {
            JSONObject item=requestedId>0?store.video(requestedId):null; List<JSONObject> saved=requestedId>0?store.markers(requestedId):new ArrayList<>();
            String accessError=item!=null&&item.optBoolean("missing",true)?unreadableVideoMessage(Uri.parse(item.optString("uri",""))):null;
            main.post(()->{
                if (destroyed || generation!=loadGeneration) return; markers=saved; video=item; renderMarkers();
                if (item==null) { title.setText("视频记录不存在"); showError("视频记录不存在，请返回视频库选择其他视频。",false); return; }
                title.setText(item.optString("title","本地视频")); durationMs=Math.max(0,item.optLong("durationMs")); videoWidth=item.optInt("width"); videoHeight=item.optInt("height"); if (highlightTicks!=null) highlightTicks.setMarkers(markers,durationMs);
                if (!restoredPosition) positionMs=Math.max(0,item.optLong("playbackMs")); restoredPosition=true;
                mediaUri=Uri.parse(item.optString("uri","")); missing=accessError!=null || !("content".equals(mediaUri.getScheme()) || "file".equals(mediaUri.getScheme())); loaded=true;
                if (missing) { showError(accessError!=null?accessError:"视频文件地址已失效。请恢复原文件或重新下载；已有精彩标记仍保留。",true); return; }
                previewLoader.setSource(mediaUri,durationMs); errorPanel.setVisibility(View.GONE); ensurePlayer();
            });
        } catch (Exception failure) { main.post(()->{ if (!destroyed && generation==loadGeneration) showError("本地视频记录读取失败，请重试。",true); }); } });
    }
    private void showError(String message,boolean retry) {
        releaseEnterTransition(); if (transitionCover!=null) transitionCover.setVisibility(View.GONE);
        status.setText(message); errorPanel.setVisibility(View.VISIBLE); retryButton.setVisibility(retry?View.VISIBLE:View.GONE); setMediaActions(false);
    }
    private void ensurePlayer() {
        if (!started || !loaded || missing || destroyed || player!=null) return;
        try {
            ExoPlayer current=new ExoPlayer.Builder(this,new DefaultRenderersFactory(this).setEnableDecoderFallback(true)).build(); player=current;
            playbackResourceClaimed=true; MediaWorkload.playerStarted();
            current.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true); current.setHandleAudioBecomingNoisy(true);
            current.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (player!=current) return;
                    if (state==Player.STATE_READY) {
                        if(rejectUnrenderableTracks(current.getCurrentTracks()))return;
                        persistActualMetadata();setMediaActions(true);errorPanel.setVisibility(View.GONE);refreshPreviewPreparation();
                        if(!firstFrameRendered){main.removeCallbacks(videoStartupCheck);main.postDelayed(videoStartupCheck,8000);}
                    }
                    else if (state==Player.STATE_BUFFERING) previewLoader.pausePreparation();
                    else if (state==Player.STATE_ENDED) { shouldPlay=false; savePosition(); playerView.showController(); }
                }
                @Override public void onIsPlayingChanged(boolean playing) {
                    if (player!=current) return; main.removeCallbacks(checkpoint);
                    refreshPreviewPreparation();
                    if (playing && resumed) { getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); main.postDelayed(checkpoint,5000); }
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
                @Override public void onPlayWhenReadyChanged(boolean playWhenReady,int reason) { if(player==current){
                    main.removeCallbacks(videoStartupCheck);if(playWhenReady&&!firstFrameRendered)main.postDelayed(videoStartupCheck,8000);refreshPreviewPreparation();
                } }
                @Override public void onRenderedFirstFrame() { if (player==current) { firstFrameRendered=true; revealVideo(); refreshPreviewPreparation(); } }
                @Override public void onTracksChanged(Tracks tracks) { if(player==current)rejectUnrenderableTracks(tracks); }
                @Override public void onVideoSizeChanged(VideoSize size) { if (player==current) { videoWidth=size.width; videoHeight=size.height; persistActualMetadata(); } }
                @Override public void onPlayerError(PlaybackException error) { if (player==current) {
                    main.removeCallbacks(checkpoint); getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); showError(playbackError(error),true);
                } }
            });
            firstFrameRendered=false; playerView.setPlayer(current); current.setMediaItem(MediaItem.fromUri(mediaUri)); current.setPlaybackSpeed(speed);
            positionMs=durationMs>0 && positionMs>=durationMs-500?0:positionMs; current.seekTo(Math.max(0,positionMs)); current.prepare(); current.setPlayWhenReady(resumed && shouldPlay); main.removeCallbacks(videoStartupCheck);main.postDelayed(videoStartupCheck,8000);playerView.showController();
        } catch (RuntimeException error) { releasePlayer(); showError("播放器初始化失败，请重试或返回视频库。",true); }
    }
    private boolean rejectUnrenderableTracks(Tracks tracks) {
        if(tracks.isEmpty()||tracks.containsType(C.TRACK_TYPE_VIDEO)&&tracks.isTypeSelected(C.TRACK_TYPE_VIDEO))return false;
        if(player!=null)player.pause();shouldPlay=false;
        showError(tracks.containsType(C.TRACK_TYPE_VIDEO)?"当前设备无法播放这个视频。":"这个文件没有可播放的画面。",true);return true;
    }
    private String playbackError(PlaybackException error) {
        if(error.errorCode==PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)return CoreRules.userMessage(new java.io.FileNotFoundException("file does not exist"));
        if(error.errorCode==PlaybackException.ERROR_CODE_IO_NO_PERMISSION)return CoreRules.userMessage(new SecurityException("permission denied"));
        return "暂时无法播放这个视频，文件可能不完整或格式不受支持。请重新下载，或尝试用其他播放器打开。";
    }
    private String unreadableVideoMessage(Uri uri){
        if(!"file".equals(uri.getScheme())&&!"content".equals(uri.getScheme()))return "视频文件地址已失效。请重新导入或下载，已有精彩标记仍保留。";
        try(android.content.res.AssetFileDescriptor file=getContentResolver().openAssetFileDescriptor(uri,"r")){return file==null?CoreRules.userMessage(new java.io.FileNotFoundException("file does not exist"))+"已有精彩标记仍保留。":null;}
        catch(Exception error){return CoreRules.userMessage(error)+"已有精彩标记仍保留。";}
    }
    private void persistActualMetadata() {
        if (player==null) return; long previousDuration=durationMs,actual=player.getDuration(); if (actual!=C.TIME_UNSET && actual>0) durationMs=actual;
        if (durationMs!=previousDuration && mediaUri!=null) { previewLoader.setSource(mediaUri,durationMs); refreshPreviewPreparation(); }
        if (highlightTicks!=null) highlightTicks.setMarkers(markers,durationMs);
        if (durationMs==measuredDuration && videoWidth==measuredWidth && videoHeight==measuredHeight) return;
        measuredDuration=durationMs; measuredWidth=videoWidth; measuredHeight=videoHeight;
        final long duration=durationMs,id=videoId; final int width=videoWidth,height=videoHeight;
        io.execute(()->{ try { store.updateMetadata(id,duration,width,height); } catch (RuntimeException ignored) {} });
    }

    private void addMarker() {
        if (player==null || bookmarkPending || missing) return; final long at=Math.max(0,player.getCurrentPosition()),id=videoId; bookmarkPending=true; bookmarkButton.setEnabled(false);
        io.execute(()->{ try {
            store.addMarker(id,at,""); List<JSONObject> saved=store.markers(id);
            main.post(()->{ if (!destroyed&&videoId==id) { markers=saved; bookmarkPending=false; bookmarkButton.setEnabled(player!=null && !missing); renderMarkers(); showHint("已标记 · "+time(at)); } });
        } catch (RuntimeException failure) { main.post(()->{ if (!destroyed) { bookmarkPending=false; bookmarkButton.setEnabled(player!=null && !missing); toast("标记保存失败，请重试"); } }); } });
    }
    private static final class MarkerFrame {
        final ImageView image; final TextView placeholder; final long at; boolean requested;TimestampThumbnails.Binding binding;
        MarkerFrame(ImageView image,TextView placeholder,long at){this.image=image;this.placeholder=placeholder;this.at=at;}
    }
    private void clearMarkerFrames(){if(markerThumbnails!=null)markerThumbnails.clear();for(MarkerFrame frame:markerFrames){frame.requested=false;frame.binding=null;}}
    private void bindVisibleMarkerFrames(){
        if(destroyed||!resumed||!"markers".equals(sheetMode)||mediaUri==null||missing)return;
        for(MarkerFrame frame:markerFrames){
            android.graphics.Rect bounds=new android.graphics.Rect();
            if(!frame.image.getGlobalVisibleRect(bounds)){
                if(frame.binding!=null){frame.binding.cancel();frame.binding=null;}frame.requested=false;frame.image.setImageDrawable(null);frame.placeholder.setVisibility(View.VISIBLE);frame.placeholder.setText("画面待载入");continue;
            }
            if(frame.requested)continue;
            frame.requested=true;frame.placeholder.setText("正在取帧");
            frame.binding=markerThumbnails.load(mediaUri,frame.at,durationMs,bitmap->{
                if(!markerFrames.contains(frame))return;frame.binding=null;frame.requested=bitmap!=null;
                frame.image.setImageBitmap(bitmap);frame.placeholder.setVisibility(bitmap==null?View.VISIBLE:View.GONE);frame.placeholder.setText("暂无画面");
            });
        }
    }
    private void renderMarkers() {
        if(highlightTicks!=null)highlightTicks.setMarkers(markers,durationMs);
        if(markerListButton!=null){markerListButton.setText(markers.isEmpty()?"标记":"标记 "+markers.size());markerListButton.setContentDescription("打开精彩标记列表，已保存 "+markers.size()+" 个");}
        if(markerList==null)return;clearMarkerFrames();markerFrames.clear();
        markerHeading.setText(markers.isEmpty()?"还没有标记":"按时间排列 · "+markers.size()+" 个");markerList.removeAllViews();
        if(markers.isEmpty())add(markerList,text("播放到喜欢的地方，点“标记此刻”。\n下次从这里直接回到那个画面。",13,MUTED,false),12,20);
        for(int i=0;i<Math.min(markers.size(),visibleMarkers);i++){
            JSONObject marker=markers.get(i);long at=markerStart(marker),end=marker.optLong("endMs");String name=marker.optString("label"),location=markerLocation(marker);
            LinearLayout line=row();line.setPadding(0,dp(10),0,dp(10));
            FrameLayout poster=new FrameLayout(this);poster.setBackground(shape(0xFF303030,8));poster.setClipToOutline(true);
            ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);poster.addView(image,new FrameLayout.LayoutParams(-1,-1));
            TextView placeholder=text("画面待载入",10,MUTED,false);placeholder.setGravity(Gravity.CENTER);poster.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));
            LinearLayout.LayoutParams thumb=new LinearLayout.LayoutParams(dp(92),dp(56));thumb.rightMargin=dp(12);line.addView(poster,thumb);markerFrames.add(new MarkerFrame(image,placeholder,at));
            LinearLayout labels=column();TextView clock=text(preciseTime(at)+(end>at?" — "+preciseTime(end):""),13,ACCENT,true);clock.setMaxLines(2);labels.addView(clock);
            TextView label=text(name.isEmpty()?(end>at?"精彩片段":"精彩时刻"):name,14,WHITE,false);label.setMaxLines(1);label.setEllipsize(TextUtils.TruncateAt.END);add(labels,label,3,0);
            TextView seconds=text(seconds(at)+" 秒"+(end>at?" · 片长 "+seconds(end-at)+" 秒":" · 时间点"),11,MUTED,false);add(labels,seconds,3,0);
            line.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            Runnable jump=()->{
                if(player==null||missing){toast("原视频当前无法打开，标记仍保留");return;}
                player.seekTo(at);positionMs=at;savePosition();closeSheet();showHint("已跳到 "+preciseTime(at));
            };
            labels.setOnClickListener(v->jump.run());poster.setOnClickListener(v->jump.run());
            labels.setFocusable(true);labels.setContentDescription("跳转到 "+preciseTime(at)+(name.isEmpty()?"":", "+name));UiTheme.press(labels);UiTheme.press(poster);
            ImageButton more=new ImageButton(this);more.setImageResource(R.drawable.sp_player_more);more.setPadding(dp(10),dp(10),dp(10),dp(10));more.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            more.setContentDescription("管理 "+location+" 的精彩标记");more.setOnClickListener(v->markerMenu(marker));line.addView(more,new LinearLayout.LayoutParams(dp(48),dp(56)));markerList.addView(line);
            View divider=new View(this);divider.setBackgroundColor(0xFF363636);markerList.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
        }
        if(markers.size()>visibleMarkers)action(markerList,"显示更多标记",()->{visibleMarkers+=40;renderMarkers();});
        if(!markers.isEmpty())add(markerList,text("点画面或文字回到这个时刻",11,MUTED,false),12,4);
        markerList.post(this::bindVisibleMarkerFrames);
    }
    private static String seconds(long ms){return java.math.BigDecimal.valueOf(ms,3).stripTrailingZeros().toPlainString();}
    private void markerMenu(JSONObject marker) {
        String[] labels={"加入剪辑","重命名","删除标记","编辑时间与名称"};
        new PocketDialog.Builder(this,android.R.style.Theme_Material_Dialog_Alert).setTitle(markerLocation(marker)).setItems(labels,(dialog,which)->{
            if (which==0) openMarkerInEditor(marker);
            else if (which==1) {
                EditText input=new PocketEditText(this); styleInput(input); input.setText(marker.optString("label")); input.setSelectAllOnFocus(true); input.setHint("标记名称"); input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)});
                LinearLayout wrap=column(); wrap.setPadding(dp(24),dp(8),dp(24),0); wrap.addView(input,new LinearLayout.LayoutParams(-1,dp(48)));
                new PocketDialog.Builder(this,android.R.style.Theme_Material_Dialog_Alert).setTitle("重命名标记").setView(wrap).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{ String label=input.getText().toString().trim(); updateMarker(()->store.renameMarker(marker.optLong("id"),label)); }).show();
            } else if(which==2) new PocketDialog.Builder(this,android.R.style.Theme_Material_Dialog_Alert).setTitle("删除这个标记？").setMessage("只删除 "+markerLocation(marker)+" 的标记，原视频保持不变。").setNegativeButton("保留",null).setPositiveButton("删除标记",(d,w)->updateMarker(()->store.deleteMarker(marker.optLong("id")))).show();
            else editMarker(marker);
        }).show();
    }
    private void editMarker(JSONObject marker){
        LinearLayout fields=column();fields.setPadding(dp(24),dp(8),dp(24),0);
        EditText name=new PocketEditText(this),from=new PocketEditText(this),to=new PocketEditText(this);styleInput(name);styleInput(from);styleInput(to);
        name.setText(marker.optString("label"));name.setHint("标记名称");name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)});
        from.setText(preciseTime(markerStart(marker)));from.setSelectAllOnFocus(true);
        boolean range=marker.optLong("endMs")>markerStart(marker);if(range)to.setText(preciseTime(marker.optLong("endMs")));
        fields.addView(text("名称",12,MUTED,false));fields.addView(name);fields.addView(text(range?"起点（原片时间）":"时间点（原片时间）",12,MUTED,false));fields.addView(from);
        if(range){fields.addView(text("终点",12,MUTED,false));fields.addView(to);}
        TextView error=text("秒数或 分:秒，最多三位小数",12,MUTED,false);fields.addView(error);
        PocketDialog dialog=new PocketDialog.Builder(this,android.R.style.Theme_Material_Dialog_Alert).setTitle("编辑标记").setView(fields).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(PocketDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            final long start,end;try{start=parseTime(from.getText().toString());end=range?parseTime(to.getText().toString()):0;long total=playbackDuration();if(start>total||total<=0||range&&(end<=start||end>total))throw new IllegalArgumentException();}
            catch(IllegalArgumentException invalid){error.setText("请检查时间，不能超过原片时长。");return;}
            dialog.getButton(PocketDialog.BUTTON_POSITIVE).setEnabled(false);String label=name.getText().toString().trim();final long id=videoId;
            io.execute(()->{try{store.editMarker(id,marker.optLong("id"),start,end,label);List<JSONObject> saved=store.markers(id);main.post(()->{if(!destroyed&&videoId==id){markers=saved;renderMarkers();dialog.dismiss();}});}
            catch(RuntimeException failure){main.post(()->{if(!destroyed){error.setText("保存失败，输入已保留，请重试。");dialog.getButton(PocketDialog.BUTTON_POSITIVE).setEnabled(true);}});}});
        }));dialog.show();
    }
    private void updateMarker(Runnable operation) {
        final long id=videoId;
        io.execute(()->{ try { operation.run(); List<JSONObject> saved=store.markers(id); main.post(()->{ if (!destroyed&&videoId==id) { markers=saved; renderMarkers(); } }); }
            catch (RuntimeException failure) { main.post(()->{ if (!destroyed) toast("标记更新失败，请重试"); }); } });
    }
    private void openEditor() { if (player==null || missing) return; savePosition(); startActivity(new Intent(this,EditorActivity.class).putExtra("video_id",videoId).putExtra("position_ms",positionMs)); }
    private void openMarkerInEditor(JSONObject marker) {
        if (player==null || missing) { toast("原视频当前无法打开，暂时不能加入剪辑"); return; }
        savePosition(); long start=markerStart(marker),end=marker.optLong("endMs"); Intent intent=new Intent(this,EditorActivity.class).putExtra("video_id",videoId).putExtra("position_ms",start);
        if (end>start) intent.putExtra("start_ms",start).putExtra("end_ms",end); startActivity(intent);
    }
    private void savePosition() {
        if (player!=null) positionMs=Math.max(0,player.getCurrentPosition()); if (videoId<=0 || !loaded || missing || destroyed || lastQueuedPosition==positionMs) return;
        final long at=positionMs,id=videoId; lastQueuedPosition=at; io.execute(()->{ try { store.savePlayback(id,at); } catch (RuntimeException ignored) {} });
    }
    private void releasePlayer() { endPressBoost();
        main.removeCallbacks(checkpoint);main.removeCallbacks(videoStartupCheck); scrubbing=false; hidePreview(); if(previewLoader!=null) previewLoader.pausePreparation();
        if (transitionCover!=null && transitionCover.getDrawable()!=null) {transitionCover.animate().cancel();transitionCover.setAlpha(1f);transitionCover.setVisibility(View.VISIBLE);}
        if (player!=null) { savePosition(); playerView.setPlayer(null); ExoPlayer old=player; player=null; old.release(); }
        if (playbackResourceClaimed) { playbackResourceClaimed=false; MediaWorkload.playerStopped(); }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void setMediaActions(boolean enabled) {
        bookmarkButton.setEnabled(enabled && !bookmarkPending); saveRangeButton.setEnabled(enabled && !rangePending);
        if (clipButton!=null) clipButton.setEnabled(enabled); speedButton.setEnabled(enabled);
        // Saved highlights and information remain reachable even if the original disappeared.
        markerOptionsButton.setEnabled(true); fullscreenButton.setEnabled(true);
    }
    private void rotateVideo() {
        boolean landscape=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
        setRequestedOrientation(landscape?ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }
    private void immersive() {
        getWindow().setStatusBarColor(BLACK); getWindow().setNavigationBarColor(BLACK);
        if (Build.VERSION.SDK_INT>=29) getWindow().setNavigationBarContrastEnforced(false);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        if (Build.VERSION.SDK_INT>=30) {
            WindowInsetsController bars=getWindow().getInsetsController(); if (bars!=null) { bars.hide(WindowInsets.Type.systemBars()); bars.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); }
        }
    }
    @Override public void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); if(zoom!=null)zoom.reset();fullscreen=config.orientation==Configuration.ORIENTATION_LANDSCAPE; immersive(); if (!sheetMode.isEmpty()) {cancelSheetAnimation();canvasSheetDrag=false;recycleCanvasVelocity();layoutSheet();sheetBody.post(()->{if(!sheetMode.isEmpty()){setSheetOffset(0);bindVisibleMarkerFrames();}});} if (scrubbing) positionPreview(scrubPosition); }
    @Override protected void onStart() { super.onStart(); started=true; if(!splitReturning)ensurePlayer(); }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==74&&result==RESULT_OK&&data!=null&&data.getLongExtra("video_id",0)>0){
            releasePlayer();videoId=data.getLongExtra("video_id",videoId);getIntent().putExtra("video_id",videoId);
            positionMs=data.getLongExtra("position_ms",0);shouldPlay=data.getBooleanExtra("should_play",true);restoredPosition=true;splitReturning=false;
            loaded=false;mediaUri=null;zoom.reset();loadVideo();
        }
    }
    @Override protected void onResume() { super.onResume(); resumed=true; if(splitReturning){splitReturning=false;restoredPosition=false;loadVideo();}else ensurePlayer(); if (player!=null) player.setPlayWhenReady(shouldPlay); markerList.post(this::bindVisibleMarkerFrames);immersive(); }
    @Override protected void onPause() {
        nextRequest++;nextPending=false;
        cancelCanvasGesture();cancelSheetAnimation();if(!sheetMode.isEmpty())settleSheet(true,false);
        clearMarkerFrames();if (player!=null) { shouldPlay=player.getPlayWhenReady(); savePosition(); } resumed=false; releasePlayer(); super.onPause();
    }
    @Override protected void onStop() { started=false; releasePlayer(); super.onStop(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        if (player!=null) positionMs=Math.max(0,player.getCurrentPosition());state.putLong("video_id",videoId); state.putLong("position_ms",positionMs); state.putFloat("speed",speed);
        state.putBoolean("split_returning",splitReturning);state.putBoolean("fill_video",fillVideo); state.putBoolean("should_play",resumed && player!=null?player.getPlayWhenReady():shouldPlay); state.putBoolean("range_open","range".equals(sheetMode));
        state.putString("range_start",rangeStart.getText().toString()); state.putString("range_end",rangeEnd.getText().toString()); state.putString("range_name",rangeName.getText().toString()); super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() { cancelSheetAnimation();recycleCanvasVelocity();releasePlayer(); destroyed=true; loadGeneration++; coverGeneration++; releaseEnterTransition(); if (transitionCover!=null) {transitionCover.animate().cancel();transitionCover.setImageDrawable(null);} previewLoader.release(); markerThumbnails.release(); main.removeCallbacksAndMessages(null); io.execute(store::close); io.shutdown(); super.onDestroy(); }
    private void handleBack() { if (!sheetMode.isEmpty()) closeSheet(); else finishPlayback(); }
    @Override public void onBackPressed() { handleBack(); }

    private float validSpeed(float value) { for (float option:SPEEDS) if (option==value) return option; return 1f; }
    private static long markerStart(JSONObject marker) { return Math.max(0,marker.optLong("startMs",marker.optLong("positionMs"))); }
    private static String markerLocation(JSONObject marker) { long start=markerStart(marker),end=marker.optLong("endMs"); return time(start)+(end>start?"–"+time(end):""); }
    private static String preciseTime(long ms) { return time(ms)+String.format(Locale.ROOT,".%03d",Math.max(0,ms)%1000); }
    private static long parseTime(String value) {
        String clean=value.trim(); if (!clean.matches("\\d{1,7}(?::[0-5]?\\d){0,2}(?:\\.\\d{1,3})?")) throw new IllegalArgumentException("Invalid time");
        java.math.BigDecimal seconds=java.math.BigDecimal.ZERO; for (String part:clean.split(":")) seconds=seconds.multiply(java.math.BigDecimal.valueOf(60)).add(new java.math.BigDecimal(part));
        try { return seconds.multiply(java.math.BigDecimal.valueOf(1000)).longValueExact(); } catch (ArithmeticException invalid) { throw new IllegalArgumentException("Invalid time",invalid); }
    }
    private static String speedLabel(float value) { return new java.math.BigDecimal(Float.toString(value)).stripTrailingZeros().toPlainString()+"×"; }
    private static String time(long ms) { long total=Math.max(0,ms)/1000; return total>=3600?String.format(Locale.ROOT,"%d:%02d:%02d",total/3600,total/60%60,total%60):String.format(Locale.ROOT,"%02d:%02d",total/60,total%60); }
    private static String sourceLabel(String source) { if ("x".equals(source)) return "X / Twitter"; if ("bilibili".equals(source)) return "哔哩哔哩"; if ("douyin".equals(source)) return "抖音"; return "本地视频"; }
    private int dp(float value) { return (int)(getResources().getDisplayMetrics().density*value+.5f); }
    private LinearLayout column() { LinearLayout v=new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v=new LinearLayout(this); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private TextView text(String value,int size,int color,boolean bold) { TextView v=new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setFontFeatureSettings("tnum"); if (bold) v.setTypeface(Typeface.DEFAULT,Typeface.BOLD); return v; }
    private GradientDrawable shape(int color,int radius) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private Button button(String label,boolean primary){return PocketButton.dark(this,label,primary?PocketButton.Role.PRIMARY:PocketButton.Role.SECONDARY);}
    private Button action(LinearLayout parent,String label,Runnable run) { Button b=button(label,false); b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL); b.setOnClickListener(v->run.run()); parent.addView(b,new LinearLayout.LayoutParams(-1,dp(48))); return b; }
    private void add(LinearLayout parent,View child,int top,int bottom) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.topMargin=dp(top); p.bottomMargin=dp(bottom); parent.addView(child,p); }
    private void toast(String message) { PocketNotice.show(this,message); }
    private void hideKeyboard() { android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE); if (keyboard!=null) keyboard.hideSoftInputFromWindow(root.getWindowToken(),0); rangePanel.clearFocus(); }
    private final class ToolOverlay extends FrameLayout {
        private float downX,downY,startOffset;
        private boolean eligible,header,dragging,inside;
        private VelocityTracker velocity;
        ToolOverlay(){super(PlayerActivity.this);}
        @Override public boolean dispatchTouchEvent(MotionEvent event){
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){
                cancelSheetAnimation();downX=event.getX();downY=event.getY();startOffset=sheetOffset;dragging=false;
                float top=sheetBody.getTop()+sheetBody.getTranslationY();inside=downY>=top&&downX>=sheetBody.getLeft()+sheetBody.getTranslationX()&&downX<=sheetBody.getRight()+sheetBody.getTranslationX();
                header=inside&&downY<top+dp(78);eligible=inside&&(header||!"range".equals(sheetMode));
                recycle();velocity=VelocityTracker.obtain();
            }
            if(velocity!=null)velocity.addMovement(event);
            boolean wasDragging=dragging;boolean handled=super.dispatchTouchEvent(event);
            if(!wasDragging&&inside&&event.getActionMasked()==MotionEvent.ACTION_UP&&sheetAnimator==null&&!sheetMode.isEmpty()&&sheetOffset>0)settleSheet(true,true);
            if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL)recycle();
            return handled;
        }
        @Override public boolean onInterceptTouchEvent(MotionEvent event){
            if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&eligible){
                float dx=event.getX()-downX,dy=event.getY()-downY;
                if(sideDrawer()){if(dragging||Math.abs(dx)>ViewConfiguration.get(PlayerActivity.this).getScaledTouchSlop()&&dx>0&&Math.abs(dx)>Math.abs(dy)*1.3f){dragging=true;setSheetOffset(startOffset+dx);return true;}return false;}
                if(dragging||Math.abs(dy)>ViewConfiguration.get(PlayerActivity.this).getScaledTouchSlop()&&Math.abs(dy)>Math.abs(dx)*1.3f&&(header||dy>0&&!details.canScrollVertically(-1))){dragging=true;setSheetOffset(startOffset+dy);return true;}
            }
            return false;
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            if(event.getPointerCount()>1){if(dragging)settleSheet(true,true);dragging=false;eligible=false;return true;}
            if(dragging){
                if(event.getActionMasked()==MotionEvent.ACTION_MOVE)setSheetOffset(startOffset+(sideDrawer()?event.getX()-downX:event.getY()-downY));
                else if(event.getActionMasked()==MotionEvent.ACTION_UP){float vy=0;if(velocity!=null){velocity.computeCurrentVelocity(1000);vy=sideDrawer()?velocity.getXVelocity():velocity.getYVelocity();}settleSheet(!(vy>dp(650)||(vy>-dp(650)&&sheetOffset>sheetTravel()*.32f)),true);dragging=false;}
                else if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){settleSheet(true,true);dragging=false;}
                return true;
            }
            return super.onTouchEvent(event);
        }
        private void recycle(){if(velocity!=null){velocity.recycle();velocity=null;}}
        @Override protected void onDetachedFromWindow(){recycle();super.onDetachedFromWindow();}
    }

    private final class ToolScrollView extends ScrollView {
        int maximumHeight=Integer.MAX_VALUE;
        ToolScrollView() { super(PlayerActivity.this); }
        @Override protected void onMeasure(int width,int height) {
            int available=View.MeasureSpec.getMode(height)==View.MeasureSpec.UNSPECIFIED?maximumHeight:Math.min(maximumHeight,View.MeasureSpec.getSize(height));
            super.onMeasure(width,View.MeasureSpec.makeMeasureSpec(available,View.MeasureSpec.AT_MOST));
        }
    }
}
