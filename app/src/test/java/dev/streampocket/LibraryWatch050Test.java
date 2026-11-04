package dev.streampocket;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real SQLite migration, composable filters and stable playback navigation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public final class LibraryWatch050Test {
    private Context app;
    private LibraryStore store;
    private File fixture;

    @Before public void setup() throws Exception {
        app=RuntimeEnvironment.getApplication();app.deleteDatabase("media-library.db");
        app.getSharedPreferences("tasks",0).edit().clear().commit();
        fixture=File.createTempFile("library050-",".mp4",app.getCacheDir());
        try(FileOutputStream out=new FileOutputStream(fixture)){out.write(new byte[]{1,2,3,4});}
        store=new LibraryStore(app);
    }
    @After public void teardown(){store.close();fixture.delete();app.deleteDatabase("media-library.db");}

    @Test public void watchBoundariesAreSharedAndUnknownDurationRemainsContinuable(){LibraryWatchStateSelfTest.main(new String[0]);}

    @Test public void versionOneMigrationPreservesEveryExistingTableAndStableIdentity() throws Exception {
        store.close();File database=app.getDatabasePath("media-library.db");database.getParentFile().mkdirs();
        Map<String,String> before;
        try(SQLiteDatabase old=SQLiteDatabase.openOrCreateDatabase(database,null)) {
            old.setForeignKeyConstraintsEnabled(true);
            // The shipped v1 schema is deliberately independent of today's onCreate.
            old.execSQL("CREATE TABLE video (id INTEGER PRIMARY KEY AUTOINCREMENT,uri TEXT NOT NULL UNIQUE,title TEXT NOT NULL,mime TEXT NOT NULL,source TEXT NOT NULL,file_name TEXT NOT NULL DEFAULT '',duration_ms INTEGER NOT NULL DEFAULT 0,width INTEGER NOT NULL DEFAULT 0,height INTEGER NOT NULL DEFAULT 0,bytes INTEGER NOT NULL DEFAULT 0,playback_ms INTEGER NOT NULL DEFAULT 0,author TEXT NOT NULL DEFAULT '',source_url TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL)");
            old.execSQL("CREATE TABLE imported_task (task_id TEXT PRIMARY KEY,uri TEXT NOT NULL,video_id INTEGER REFERENCES video(id) ON DELETE SET NULL)");
            old.execSQL("CREATE INDEX video_created ON video(created_at DESC,id DESC)");
            old.execSQL("CREATE TABLE marker (id INTEGER PRIMARY KEY AUTOINCREMENT,video_id INTEGER NOT NULL REFERENCES video(id) ON DELETE CASCADE,position_ms INTEGER NOT NULL,end_ms INTEGER NOT NULL DEFAULT 0,label TEXT NOT NULL DEFAULT '')");
            old.execSQL("CREATE INDEX marker_video ON marker(video_id,position_ms)");
            old.execSQL("CREATE TABLE collection (id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE)");
            old.execSQL("CREATE TABLE collection_video (collection_id INTEGER NOT NULL REFERENCES collection(id) ON DELETE CASCADE,video_id INTEGER NOT NULL REFERENCES video(id) ON DELETE CASCADE,PRIMARY KEY(collection_id,video_id))");
            old.execSQL("CREATE TABLE edit_project (id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,created_at INTEGER NOT NULL,export_state TEXT NOT NULL DEFAULT 'draft',export_progress INTEGER NOT NULL DEFAULT 0,export_detail TEXT NOT NULL DEFAULT '',output_uri TEXT NOT NULL DEFAULT '')");
            old.execSQL("CREATE TABLE edit_clip (id INTEGER PRIMARY KEY AUTOINCREMENT,project_id INTEGER NOT NULL REFERENCES edit_project(id) ON DELETE CASCADE,video_id INTEGER REFERENCES video(id) ON DELETE SET NULL,uri TEXT NOT NULL,title TEXT NOT NULL,start_ms INTEGER NOT NULL,end_ms INTEGER NOT NULL,position INTEGER NOT NULL)");
            old.execSQL("CREATE INDEX clip_order ON edit_clip(project_id,position,id)");
            old.execSQL("INSERT INTO video VALUES(41,?,'旧视频','video/mp4','x','原名.mp4',100000,1920,1080,456789,12345,'旧作者','https://x.com/reference/status/41',1234567)",new Object[]{Uri.fromFile(fixture).toString()});
            old.execSQL("INSERT INTO imported_task VALUES('kept',?,41)",new Object[]{Uri.fromFile(fixture).toString()});
            old.execSQL("INSERT INTO imported_task VALUES('removed','content://fixture/removed',NULL)");
            old.execSQL("INSERT INTO marker VALUES(11,41,1000,4000,'旧精彩片段')");
            old.execSQL("INSERT INTO collection VALUES(9,'收藏')");
            old.execSQL("INSERT INTO collection_video VALUES(9,41)");
            old.execSQL("INSERT INTO edit_project VALUES(7,'原草稿',1234567,'draft',0,'原说明','')");
            old.execSQL("INSERT INTO edit_clip VALUES(13,7,41,?,'旧快照',1000,4000,0)",new Object[]{Uri.fromFile(fixture).toString()});
            old.setVersion(1);before=snapshotV1(old);
        }
        store=new LibraryStore(app);
        JSONObject video=store.video(41);assertNotNull(video);assertFalse(video.getBoolean("favorite"));assertEquals(12345,video.getLong("playbackMs"));
        assertEquals(Collections.singletonList(9L),store.collectionIds(41));assertEquals("收藏",store.collections().get(0).getString("name"));
        try(SQLiteDatabase migrated=app.openOrCreateDatabase("media-library.db",0,null)) {
            assertEquals(3,migrated.getVersion());assertEquals(before,snapshotV1(migrated));
            try(Cursor check=migrated.rawQuery("PRAGMA foreign_key_check",null)){assertEquals(0,check.getCount());}
        }
        store.setFavorite(41,true);store.close();store=new LibraryStore(app);
        assertTrue(store.video(41).getBoolean("favorite"));assertEquals(12345,store.video(41).getLong("playbackMs"));
        assertTrue(seed(99,"新视频","x",100000,0)>41);assertTrue(fixture.exists());
    }

    @Test public void combinedFiltersCountAndEveryPageAgreeWithStableDateAndSourceOrdering() throws Exception {
        long group=store.createCollection("周末继续看");List<Long> expected=new ArrayList<>();
        for(int n=0;n<180;n++) {
            String source=n%3==0?"youtube":n%2==0?"bilibili":"x";
            long at=n%5==0?100000:n%5==1?0:12000;
            long id=seed(n,"100%_片段 "+n,source,100000,at);boolean favorite=n%4!=0;
            store.setFavorite(id,favorite);if(n%7!=0)store.setCollection(id,group,true);
            if(favorite&&at==12000&&n%7!=0&&!"youtube".equals(source))expected.add(id);
        }
        List<String> sources=Arrays.asList("x","bilibili");
        for(String order:Arrays.asList("date","source")) {
            List<Long> actual=new ArrayList<>();
            for(int offset=0;;offset+=7) {
                List<JSONObject> page=store.listVideosFromSources("%_",sources,group,7,offset,order,"continue",true);
                actual.addAll(ids(page));if(page.size()<7)break;
            }
            assertTrue("Fixture must cross the UI's first 30 records",actual.size()>30);
            List<Long> sortedExpected=new ArrayList<>();
            for(int offset=0;;offset+=100){List<JSONObject> page=store.listVideosFromSources("%_",sources,group,100,offset,order);for(JSONObject row:page)if(expected.contains(row.optLong("id")))sortedExpected.add(row.optLong("id"));if(page.size()<100)break;}
            assertEquals(sortedExpected,actual);assertEquals(expected.size(),actual.size());
            assertEquals(actual.size(),store.countVideosFromSources("%_",sources,group,"continue",true));
            for(int i=0;i<actual.size();i++) {
                assertEquals(i+1<actual.size()?actual.get(i+1).longValue():0,store.nextVideoId(actual.get(i),"%_",sources,group,order,"continue",true));
                assertEquals(i>0?actual.get(i-1).longValue():0,store.adjacentVideoId(actual.get(i),"%_",sources,group,order,false,"continue",true));
            }
        }
        assertEquals(0,store.countVideosFromSources("' OR 1=1 --",sources,group,"continue",true));
        assertEquals(180,store.countVideosFromSources("",null,0,"untrusted SQL",false));
    }

    @Test public void sqlClassificationMatchesBadgesIncludingVeryShortAndUnknownVideos() throws Exception {
        long[][] samples={{0,0},{1000,0},{0,200},{1,200},{189,200},{190,200},{9499,10000},{9500,10000},{99499,100000},{99500,100000}};
        List<Long> unwatched=new ArrayList<>(),continuing=new ArrayList<>(),watched=new ArrayList<>();
        for(int i=0;i<samples.length;i++) {
            long at=samples[i][0],duration=samples[i][1],id=seed(i,"分类 "+i,"x",duration,at);
            if(at<=0)unwatched.add(id);else if(LibraryWatchState.isWatched(at,duration))watched.add(id);else continuing.add(id);
        }
        Collections.reverse(unwatched);Collections.reverse(continuing);Collections.reverse(watched);
        assertEquals(unwatched,ids(store.listVideosFromSources("",null,0,30,0,"date","unwatched",false)));
        assertEquals(continuing,ids(store.listVideosFromSources("",null,0,30,0,"date","continue",false)));
        assertEquals(watched,ids(store.listVideosFromSources("",null,0,30,0,"date","watched",false)));
        assertEquals(samples.length,unwatched.size()+continuing.size()+watched.size());
    }

    @Test public void navigationKeepsAnAnchorAfterCurrentVideoLeavesTheWatchOrFavoriteFilter() throws Exception {
        for(int n=0;n<65;n++){long id=seed(n,"连播 "+n,n%3==0?"bilibili":"x",100000,0);store.setFavorite(id,true);}
        for(String order:Arrays.asList("date","source")) {
            List<Long> all=ids(store.listVideosFromSources("连播",null,0,100,0,order));
            long current=all.get(29),previous=all.get(28),next=all.get(30);
            store.savePlayback(current,12000);store.setFavorite(current,false);
            assertEquals(next,store.adjacentVideoId(current,"连播",null,0,order,true,"unwatched",true));
            assertEquals(previous,store.adjacentVideoId(current,"连播",null,0,order,false,"unwatched",true));
            store.savePlayback(current,100000);
            assertEquals(next,store.nextVideoId(current,"连播",null,0,order,"unwatched",true));
            assertEquals(next,store.nextVideoId(current,"连播",null,0,order));
            store.savePlayback(current,0);store.setFavorite(current,true);
        }
    }

    @Test public void favoriteBatchesAreAtomicAndDoNotTouchProgressMarkersOrDrafts() throws Exception {
        long first=seed(1,"甲","x",100000,12000),second=seed(2,"乙","bilibili",100000,34000);
        long marker=store.addRangeMarker(first,1000,4000,"保留"),project=store.createProject("剪辑");store.addClip(project,first,2000,5000);
        String original=store.video(first).getString("uri");
        invalid(()->store.setFavoriteForVideos(Arrays.asList(first,999999L),true));assertFalse(store.video(first).getBoolean("favorite"));
        store.setFavoriteForVideos(Arrays.asList(first,first,second),true);assertEquals(2,store.countVideosFromSources("",null,0,"all",true));
        store.close();store=new LibraryStore(app);assertTrue(store.video(first).getBoolean("favorite"));
        invalid(()->store.setFavoriteForVideos(Arrays.asList(first,0L),false));assertTrue(store.video(first).getBoolean("favorite"));
        store.setFavoriteForVideos(Arrays.asList(first,second),false);assertEquals(0,store.countVideosFromSources("",null,0,"all",true));
        assertEquals(original,store.video(first).getString("uri"));assertEquals(12000,store.video(first).getLong("playbackMs"));
        assertEquals(marker,store.markers(first).get(0).getLong("id"));assertEquals(first,store.clips(project).get(0).getLong("videoId"));
    }

    @Test public void collectionRenameRemoveUndoAndDeleteOnlyChangeTheTargetMemberships() throws Exception {
        long a=seed(1,"甲","x",100000,12000),b=seed(2,"乙","x",100000,0),outside=seed(3,"丙","bilibili",100000,100000);
        long group=store.createCollection("收藏"),other=store.createCollection("工作");
        store.setCollectionForVideos(Arrays.asList(a,b),group);store.setCollectionForVideos(Arrays.asList(a,outside),other);
        store.setFavorite(a,true);long marker=store.addMarker(a,5000,"重点"),project=store.createProject("保留草稿");store.addClip(project,a,1000,4000);
        invalid(()->store.renameCollection(group," 工作 "));invalid(()->store.renameCollection(group," "));invalid(()->store.renameCollection(999999,"新名称"));
        store.renameCollection(group," 周末 ");assertEquals("周末",name(group));assertEquals(2,store.countVideosFromSources("",null,group,"all",false));
        invalid(()->store.removeVideosFromCollection(Arrays.asList(a,999999L),group));assertTrue(store.collectionIds(a).contains(group));
        List<Long> removed=store.removeVideosFromCollection(Arrays.asList(a,a,outside),group);assertEquals(Collections.singletonList(a),removed);
        assertFalse(store.collectionIds(a).contains(group));assertTrue(store.collectionIds(a).contains(other));
        store.setCollectionForVideos(removed,group);assertTrue(store.collectionIds(a).contains(group));assertFalse(store.collectionIds(outside).contains(group));
        store.deleteCollection(group);assertEquals("工作",name(other));assertEquals(Collections.singletonList(other),store.collectionIds(a));
        assertNotNull(store.video(a));assertNotNull(store.video(b));assertTrue(store.video(a).getBoolean("favorite"));assertEquals(12000,store.video(a).getLong("playbackMs"));
        assertEquals(marker,store.markers(a).get(0).getLong("id"));assertEquals(a,store.clips(project).get(0).getLong("videoId"));
        try(SQLiteDatabase db=app.openOrCreateDatabase("media-library.db",0,null);Cursor c=db.rawQuery("SELECT COUNT(*) FROM imported_task",null)){assertTrue(c.moveToFirst());assertEquals(3,c.getLong(0));}
        invalid(()->store.removeVideosFromCollection(Collections.singletonList(a),group));assertTrue(fixture.exists());
    }

    private long seed(int n,String title,String source,long duration,long position) throws Exception {
        String uri=Uri.fromFile(new File(app.getCacheDir(),"watch050-"+n+".mp4")).toString();
        String url="youtube".equals(source)?"https://youtu.be/BaW_jenozKc":"bilibili".equals(source)?"https://b23.tv/fixture":"https://x.com/fixture/status/"+n;
        JSONObject task=new JSONObject().put("id","watch050-"+n).put("uri",uri).put("title",title).put("mime","video/mp4").put("status","completed")
                .put("url",url).put("size",1234).put("completedAt",1000+n/2).put("media",new JSONObject().put("duration",duration/1000d).put("uploader","样例作者"));
        long id=store.upsertCompletedDownload(task);if(duration>0)store.updateMetadata(id,duration,0,0);store.savePlayback(id,position);return id;
    }
    private String name(long id) {for(JSONObject group:store.collections())if(group.optLong("id")==id)return group.optString("name");return "";}
    private static List<Long> ids(List<JSONObject> rows){List<Long> result=new ArrayList<>();for(JSONObject row:rows)result.add(row.optLong("id"));return result;}
    private static void invalid(Runnable work){try{work.run();fail("Expected invalid operation to fail");}catch(IllegalArgumentException expected){}}
    private static Map<String,String> snapshotV1(SQLiteDatabase db) {
        Map<String,String> result=new LinkedHashMap<>();
        for(String table:new String[]{"video","imported_task","marker","collection","collection_video","edit_project","edit_clip"}) {
            String columns="video".equals(table)?"id,uri,title,mime,source,file_name,duration_ms,width,height,bytes,playback_ms,author,source_url,created_at":"*";
            StringBuilder rows=new StringBuilder();try(Cursor c=db.rawQuery("SELECT "+columns+" FROM "+table+" ORDER BY 1",null)) {
                while(c.moveToNext()){for(int i=0;i<c.getColumnCount();i++)rows.append(c.isNull(i)?"<null>":c.getString(i)).append('\u0001');rows.append('\n');}
            }result.put(table,rows.toString());
        }return result;
    }
}