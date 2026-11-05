package dev.streampocket;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local catalogue browser. The host supplies the application header and navigation. */
public final class VideoLibraryView extends LinearLayout {
    private final int INK, MUTED, BORDER;
    private final UiTheme.Palette palette;
    private static final int PAGE = 30;
    private final Activity activity;
    private final LibraryStore store;
    private final ThumbnailRepository covers;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "video-library"));
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<JSONObject> videos = new ArrayList<>();
    private final List<JSONObject> collections = new ArrayList<>();
    private final VideoAdapter adapter = new VideoAdapter();
    private final GridView grid;
    private final TextView status, watchButton, sourceButton, collectionButton, headingTitle, selectionCount, shareSelection, collectSelection, moreSelection;
    private final LinearLayout selectionBar;
    private final View searchToggle, viewToggle;
    private final TextView clearFilters;
    private final Set<String> selectedSources=new LinkedHashSet<>();
    private final EditText search;
    private boolean released, loading, more = true, selectionMode, writing;
    private boolean listMode, favoritesOnly;
    private int gridColumns;
    private String order = "date";
    private final Set<Long> selectedIds = new LinkedHashSet<>();
    private int restoreFirst = -1, restoreTop;
    private android.view.ViewTreeObserver.OnGlobalLayoutListener viewportRestore;
    private int generation;
    private String source = "", query = "", watchFilter = LibraryWatchState.ALL;
    private long collection, totalVideos;
    private final Runnable searchChanged = this::applySearch;
    private void applySearch() { query = search.getText().toString(); endSelection(); restoreFirst=-1; load(true); }

    public VideoLibraryView(Activity host) {
        super(host); activity = host; palette = UiTheme.get(host); INK=palette.ink; MUTED=palette.muted; BORDER=palette.line;
        store = new LibraryStore(host); covers = new ThumbnailRepository(host);
        listMode = "list".equals(host.getSharedPreferences("config",0).getString("library_view","grid"));
        gridColumns=host.getSharedPreferences("config",0).getInt("library_columns",2)==3?3:2;
        order = "source".equals(host.getSharedPreferences("config",0).getString("library_order","date")) ? "source" : "date";
        setOrientation(VERTICAL); setBackgroundColor(palette.bg); setPadding(dp(20), dp(8), dp(20), 0);
        LinearLayout heading = new LinearLayout(host); heading.setGravity(Gravity.CENTER_VERTICAL);
        headingTitle = text("视频库", 22, INK, true);
        heading.addView(headingTitle, new LinearLayout.LayoutParams(0, dp(56), 1));
        searchToggle = iconControl("search", "搜索视频"); searchToggle.setOnClickListener(v -> toggleSearch());
        heading.addView(searchToggle, new LinearLayout.LayoutParams(dp(48), dp(48)));
        viewToggle = iconControl("grid", "选择视频库视图");
        viewToggle.setOnClickListener(v -> chooseViewMode()); heading.addView(viewToggle, new LinearLayout.LayoutParams(dp(48), dp(48)));
        View menu = iconControl("sort", "视频库更多操作"); menu.setOnClickListener(v -> showLibraryMenu());
        heading.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48))); addView(heading);

        search = new PocketEditText(host); search.setSingleLine(true); search.setTextSize(14); search.setTextColor(INK); search.setHintTextColor(MUTED);
        search.setHint("搜索标题或作者"); search.setContentDescription("搜索视频标题或作者");
        search.setPadding(dp(14), 0, dp(14), 0); search.setBackground(shape(palette.surface, false, 12)); search.setVisibility(GONE);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(48)); searchParams.bottomMargin = dp(8); addView(search, searchParams);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { main.removeCallbacks(searchChanged); main.postDelayed(searchChanged, 280); }
            @Override public void afterTextChanged(Editable value) {}
        });

        HorizontalScrollView filterScroll=new HorizontalScrollView(host); filterScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout filters = new LinearLayout(host); filters.setGravity(Gravity.CENTER_VERTICAL);
        watchButton=filterChip("全部视频","watch");watchButton.setContentDescription("按观看状态和收藏筛选视频");
        sourceButton = filterChip("全部来源","source"); collectionButton = filterChip("全部合集","collection");
        sourceButton.setContentDescription("按来源筛选视频"); collectionButton.setContentDescription("按合集筛选视频");
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(-2, dp(48)); left.rightMargin = dp(8);
        filters.addView(watchButton,new LinearLayout.LayoutParams(left));filters.addView(sourceButton, left); filters.addView(collectionButton, new LinearLayout.LayoutParams(-2, dp(48)));
        clearFilters=control("清除",false);clearFilters.setBackgroundColor(Color.TRANSPARENT);clearFilters.setTextColor(palette.accent);clearFilters.setVisibility(GONE);clearFilters.setContentDescription("清除视频库筛选条件");clearFilters.setOnClickListener(v->{source="";selectedSources.clear();collection=0;watchFilter=LibraryWatchState.ALL;favoritesOnly=false;endSelection();restoreFirst=-1;updateCollectionLabel();load(true);});filters.addView(clearFilters,new LinearLayout.LayoutParams(dp(48),dp(48)));
        filterScroll.addView(filters); addView(filterScroll, new LinearLayout.LayoutParams(-1, dp(48)));
        watchButton.setOnClickListener(v->chooseWatchFilter());sourceButton.setOnClickListener(v -> chooseSource()); collectionButton.setOnClickListener(v -> chooseCollection());

        status = text("正在整理本地视频…", 12, MUTED, false); status.setPadding(0, dp(4), 0, dp(8)); status.setMinHeight(dp(32)); addView(status, new LinearLayout.LayoutParams(-1, -2));
        selectionBar=new LinearLayout(host); selectionBar.setGravity(Gravity.CENTER_VERTICAL); selectionBar.setBackground(shape(palette.accentSoft,false,12)); selectionBar.setPadding(dp(6),0,dp(6),0); selectionBar.setVisibility(GONE);
        TextView close=control("退出",false); close.setBackgroundColor(Color.TRANSPARENT); close.setOnClickListener(v -> endSelection()); selectionBar.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48)));
        selectionCount=text("已选 0 项",12,INK,true); selectionBar.addView(selectionCount,new LinearLayout.LayoutParams(0,dp(48),1));
        shareSelection=selectionAction("分享",this::shareSelected); collectSelection=selectionAction("合集",this::collectSelected); moreSelection=selectionAction("更多",this::showSelectionMore);
        moreSelection.setContentDescription("所选视频更多整理操作");
        addView(selectionBar,new LinearLayout.LayoutParams(-1,dp(48)));

        grid = new GridView(host); configureColumns(); grid.setVerticalSpacing(dp(18)); grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setClipToPadding(false); grid.setPadding(0, dp(4), 0, dp(20)); grid.setSelector(android.R.color.transparent); grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            if(position<0||position>=videos.size())return;
            activateVideo(videos.get(position),view);
        });
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            if(position<0||position>=videos.size())return false;
            selectVideo(videos.get(position)); return true;
        });
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int scrollState) {}
            @Override public void onScroll(AbsListView view, int firstVisible, int visible, int total) { if (total > 0 && firstVisible + visible >= total - 4 && more && !loading) load(false); }
        });
        grid.setRecyclerListener(view -> { Holder h = (Holder) view.getTag(); covers.cancel(h.cover); });
        addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void configureColumns() {
        int columns=listMode?1:gridColumns==3?3:getResources().getConfiguration().screenWidthDp<340 || getResources().getConfiguration().fontScale>=1.35f?1:2;
        grid.setNumColumns(columns);grid.setHorizontalSpacing(dp(columns==3?8:12));
    }
    private void chooseViewMode(){new PocketDialog.Builder(activity).setTitle("视频库视图").setSingleChoiceItems(new String[]{"双列网格","三列网格","详细列表"},listMode?2:gridColumns==3?1:0,(dialog,which)->{if(which<2)gridColumns=which==1?3:2;setListMode(which==2);dialog.dismiss();}).setNegativeButton("返回",null).show();}
    private void toggleSearch() {
        boolean reveal=search.getVisibility()!=VISIBLE; search.setVisibility(reveal?VISIBLE:GONE);
        if(reveal){UiTheme.enter(search);search.requestFocus();}
        else {search.clearFocus(); android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(search.getWindowToken(),0);}
    }
    private void setListMode(boolean list) {
        listMode=list; activity.getSharedPreferences("config",0).edit().putString("library_view",list?"list":"grid").putInt("library_columns",gridColumns).apply();
        viewToggle.setContentDescription("选择视频库视图"); ((ImageView)viewToggle).setImageDrawable(new PocketIcon(list?"list":"grid",INK));
        int first=grid.getFirstVisiblePosition(); configureColumns(); adapter.notifyDataSetChanged(); grid.setSelection(first);
    }
    private void showLibraryMenu() {
        String[] actions={"剪辑草稿", "按日期排序", "按来源排序", selectionMode?"全选已加载的视频":"多选整理", "管理合集"};
        new PocketDialog.Builder(activity).setTitle("视频库").setItems(actions,(dialog,which)->{
            if(which==0)openEditor(0);
            else if(which==1||which==2){endSelection();order=which==2?"source":"date";activity.getSharedPreferences("config",0).edit().putString("library_order",order).apply();load(true);}
            else if(which==4)showCollectionManager();
            else if(selectionMode){for(JSONObject video:videos)selectedIds.add(video.optLong("id"));updateSelection();adapter.notifyDataSetChanged();}
            else {selectionMode=true;updateSelection();}
        }).show();
    }

    /** The host can retain browsing state across tab changes and theme recreation. */
    public Bundle saveUiState() {
        Bundle state=new Bundle(); state.putString("query",search.getText().toString()); state.putString("source",source); state.putLong("collection",collection);
        state.putString("watch_filter",watchFilter);state.putBoolean("favorites_only",favoritesOnly);
        state.putStringArrayList("sources",new ArrayList<>(selectedSources));state.putString("order",order);state.putInt("columns",gridColumns); state.putBoolean("list",listMode); state.putBoolean("search_open",search.getVisibility()==VISIBLE);
        state.putInt("first",grid.getFirstVisiblePosition()); state.putInt("top",grid.getChildCount()>0?grid.getChildAt(0).getTop():0); return state;
    }
    public void restoreUiState(Bundle state) {
        if(state==null||released)return; main.removeCallbacks(searchChanged); query=state.getString("query",""); source=state.getString("source","");selectedSources.clear();if(state.getStringArrayList("sources")!=null)selectedSources.addAll(state.getStringArrayList("sources"));else if(!source.isEmpty())selectedSources.add(source);collection=state.getLong("collection",0);
        watchFilter=LibraryWatchState.normalizeFilter(state.getString("watch_filter"));favoritesOnly=state.getBoolean("favorites_only",false);
        order="source".equals(state.getString("order"))?"source":"date";gridColumns=state.getInt("columns",gridColumns)==3?3:2; listMode=state.getBoolean("list",listMode); configureColumns();
        search.setText(query); main.removeCallbacks(searchChanged); search.setVisibility(state.getBoolean("search_open")||!query.isEmpty()?VISIBLE:GONE);
        updateCollectionLabel();
        restoreFirst=Math.max(0,state.getInt("first",0));restoreTop=state.getInt("top",0);
        viewToggle.setContentDescription("选择视频库视图"); ((ImageView)viewToggle).setImageDrawable(new PocketIcon(listMode?"list":"grid",INK));
    }

    public void refresh() { if (!released && !loading && !writing) load(true, true); }

    private void load(boolean reset) {
        load(reset, false);
    }
    private void load(boolean reset, boolean preserveViewport) {
        if (released || (!reset && (loading || !more))) return;
        int token = reset ? ++generation : generation; int offset = reset ? 0 : videos.size();
        List<String> chosenSources=new ArrayList<>(selectedSources);String chosenQuery = query, chosenOrder=order; long chosenCollection = collection,knownCount=totalVideos;
        String chosenWatch=watchFilter;boolean chosenFavorites=favoritesOnly;
        int cachedCount = preserveViewport ? Math.max(PAGE, videos.size()) : PAGE;
        // A pending deep restore needs its viewport in the first adapter update;
        // publishing earlier pages would first establish a viewport at row zero.
        int requestCount = reset && restoreFirst>=0 ? Math.max(cachedCount,restoreFirst+PAGE) : cachedCount;
        int firstVisible = preserveViewport ? grid.getFirstVisiblePosition() : 0;
        int firstTop = preserveViewport && grid.getChildCount() > 0 ? grid.getChildAt(0).getTop() : 0;
        loading = true; status.setOnClickListener(null);
        if (!preserveViewport || videos.isEmpty()) status.setText(reset ? "正在读取本地视频…" : "正在加载更多…");
        io.execute(() -> {
            try {
                if (reset) { store.recoverInterruptedExports(); store.syncCompletedDownloads(); }
                // The store caps each query at 100. Refresh the complete cached
                // range so an unchanged deep viewport can keep its existing rows.
                List<JSONObject> page = new ArrayList<>();
                while(page.size()<requestCount){
                    int chunkSize=Math.min(100,requestCount-page.size());
                    List<JSONObject> chunk=store.listVideosFromSources(chosenQuery,chosenSources,chosenCollection,chunkSize,offset+page.size(),chosenOrder,chosenWatch,chosenFavorites);
                    page.addAll(chunk);if(chunk.size()<chunkSize)break;
                }
                long count=reset?store.countVideosFromSources(chosenQuery,chosenSources,chosenCollection,chosenWatch,chosenFavorites):knownCount;
                List<JSONObject> groups = reset ? store.collections() : null;
                main.post(() -> {
                    if (released || token != generation) return;
                    loading = false;
                    boolean sameCount=totalVideos==count;totalVideos=count;
                    if (reset) { collections.clear(); collections.addAll(groups); updateCollectionLabel(); }
                    if (preserveViewport && sameCount && samePrefix(page)) {updateStatus();return;}
                    if (reset) videos.clear();
                    videos.addAll(page); adapter.notifyDataSetChanged();
                    more=page.size()==requestCount&&videos.size()<totalVideos;
                    if(reset&&preserveViewport&&restoreFirst<0){restoreFirst=firstVisible;restoreTop=firstTop;}
                    if(videos.isEmpty())restoreFirst=-1;
                    else if(restoreFirst>=0){
                        if(restoreFirst>=videos.size()&&more){load(false);return;}
                        restoreViewport(Math.min(restoreFirst,videos.size()-1),restoreTop);restoreFirst=-1;
                    }else if (reset && preserveViewport) restoreViewport(Math.min(firstVisible,videos.size()-1),firstTop);
                    else if (reset) grid.setSelection(0);
                    updateStatus();
                });
            } catch (Exception failure) {
                main.post(() -> { if (!released && token == generation) { loading = false; more = false; status.setText("视频库读取失败，点击重试"); status.setOnClickListener(v -> { status.setOnClickListener(null); load(true); }); } });
            }
        });
    }
    private void updateStatus() {
        if(!videos.isEmpty()){status.setText("共 "+totalVideos+" 个视频 · "+("source".equals(order)?"按来源":"按日期"));return;}
        if(!query.isEmpty()||!selectedSources.isEmpty()||collection>0)status.setText("没有符合筛选条件的视频");
        else if(favoritesOnly)status.setText("还没有符合条件的收藏，可在视频更多操作中收藏。");
        else if(LibraryWatchState.CONTINUE.equals(watchFilter))status.setText("播放过且未看完的视频会出现在这里。");
        else if(!LibraryWatchState.ALL.equals(watchFilter))status.setText("没有符合观看状态的视频");
        else status.setText("下载完成的视频会出现在这里。");
    }
    private void reloadAfterWrite() {
        restoreFirst=grid.getFirstVisiblePosition();restoreTop=grid.getChildCount()>0?grid.getChildAt(0).getTop():0;
        load(true);
    }
    private void clearViewportRestore() {
        if(viewportRestore!=null&&grid.getViewTreeObserver().isAlive())grid.getViewTreeObserver().removeOnGlobalLayoutListener(viewportRestore);
        viewportRestore=null;
    }
    private void restoreViewport(int position,int top) {
        clearViewportRestore();
        if(released||position<0||position>=videos.size())return;
        // GridView's supported selection layout establishes its row before an
        // offset is applied. The inherited setSelectionFromTop can enter a
        // specific-position layout with selection=-1 on a newly attached grid.
        grid.setSelection(position);
        if(position==0&&top==0)return;
        final int token=generation;
        viewportRestore=()->{
            if(released||token!=generation){clearViewportRestore();return;}
            int index=position-grid.getFirstVisiblePosition();
            if(index<0||index>=grid.getChildCount())return;
            View row=grid.getChildAt(index);int distance=row.getTop()-top;
            clearViewportRestore();if(distance!=0)grid.scrollListBy(distance);
        };
        grid.getViewTreeObserver().addOnGlobalLayoutListener(viewportRestore);
    }
    private boolean samePrefix(List<JSONObject> page) {
        // A capped refresh cannot prove that the unqueried suffix is unchanged:
        // playback and favorite updates may leave the count and first 100 intact.
        if (page.isEmpty() || videos.size() != page.size()) return false;
        for (int i = 0; i < page.size(); i++) if (!videos.get(i).toString().equals(page.get(i).toString())) return false;
        return true;
    }

    private void chooseWatchFilter() {
        String[] values={LibraryWatchState.ALL,LibraryWatchState.CONTINUE,LibraryWatchState.UNWATCHED,LibraryWatchState.WATCHED};
        int selected=0;for(int i=0;i<values.length;i++)if(values[i].equals(watchFilter))selected=i;
        new PocketDialog.Builder(activity).setTitle("观看与收藏")
                .setMessage(favoritesOnly?"当前只看收藏，可同时选择观看状态。":"选择观看状态，也可以只看收藏。")
                .setSingleChoiceItems(new String[]{"全部视频","继续观看","未看","已看完"},selected,(dialog,which)->{
                    watchFilter=values[which];endSelection();restoreFirst=-1;updateCollectionLabel();dialog.dismiss();load(true);
                }).setNeutralButton(favoritesOnly?"取消收藏筛选":"只看收藏",(dialog,which)->{
                    favoritesOnly=!favoritesOnly;endSelection();restoreFirst=-1;updateCollectionLabel();load(true);
                }).setNegativeButton("返回",null).show();
    }
    private static String watchLabel(String filter) {
        switch(LibraryWatchState.normalizeFilter(filter)){
            case LibraryWatchState.CONTINUE:return "继续观看";
            case LibraryWatchState.UNWATCHED:return "未看";
            case LibraryWatchState.WATCHED:return "已看完";
            default:return "全部视频";
        }
    }
    private void chooseSource() {
        String[] labels={"X","哔哩哔哩","抖音","YouTube","小红书","快手","TikTok","Instagram","Facebook","Pinterest","Pornhub","小黑盒","其他平台","我的导出"},values={"x","bilibili","douyin","youtube","xiaohongshu","kuaishou","tiktok","instagram","facebook","pinterest","pornhub","heybox","other","export"};boolean[] checked=new boolean[values.length];
        for(int n=0;n<values.length;n++)checked[n]=selectedSources.contains(values[n]);
        new PocketDialog.Builder(activity).setTitle("选择视频来源").setMultiChoiceItems(labels,checked,(dialog,at,value)->checked[at]=value)
                .setPositiveButton("应用筛选",(dialog,which)->{selectedSources.clear();for(int n=0;n<values.length;n++)if(checked[n])selectedSources.add(values[n]);source=selectedSources.size()==1?selectedSources.iterator().next():"";endSelection();restoreFirst=-1;updateCollectionLabel();load(true);})
                .setNeutralButton("全部来源",(dialog,which)->{selectedSources.clear();source="";endSelection();restoreFirst=-1;updateCollectionLabel();load(true);}).setNegativeButton("取消",null).show();
    }
    private void chooseCollection() {
        String[] labels = new String[collections.size() + 1]; labels[0] = "全部视频"; int selected = 0;
        for (int i = 0; i < collections.size(); i++) { JSONObject group = collections.get(i); labels[i + 1] = group.optString("name") + " · " + group.optLong("count"); if (group.optLong("id") == collection) selected = i + 1; }
        PocketDialog.Builder chooser=new PocketDialog.Builder(activity).setTitle("我的合集").setSingleChoiceItems(labels, selected, (dialog, which) -> {
            endSelection(); restoreFirst=-1; collection = which == 0 ? 0 : collections.get(which - 1).optLong("id");
            updateCollectionLabel(); dialog.dismiss(); load(true);
        }).setPositiveButton("新建合集", (dialog, which) -> createCollection(null)).setNegativeButton("取消", null);
        if(!collections.isEmpty())chooser.setNeutralButton("管理合集",(dialog,which)->showCollectionManager());
        chooser.show();
    }

    private void activateVideo(JSONObject video,View cell) {
        if(released||writing)return;
        if(selectionMode)toggleSelected(video);else openPlayer(video,cell);
    }
    private void selectVideo(JSONObject video) {
        if(released||writing)return;selectionMode=true;toggleSelected(video);
    }
    private void openPlayer(JSONObject video) { openPlayer(video,null); }
    private void openPlayer(JSONObject video,View cell) {
        long id=video.optLong("id");if(id<=0){message("视频记录不存在，请刷新视频库。");return;}
        try {
            // The player re-reads this stable ID and its current local URI/access.
            // A stale catalogue 'missing' snapshot must not block a restored file.
            Intent intent=new Intent(activity,PlayerActivity.class).putExtra("video_id",id).putExtra("library_query",query).putExtra("library_collection",collection).putExtra("library_order",order).putStringArrayListExtra("library_sources",new ArrayList<>(selectedSources))
                    .putExtra("library_watch_filter",watchFilter).putExtra("library_favorites_only",favoritesOnly);
            // Match the download entry. A window-owned shared-element snapshot
            // must never sit above the independent video surface.
            activity.startActivity(intent);
        }catch (Exception unavailable) { message("暂时无法打开播放器，请重试。"); }
    }

    private void openEditor(long videoId) {
        try { Intent intent = new Intent().setClassName(activity, "dev.streampocket.EditorActivity"); if (videoId > 0) intent.putExtra("video_id", videoId); activity.startActivity(intent); }
        catch (Exception unavailable) { message("暂时无法打开剪辑，请重试。"); }
    }

    private void showActions(JSONObject video) {
        boolean favorite=video.optBoolean("favorite");
        String[] actions = {"播放", favorite?"取消收藏":"收藏视频", "添加到剪辑", "重命名文件", "管理所属合集", "分享视频", "从视频库移除"};
        new PocketDialog.Builder(activity).setTitle(video.optString("title", "视频")).setItems(actions, (dialog, which) -> {
            if (which == 0) openPlayer(video);
            else if(which==1)runWrite(()->store.setFavorite(video.optLong("id"),!favorite),favorite?"已取消收藏":"已收藏");
            else if (which == 2) { if (video.optBoolean("missing")) message("原视频无法读取，请先恢复文件。"); else openEditor(video.optLong("id")); }
            else if (which == 3) rename(video);
            else if (which == 4) manageCollections(video);
            else if (which == 5) share(video);
            else new PocketDialog.Builder(activity).setTitle("从视频库移除？").setMessage("只移除库记录、精彩标记与合集归属。手机上的视频文件和已有剪辑草稿会保留。")
                    .setNegativeButton("取消", null).setPositiveButton("移除", (confirm, action) -> runWrite(() -> store.removeVideoRecord(video.optLong("id")), "已从视频库移除")).show();
        }).show();
    }
    private void rename(JSONObject video) {
        if (video.optBoolean("missing")) { message("原视频无法读取，请先恢复文件。"); return; }
        String fileName = video.optString("fileName", ""); String extension = LibraryStore.extension(fileName);
        String base = fileName.isEmpty() ? video.optString("title", "") : fileName.substring(0, fileName.length() - extension.length());
        LinearLayout form = new LinearLayout(activity); form.setOrientation(VERTICAL); form.setPadding(dp(24), dp(8), dp(24), dp(8));
        EditText input = new PocketEditText(activity); input.setSingleLine(true); input.setTextSize(16); input.setText(base); input.setSelection(input.length()); input.setHint("文件名称"); input.setContentDescription("新文件名称，不含扩展名");
        form.addView(input, new LinearLayout.LayoutParams(-1, dp(52)));
        TextView caption = text(extension.isEmpty() ? "保留原文件扩展名，精彩标记不受影响。" : "保留 " + extension + " 扩展名，精彩标记不受影响。", 13, MUTED, false);
        caption.setPadding(0, dp(8), 0, dp(4)); form.addView(caption, new LinearLayout.LayoutParams(-1, -2));
        PocketDialog dialog = new PocketDialog.Builder(activity).setTitle("重命名手机里的文件").setView(form).setPositiveButton("保存", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(PocketDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (released) { dialog.dismiss(); return; }
            String requested;
            try { requested = LibraryStore.validFileBaseName(input.getText().toString()); }
            catch (IllegalArgumentException invalid) { input.setError(invalid.getMessage()); return; }
            input.setError(null); input.setEnabled(false); dialog.setCancelable(false); dialog.getButton(PocketDialog.BUTTON_POSITIVE).setEnabled(false); dialog.getButton(PocketDialog.BUTTON_NEGATIVE).setEnabled(false);
            dialog.getButton(PocketDialog.BUTTON_POSITIVE).setText("正在保存…");
            io.execute(() -> {
                try {
                    store.renameVideo(video.optLong("id"), requested);
                    main.post(() -> { dialog.dismiss(); if (!released) { message("文件已重命名"); load(true); } });
                } catch (Exception failure) {
                    main.post(() -> {
                        if (released || !dialog.isShowing()) return;
                        input.setEnabled(true); input.setError(failure.getMessage() == null ? "重命名失败，请重试。" : failure.getMessage());
                        dialog.setCancelable(true); dialog.getButton(PocketDialog.BUTTON_POSITIVE).setEnabled(true); dialog.getButton(PocketDialog.BUTTON_NEGATIVE).setEnabled(true); dialog.getButton(PocketDialog.BUTTON_POSITIVE).setText("保存");
                    });
                }
            });
        })); dialog.show();
    }
    private void share(JSONObject video) {
        if (video.optBoolean("missing")) { message("原视频无法读取，暂时不能分享。"); return; }
        try {
            Uri uri = Uri.parse(video.optString("uri")); Intent send = new Intent(Intent.ACTION_SEND).setType(video.optString("mime", "video/mp4"));
            send.putExtra(Intent.EXTRA_STREAM, uri); send.setClipData(ClipData.newRawUri("视频", uri)); send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(send, "分享视频"));
        } catch (Exception unavailable) { message("没有可用的分享方式，或文件无法读取。"); }
    }

    private void manageCollections(JSONObject video) {
        io.execute(() -> {
            try {
                List<JSONObject> groups = store.collections(); List<Long> included = store.collectionIds(video.optLong("id"));
                main.post(() -> {
                    if (released) return;
                    if (groups.isEmpty()) { createCollection(video); return; }
                    String[] labels = new String[groups.size()]; boolean[] checks = new boolean[groups.size()];
                    for (int i = 0; i < groups.size(); i++) { labels[i] = groups.get(i).optString("name"); checks[i] = included.contains(groups.get(i).optLong("id")); }
                    new PocketDialog.Builder(activity).setTitle("整理到合集").setMultiChoiceItems(labels, checks, (dialog, which, value) -> checks[which] = value)
                            .setPositiveButton("保存", (dialog, which) -> runWrite(() -> { for (int i = 0; i < groups.size(); i++) store.setCollection(video.optLong("id"), groups.get(i).optLong("id"), checks[i]); }, "合集已更新"))
                            .setNeutralButton("新建合集", (dialog, which) -> createCollection(video)).setNegativeButton("取消", null).show();
                });
            } catch (Exception failure) { main.post(() -> message("无法读取合集，请重试。")); }
        });
    }
    private void createCollection(JSONObject video) {
        EditText input = new PocketEditText(activity); input.setSingleLine(true); input.setTextSize(16); input.setHint("合集名称"); input.setPadding(dp(24), dp(12), dp(24), dp(12));
        PocketDialog dialog = new PocketDialog.Builder(activity).setTitle("新建合集").setView(input).setPositiveButton("创建", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(PocketDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim(); if (name.isEmpty()) { input.setError("请输入合集名称"); return; }
            dialog.dismiss(); runWrite(() -> { long id = store.createCollection(name); if (video != null) store.setCollection(video.optLong("id"), id, true); }, "合集已创建");
        })); dialog.show();
    }
    private void showCollectionManager() {
        if(released)return;
        io.execute(()->{try{
            List<JSONObject> groups=store.collections();
            main.post(()->{
                if(released)return;
                if(groups.isEmpty()){createCollection(null);return;}
                String[] names=new String[groups.size()];for(int i=0;i<groups.size();i++)names[i]=groups.get(i).optString("name")+" · "+groups.get(i).optLong("count");
                new PocketDialog.Builder(activity).setTitle("管理合集").setItems(names,(dialog,which)->{
                    JSONObject group=groups.get(which);
                    new PocketDialog.Builder(activity).setTitle(group.optString("name")).setItems(new String[]{"重命名合集","删除合集"},(options,action)->{
                        if(action==0)renameCollection(group);else deleteCollection(group);
                    }).setNegativeButton("返回",null).show();
                }).setPositiveButton("新建合集",(dialog,which)->createCollection(null)).setNegativeButton("返回",null).show();
            });
        }catch(Exception failure){main.post(()->message("无法读取合集，请重试。"));}});
    }
    private void renameCollection(JSONObject group) {
        EditText input=new PocketEditText(activity);input.setSingleLine(true);input.setText(group.optString("name"));input.setSelection(input.length());input.setHint("合集名称");input.setContentDescription("新的合集名称");input.setPadding(dp(20),dp(12),dp(20),dp(12));
        PocketDialog dialog=new PocketDialog.Builder(activity).setTitle("重命名合集").setView(input).setPositiveButton("保存",null).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(PocketDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(released){dialog.dismiss();return;}
            String name=input.getText().toString().trim();if(name.isEmpty()||name.length()>80){input.setError("合集名称需为 1–80 个字符");return;}
            input.setError(null);input.setEnabled(false);dialog.setCancelable(false);dialog.getButton(PocketDialog.BUTTON_POSITIVE).setEnabled(false);dialog.getButton(PocketDialog.BUTTON_NEGATIVE).setEnabled(false);
            io.execute(()->{try{
                store.renameCollection(group.optLong("id"),name);
                main.post(()->{dialog.dismiss();if(!released){message("合集已重命名");reloadAfterWrite();}});
            }catch(Exception failure){main.post(()->{if(released||!dialog.isShowing())return;input.setEnabled(true);input.setError(failure.getMessage()==null?"保存失败，请重试。":failure.getMessage());dialog.setCancelable(true);dialog.getButton(PocketDialog.BUTTON_POSITIVE).setEnabled(true);dialog.getButton(PocketDialog.BUTTON_NEGATIVE).setEnabled(true);});}});
        }));dialog.show();
    }
    private void deleteCollection(JSONObject group) {
        long id=group.optLong("id");
        new PocketDialog.Builder(activity).setTitle("删除合集？")
                .setMessage("删除「"+group.optString("name")+"」后，其中的 "+group.optLong("count")+" 个视频仍保留在视频库，观看进度、精彩标记和剪辑草稿不变。")
                .setNegativeButton("取消",null).setPositiveButton("删除合集",(dialog,which)->runWrite(()->store.deleteCollection(id),"合集已删除，视频保留",()->{
                    if(collection==id)collection=0;endSelection();updateCollectionLabel();
                })).show();
    }
    private void runWrite(Runnable action, String success) {runWrite(action,success,null);}
    private void runWrite(Runnable action, String success,Runnable after) {
        if (released) return;
        io.execute(() -> { try { action.run(); main.post(() -> { if (!released) { if(after!=null)after.run();message(success); reloadAfterWrite(); } }); } catch (Exception failure) { main.post(() -> message("保存失败，请重试。")); } });
    }

    private void updateCollectionLabel() {
        String name="全部";
        for(JSONObject group:collections)if(group.optLong("id")==collection){name=group.optString("name");break;}
        collectionButton.setText(collection==0?"全部合集":name);
        watchButton.setText(watchLabel(watchFilter)+(favoritesOnly?" · 收藏":""));
        watchButton.setTextColor(LibraryWatchState.ALL.equals(watchFilter)&&!favoritesOnly?MUTED:palette.accent);
        sourceButton.setText(selectedSources.isEmpty()?"全部来源":selectedSources.size()==1?sourceLabel(selectedSources.iterator().next()):selectedSources.size()+" 个来源");clearFilters.setVisibility(selectedSources.isEmpty()&&collection==0&&LibraryWatchState.ALL.equals(watchFilter)&&!favoritesOnly?GONE:VISIBLE);
        sourceButton.setTextColor(selectedSources.isEmpty()?INK:palette.accent); collectionButton.setTextColor(collection==0?MUTED:palette.accent);
    }
    private TextView selectionAction(String label,Runnable action) {
        TextView button=control(label,false);button.setTextSize(13);button.setBackgroundColor(Color.TRANSPARENT);button.setOnClickListener(v->{if(!writing&&!selectedIds.isEmpty())action.run();});
        selectionBar.addView(button,new LinearLayout.LayoutParams(dp(48),dp(48)));return button;
    }
    private void toggleSelected(JSONObject video) {
        if(writing)return;long id=video.optLong("id");if(!selectedIds.remove(id))selectedIds.add(id);updateSelection();adapter.notifyDataSetChanged();
    }
    private void endSelection() {
        if(writing)return;selectionMode=false;selectedIds.clear();updateSelection();adapter.notifyDataSetChanged();
    }
    private void updateSelection() {
        headingTitle.setText(selectionMode?"整理视频":"视频库");selectionBar.setVisibility(selectionMode?VISIBLE:GONE);
        selectionCount.setText("已选 "+selectedIds.size()+" 项");boolean enabled=!writing&&!selectedIds.isEmpty();
        for(TextView button:new TextView[]{shareSelection,collectSelection,moreSelection})if(button!=null){button.setEnabled(enabled);button.setAlpha(enabled?1f:.38f);}
    }
    private List<JSONObject> selectedVideos() {
        List<JSONObject> result=new ArrayList<>();for(JSONObject video:videos)if(selectedIds.contains(video.optLong("id")))result.add(video);return result;
    }
    private void shareSelected() {
        List<JSONObject> selected=selectedVideos();if(selected.isEmpty())return;ArrayList<Uri> uris=new ArrayList<>();
        for(JSONObject video:selected){if(video.optBoolean("missing")){message("选择中包含不可用的文件，请先取消选择该视频。");return;}uris.add(Uri.parse(video.optString("uri")));}
        try{
            Intent send=new Intent(Intent.ACTION_SEND_MULTIPLE).setType("video/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ClipData clip=ClipData.newRawUri("视频",uris.get(0));for(int i=1;i<uris.size();i++)clip.addItem(new ClipData.Item(uris.get(i)));send.setClipData(clip);
            activity.startActivity(Intent.createChooser(send,"分享 "+uris.size()+" 个视频"));
        }catch(Exception unavailable){message("没有可用的分享方式，或文件无法读取。");}
    }
    private void collectSelected() {
        if(selectedIds.isEmpty())return;List<Long> ids=new ArrayList<>(selectedIds);
        io.execute(()->{try{
            List<JSONObject> groups=store.collections();main.post(()->{
                if(released)return;
                if(groups.isEmpty()){createCollectionFor(ids);return;}
                String[] names=new String[groups.size()];for(int i=0;i<groups.size();i++)names[i]=groups.get(i).optString("name");
                new PocketDialog.Builder(activity).setTitle("将 "+ids.size()+" 个视频加入合集").setItems(names,(dialog,which)->writeSelection(()->store.setCollectionForVideos(ids,groups.get(which).optLong("id")),"已加入合集"))
                        .setPositiveButton("新建合集",(dialog,which)->createCollectionFor(ids)).setNegativeButton("取消",null).show();
            });
        }catch(Exception failure){main.post(()->message("无法读取合集，请重试。"));}});
    }
    private void createCollectionFor(List<Long> ids) {
        EditText input=new PocketEditText(activity);input.setSingleLine(true);input.setTextColor(INK);input.setHintTextColor(MUTED);input.setHint("合集名称");input.setPadding(dp(20),dp(12),dp(20),dp(12));
        PocketDialog dialog=new PocketDialog.Builder(activity).setTitle("新建合集").setView(input).setPositiveButton("创建",null).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(PocketDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String name=input.getText().toString().trim();if(name.isEmpty()){input.setError("请输入合集名称");return;}dialog.dismiss();
            writeSelection(()->{long id=store.createCollection(name);store.setCollectionForVideos(ids,id);},"已创建合集并加入视频");
        }));dialog.show();
    }
    private void removeSelected() {
        if(selectedIds.isEmpty())return;List<Long> ids=new ArrayList<>(selectedIds);
        new PocketDialog.Builder(activity).setTitle("移除 "+ids.size()+" 条视频库记录？")
                .setMessage("只移除所选库记录、精彩标记和合集归属。手机上的原视频文件、下载记录与已有剪辑草稿会保留。")
                .setNegativeButton("取消",null).setPositiveButton("移除记录",(dialog,which)->writeSelection(()->store.removeVideoRecords(ids),"已移除库记录，原视频保留")).show();
    }
    private void showSelectionMore() {
        if(selectedIds.isEmpty()||writing)return;
        List<Long> ids=new ArrayList<>(selectedIds);List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        labels.add("收藏所选视频");actions.add(()->writeSelection(()->store.setFavoriteForVideos(ids,true),"所选视频已收藏"));
        labels.add("取消所选视频收藏");actions.add(()->writeSelection(()->store.setFavoriteForVideos(ids,false),"已取消所选视频收藏"));
        long selectedCollection=collection;
        if(selectedCollection>0){labels.add("从当前合集移除");actions.add(()->removeSelectedFromCollection(ids,selectedCollection));}
        labels.add("从视频库移除");actions.add(this::removeSelected);
        new PocketDialog.Builder(activity).setTitle("整理 "+ids.size()+" 个视频").setItems(labels.toArray(new String[0]),(dialog,which)->actions.get(which).run()).setNegativeButton("返回",null).show();
    }
    private void removeSelectedFromCollection(List<Long> ids,long collectionId) {
        java.util.concurrent.atomic.AtomicReference<List<Long>> removed=new java.util.concurrent.atomic.AtomicReference<>(java.util.Collections.emptyList());
        new PocketDialog.Builder(activity).setTitle("从合集移除 "+ids.size()+" 个视频？")
                .setMessage("只移除当前合集中的归属，视频、观看进度、精彩标记和其他合集不变。")
                .setNegativeButton("取消",null).setPositiveButton("移出合集",(dialog,which)->writeSelection(
                        ()->removed.set(store.removeVideosFromCollection(ids,collectionId)),"已从合集移除，视频保留",
                        ()->restoreCollectionMembership(removed.get(),collectionId))).show();
    }
    private void restoreCollectionMembership(List<Long> ids,long collectionId) {
        if(ids.isEmpty())return;
        // An undo notice can outlive the library tab. Use a fresh handle rather
        // than an already closed View-owned database in that case.
        Runnable restore=()->{try(LibraryStore fresh=new LibraryStore(activity)){
            fresh.setCollectionForVideos(ids,collectionId);
            activity.runOnUiThread(()->{if(activity.isFinishing()||activity.isDestroyed())return;PocketNotice.show(activity,"已撤销移出合集");if(!released)reloadAfterWrite();});
        }catch(Exception failure){activity.runOnUiThread(()->PocketNotice.show(activity,"未能撤销，请确认合集和视频仍在库中。"));}};
        if(released)new Thread(restore,"library-undo").start();else io.execute(restore);
    }
    private void writeSelection(Runnable action,String success) {writeSelection(action,success,null);}
    private void writeSelection(Runnable action,String success,Runnable undo) {
        if(released||writing)return;writing=true;updateSelection();
        io.execute(()->{try{
            action.run();
            // A committed action belongs to the still-live Activity. Its undo
            // must survive both leaving this tab and clearing the View's Handler.
            activity.runOnUiThread(()->{writing=false;if(activity.isFinishing()||activity.isDestroyed())return;PocketNotice.show(activity,success,undo);if(!released){endSelection();reloadAfterWrite();}});
        }catch(Exception failure){main.post(()->{writing=false;if(!released){updateSelection();message("保存失败，原文件未改动，请重试。");}});}});
    }

    public void release() {
        if (released) return; released = true; generation++; clearViewportRestore(); main.removeCallbacksAndMessages(null); covers.release();
        io.execute(store::close); io.shutdown();
    }
    private void message(String value) { if (!released && !activity.isFinishing()) PocketNotice.show(activity,value); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private GradientDrawable shape(int color, boolean border, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); if (border) d.setStroke(dp(1), BORDER); return d; }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(activity); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setGravity(Gravity.CENTER_VERTICAL); if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v;
    }
    private TextView filterChip(String value,String icon){TextView v=control(value,true);v.setTextSize(12);v.setPadding(dp(12),0,dp(10),0);v.setBackground(new android.graphics.drawable.InsetDrawable(shape(palette.surface,false,18),0,dp(7),0,dp(7)));v.setPadding(dp(12),0,dp(10),0);Drawable left="watch".equals(icon)?new PocketIcon("watch",MUTED):new PocketIcon(icon,MUTED),down=new PocketIcon("down",MUTED);left.setBounds(0,0,dp(16),dp(16));down.setBounds(0,0,dp(13),dp(13));v.setCompoundDrawables(left,null,down,null);v.setCompoundDrawablePadding(dp(6));return v;}
    private TextView control(String value, boolean border) {
        TextView v = text(value, 14, INK, false); v.setGravity(Gravity.CENTER); v.setPadding(dp(8), 0, dp(8), 0); v.setSingleLine(true); v.setEllipsize(TextUtils.TruncateAt.END); v.setBackground(shape(palette.control, false, 16)); UiTheme.press(v); return v;
    }
    private static String sourceLabel(String value) {
        VideoPlatform platform=VideoPlatform.fromId(value); if(platform!=null)return platform==VideoPlatform.X?"X":platform==VideoPlatform.BILIBILI?"B站":platform.title; if (value.equals("youtube")) return "YouTube"; if (value.equals("x")) return "X"; if (value.equals("bilibili")) return "B站"; if (value.equals("douyin")) return "抖音"; if (value.equals("export")) return "我的导出"; return "其他平台";
    }
    private static String duration(long ms) { long seconds = Math.max(0, ms / 1000); return seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60) : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60); }

    private View iconControl(String type,String description) {
        ImageView view=new ImageView(activity);view.setImageDrawable(new PocketIcon(type,INK));view.setContentDescription(description);view.setPadding(dp(13),dp(13),dp(13),dp(13));view.setFocusable(true);
        view.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(palette.accentSoft),null,shape(Color.WHITE,false,12)));UiTheme.press(view);return view;
    }
    private final class VideoAdapter extends BaseAdapter {
        @Override public int getCount() { return videos.size(); }
        @Override public Object getItem(int position) { return videos.get(position); }
        @Override public long getItemId(int position) {
            // AbsListView asks for INVALID_POSITION while reconciling stable IDs
            // after filtering, restoring a viewport, or removing the last row.
            return position>=0&&position<videos.size()?videos.get(position).optLong("id"):android.widget.AdapterView.INVALID_ROW_ID;
        }
        @Override public boolean hasStableIds() { return true; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) { return listMode?1:0; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Holder holder;
            if (recycled == null || !(recycled.getTag() instanceof Holder) || ((Holder)recycled.getTag()).list!=listMode) {
                LinearLayout cell=new LinearLayout(activity);cell.setOrientation(listMode?HORIZONTAL:VERTICAL);cell.setGravity(Gravity.CENTER_VERTICAL);cell.setPadding(dp(3),dp(3),dp(3),dp(3));
                FrameLayout media=new FrameLayout(activity);media.setBackground(shape(palette.dark?0xff242424:0xffe8e8e8,false,12));media.setClipToOutline(true);
                ImageView cover=new LibraryCoverView(activity);cover.setScaleType(ImageView.ScaleType.FIT_CENTER);cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);media.addView(cover,new FrameLayout.LayoutParams(-1,-1));
                ProgressBar progress=new PocketProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);progress.setPadding(0,0,0,0);progress.setProgressTintList(android.content.res.ColorStateList.valueOf(palette.accent));progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x66383E3C));progress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                media.addView(progress,new FrameLayout.LayoutParams(-1,dp(3),Gravity.BOTTOM));
                TextView time=text("",11,Color.WHITE,true);time.setGravity(Gravity.CENTER);time.setPadding(dp(6),dp(2),dp(6),dp(2));time.setBackground(shape(0xc2171a1c,false,5));
                FrameLayout.LayoutParams overlay=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.END);overlay.setMargins(dp(6),dp(6),dp(6),dp(6));media.addView(time,overlay);
                TextView checked=text("✓",13,Color.WHITE,true);checked.setGravity(Gravity.CENTER);checked.setBackground(shape(palette.accent,false,12));
                FrameLayout.LayoutParams checkParams=new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.TOP|Gravity.END);checkParams.setMargins(dp(6),dp(6),dp(6),dp(6));media.addView(checked,checkParams);checked.setVisibility(GONE);
                int available=activity.getResources().getConfiguration().screenWidthDp-36;
                int coverHeight=listMode?82:Math.round((available-(grid.getNumColumns()-1)*12f)/Math.max(1,grid.getNumColumns())*.75f);
                LinearLayout.LayoutParams mediaParams=new LinearLayout.LayoutParams(listMode?dp(118):-1,dp(Math.max(106,coverHeight)));
                if(listMode){mediaParams.height=dp(82);mediaParams.rightMargin=dp(12);}cell.addView(media,mediaParams);
                LinearLayout body=new LinearLayout(activity);body.setOrientation(VERTICAL);LinearLayout top=new LinearLayout(activity);top.setGravity(Gravity.TOP);
                TextView title=text("",14,INK,false);if(android.os.Build.VERSION.SDK_INT>=28)title.setTypeface(Typeface.create(Typeface.DEFAULT,600,false));else title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));title.setMaxLines(2);title.setMinLines(listMode?1:2);title.setEllipsize(TextUtils.TruncateAt.END);title.setGravity(Gravity.TOP);title.setPadding(0,listMode?0:dp(8),0,0);
                top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
                ImageView menu=(ImageView)iconControl("more","视频更多操作");menu.setPadding(dp(18),dp(18),dp(18),dp(18));
                if(listMode)top.addView(menu,new LinearLayout.LayoutParams(dp(48),dp(48)));
                else {menu.setImageDrawable(new PocketIcon("more",Color.WHITE));menu.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x44ffffff),new android.graphics.drawable.InsetDrawable(shape(0x68171a1c,false,12),dp(22),dp(2),dp(2),dp(22)),null));FrameLayout.LayoutParams mp=new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP|Gravity.END);media.addView(menu,mp);menu.setPadding(dp(28),dp(8),dp(8),dp(28));}
                body.addView(top,new LinearLayout.LayoutParams(-1,-2));
                TextView meta=text("",12,MUTED,false);meta.setMaxLines(1);meta.setEllipsize(TextUtils.TruncateAt.END);body.addView(meta,new LinearLayout.LayoutParams(-1,-2));
                TextView context=text("",11,palette.accent,false);context.setSingleLine(true);context.setEllipsize(TextUtils.TruncateAt.END);context.setPadding(dp(6),dp(3),dp(6),dp(3));context.setBackground(shape(palette.accentSoft,false,6));LinearLayout.LayoutParams badge=new LinearLayout.LayoutParams(-2,-2);badge.topMargin=dp(5);body.addView(context,badge);
                cell.addView(body,new LinearLayout.LayoutParams(listMode?0:-1,-2,listMode?1:0));
                holder=new Holder(cover,progress,time,title,meta,context,menu,checked,listMode);cell.setTag(holder);cell.setFocusable(true);UiTheme.press(cell);recycled=cell;
            }else holder=(Holder)recycled.getTag();
            View media=(View)holder.cover.getParent();ViewGroup.LayoutParams size=media.getLayoutParams();int columns=grid.getNumColumns();
            size.height=dp(listMode?82:Math.max(columns==3?84:106,Math.round((getResources().getConfiguration().screenWidthDp-46-(columns-1)*(columns==3?8:12f))/Math.max(1,columns)*.75f)));media.setLayoutParams(size);holder.title.setTextSize(!listMode&&columns==3?12:14);
            JSONObject video=videos.get(position);long id=video.optLong("id");holder.title.setText(video.optString("title","视频"));
            holder.time.setText(video.optBoolean("missing")?"文件不可用":video.optLong("durationMs")>0?duration(video.optLong("durationMs")):"视频");
            int resolution=Math.min(video.optInt("width"),video.optInt("height"));
            holder.meta.setText(sourceLabel(video.optString("source"))+(resolution>0?" · "+resolution+"P":"")+" · "+(video.optLong("bytes")>0?MediaSize.format(video.optLong("bytes")):"大小待获取"));
            long at=Math.max(0,video.optLong("playbackMs")),total=Math.max(0,video.optLong("durationMs"));boolean missing=video.optBoolean("missing");
            boolean watched=LibraryWatchState.isWatched(at,total),resume=LibraryWatchState.isContinuing(at,total),favorite=video.optBoolean("favorite");
            String context=missing?"文件不可用":watched?"已看完":resume?"续播 "+duration(at):"";
            holder.context.setText((favorite?"收藏"+(context.isEmpty()?"":" · "):"")+context);holder.context.setVisibility(missing||watched||resume||favorite?VISIBLE:GONE);
            holder.progress.setVisibility(!missing&&at>0&&total>0?VISIBLE:GONE);
            holder.progress.setProgress(total>0?(int)Math.min(1000,at*1000d/total):0);
            boolean selected=selectedIds.contains(id);holder.checked.setVisibility(selectionMode?VISIBLE:GONE);holder.checked.setText(selected?"✓":"");holder.checked.setTextColor(palette.dark&&selected?palette.bg:Color.WHITE);holder.checked.setBackground(shape(selected?palette.accent:0x99242a2b,false,12));
            recycled.setBackground(shape(selected?palette.accentSoft:Color.TRANSPARENT,false,16));
            // A focusable overflow child suppresses GridView.onItemClick for the
            // whole row. Own the actual row touch/keyboard action, leaving the
            // overflow independent; keep AdapterView callbacks for accessibility.
            final View cell=recycled;recycled.setOnClickListener(v->activateVideo(video,cell));
            recycled.setOnLongClickListener(v->{selectVideo(video);return true;});
            holder.menu.setContentDescription("视频更多操作："+video.optString("title","视频"));holder.menu.setVisibility(selectionMode?GONE:VISIBLE);holder.menu.setOnClickListener(v->showActions(video));
            holder.cover.setTransitionName("sp-video-"+id);covers.load(holder.cover,id,video.optString("uri"),video.optString("thumbnailKey"),video.optLong("durationMs"));
            recycled.setContentDescription(video.optString("title")+"，"+holder.meta.getText()+"，"+holder.context.getText()+(selectionMode?(selected?"，已选择":"，未选择"):"，长按多选"));return recycled;
        }
    }
    private static final class Holder {
        final ImageView cover,menu;final ProgressBar progress;final TextView time,title,meta,context,checked;final boolean list;
        Holder(ImageView cover,ProgressBar progress,TextView time,TextView title,TextView meta,TextView context,ImageView menu,TextView checked,boolean list){this.cover=cover;this.progress=progress;this.time=time;this.title=title;this.meta=meta;this.context=context;this.menu=menu;this.checked=checked;this.list=list;}
    }
}
