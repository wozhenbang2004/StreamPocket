package dev.streampocket;

import android.app.Dialog;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.ZipFile;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class Content040UiTest {
    @Test public void contentOptionsAreExplicitPersistedAndDoNotStartDownloads() throws Exception {
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible()){
            MainActivity a=c.get();Engine.Media video=video();Engine.Media album=video();album.id="album";album.title="离线图集样例";album.album=true;album.imageCount=3;album.hasAudio=false;album.qualities.clear();album.qualities.add(new Engine.Quality("images","全部图片 · ZIP","best"));
            media(a).add(video);media(a).add(album);call(a,"showTab",new Class<?>[]{String.class},"new");
            assertNotNull(find(a.getWindow().getDecorView(),"保存图集"));assertNotNull(find(a.getWindow().getDecorView(),"批量设置"));
            call(a,"showAttachments",new Class<?>[]{Engine.Media.class},video);PocketDialog panel=(PocketDialog)ShadowDialog.getLatestDialog();
            panel.getListView().performItemClick(null,0,0);panel.getListView().performItemClick(null,1,1);
            capture(panel.getWindow().getDecorView(),"attachments-fixture");panel.getButton(PocketDialog.BUTTON_POSITIVE).performClick();
            assertTrue(video.saveSubtitles);assertTrue(video.saveCover);assertTrue(TaskStore.all(a).isEmpty());
            InspectionInbox restored=new InspectionInbox();restored.restore(a.getPreferences(0).getString("inspection_inbox",""));assertTrue(restored.media.get(0).saveSubtitles);
            capture(a.getWindow().getDecorView(),"content-ready-fixture");
        }
    }
    @Test public void collectionStartsWithNoSelectionAndReturningDoesNotAcceptOrDownload() throws Exception {
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible()){
            MainActivity a=c.get();List<Engine.CollectionItem> items=Arrays.asList(new Engine.CollectionItem("https://www.youtube.com/watch?v=jNQXAC9IVRw","离线合集条目一",19,-1),new Engine.CollectionItem("https://www.youtube.com/watch?v=BaW_jenozKc","离线合集条目二",10,-1));
            call(a,"showCollectionItems",new Class<?>[]{List.class},items);PocketDialog panel=(PocketDialog)ShadowDialog.getLatestDialog();
            assertFalse(panel.getButton(PocketDialog.BUTTON_POSITIVE).isEnabled());
            panel.getListView().performItemClick(null,0,0);assertTrue(panel.getButton(PocketDialog.BUTTON_POSITIVE).isEnabled());
            assertEquals("解析 1 项",panel.getButton(PocketDialog.BUTTON_POSITIVE).getText().toString());
            capture(panel.getWindow().getDecorView(),"collection-fixture");panel.getButton(PocketDialog.BUTTON_NEGATIVE).performClick();
            assertTrue(TaskStore.all(a).isEmpty());Field field=MainActivity.class.getDeclaredField("inspectionInbox");field.setAccessible(true);assertTrue(((InspectionInbox)field.get(a)).jobs.isEmpty());
        }
    }
    @Test public void completeImagesBecomeOneArchiveAndMissingImagesAreRejected() throws Exception {
        File directory=Files.createTempDirectory("album-fixture").toFile();File a=png(directory,"a.png"),b=png(directory,"b.png");
        File zip=ContentFiles.album(directory,a+"\n"+b,2,"离线图集","post1",()->false);
        try(ZipFile content=new ZipFile(zip)){assertEquals(2,content.size());assertNotNull(content.getEntry("001.png"));assertNotNull(content.getEntry("002.png"));}
        try{ContentFiles.album(directory,a.toString(),2,"离线图集","post1",()->false);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("数量不完整"));}
        try(LibraryStore store=new LibraryStore(RuntimeEnvironment.getApplication())){assertEquals(0,store.upsertCompletedDownload(new JSONObject().put("status","completed").put("mime","application/zip").put("uri",Uri.fromFile(zip))));}
    }
    @Test public void partialAttachmentsRetainAlreadyPublishedUrisAndCorrectMime() throws Exception {
        Context app=RuntimeEnvironment.getApplication();File storage=Files.createTempDirectory("published-fixture").toFile();
        ContentProvider provider=new ContentProvider(){
            public boolean onCreate(){return true;}public String getType(Uri uri){return "image/png";}
            public Uri insert(Uri uri,ContentValues values){return Uri.parse("content://media/external/downloads/"+System.nanoTime());}
            public int update(Uri uri,ContentValues values,String where,String[] args){return 1;}
            public int delete(Uri uri,String where,String[] args){return 1;}
            public Cursor query(Uri uri,String[] projection,String where,String[] args,String order){return null;}
            public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{return ParcelFileDescriptor.open(new File(storage,uri.getLastPathSegment()),mode.contains("w")?ParcelFileDescriptor.MODE_CREATE|ParcelFileDescriptor.MODE_READ_WRITE|ParcelFileDescriptor.MODE_TRUNCATE:ParcelFileDescriptor.MODE_READ_ONLY);}
        };
        android.content.pm.ProviderInfo providerInfo=new android.content.pm.ProviderInfo();providerInfo.authority="media";
        provider.attachInfo(app,providerInfo);ShadowContentResolver.registerProviderInternal("media",provider);
        File directory=Files.createTempDirectory("sidecar-fixture").toFile();png(directory,"a.png");Files.write(new File(directory,"z.vtt").toPath(),"<html>Site Unavailable</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Engine.Media media=video();media.saveCover=true;media.saveSubtitles=true;List<String> committed=new ArrayList<>();
        ContentFiles.Sidecars result=ContentFiles.publishSidecars(app,directory,media,()->false,files->committed.add(files.toString()));
        assertEquals(result.error,1,result.saved.length());assertEquals(1,committed.size());assertFalse(result.warning.isEmpty());assertEquals("image/png",result.saved.getJSONObject(0).getString("mime"));
        assertEquals("application/zip",MediaExporter.mimeForExtension("zip"));assertEquals("text/x-ssa",MediaExporter.mimeForExtension("ass"));assertEquals("application/octet-stream",MediaExporter.mimeForExtension("unknownfixture"));
    }
    private static Engine.Media video(){Engine.Media m=new Engine.Media();m.id="video";m.url="https://www.xiaohongshu.com/explore/123abc";m.title="离线视频样例 · 字幕与封面";m.platform="小红书";m.hasAudio=true;m.thumbnail="https://example.invalid/fixture.jpg";m.subtitleAvailable=true;m.subtitleLanguage="zh-Hans";m.qualities.add(new Engine.Quality("best","最高可用画质","bv*+ba/b"));return m;}
    @SuppressWarnings("unchecked") private static List<Engine.Media> media(MainActivity a)throws Exception{Field f=MainActivity.class.getDeclaredField("inspectedMedia");f.setAccessible(true);return (List<Engine.Media>)f.get(a);}
    private static Object call(Object a,String name,Class<?>[] types,Object...args)throws Exception{Method m=a.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(a,args);}
    private static View find(View view,String text){if(view instanceof TextView&&((TextView)view).getText().toString().equals(text))return view;if(view instanceof ViewGroup)for(int n=0;n<((ViewGroup)view).getChildCount();n++){View result=find(((ViewGroup)view).getChildAt(n),text);if(result!=null)return result;}return null;}
    private static File png(File directory,String name)throws Exception{File file=new File(directory,name);Bitmap b=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);b.eraseColor(Color.GRAY);try(OutputStream out=new FileOutputStream(file)){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();return file;}
    private static void capture(View view,String name)throws Exception{Shadows.shadowOf(Looper.getMainLooper()).idle();int width=824,height=1700;view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.AT_MOST));view.layout(0,0,width,view.getMeasuredHeight());Bitmap image=Bitmap.createBitmap(width,Math.max(1,view.getMeasuredHeight()),Bitmap.Config.ARGB_8888);view.draw(new Canvas(image));File dir=new File("build/reports/view-fixtures/040-content");dir.mkdirs();try(OutputStream out=new FileOutputStream(new File(dir,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();}
}
