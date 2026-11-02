package dev.streampocket;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public final class DownloadMoverTest {
    Context app;StorageLocationTest.TreeProvider provider;
    @Before public void setup() throws Exception {
        app=RuntimeEnvironment.getApplication();app.deleteDatabase("media-library.db");
        for(String name:new String[]{"download-location","download-moves","download-uri-renames","tasks"})app.getSharedPreferences(name,0).edit().clear().commit();
        Field initialized=TaskStore.class.getDeclaredField("initialized");initialized.setAccessible(true);initialized.setBoolean(null,false);
        provider=StorageLocationTest.install(app);DownloadLocation.remember(app,StorageLocationTest.folder("b"),StorageLocationTest.GRANTS);
    }
    JSONObject seed(String id,Uri uri,String mime)throws Exception {
        JSONObject task=new JSONObject().put("id",id).put("status","completed").put("uri",uri.toString()).put("title",id).put("mime",mime)
                .put("progress",100).put("media",new JSONObject().put("duration",120).put("url","https://example.org/recorded"));
        JSONArray tasks=new JSONArray(app.getSharedPreferences("tasks",0).getString("items","[]"));tasks.put(task);
        app.getSharedPreferences("tasks",0).edit().putString("items",tasks.toString()).commit();TaskStore.all(app);return task;
    }
    @Test public void successfulMovePreservesVideoProgressMarkersCollectionsAndClipSnapshots() throws Exception {
        File source=StorageLocationTest.source(app,"move-success.mp4");JSONObject task=seed("video",Uri.fromFile(source),"video/mp4");
        long id,project,marker,collection,clip;
        try(LibraryStore library=new LibraryStore(app)) {
            id=library.upsertCompletedDownload(task);library.savePlayback(id,43000);marker=library.addMarker(id,12345,"kept");
            collection=library.createCollection("kept collection");library.setCollection(id,collection,true);
            project=library.createProject("kept draft");clip=library.addClip(project,id,1000,3000);
        }
        DownloadMover.Result moved=DownloadMover.moveCompleted(app,()->false,message->{});assertEquals(moved.summary(),1,moved.moved);assertEquals(0,moved.failed);
        String destination=TaskStore.get(app,"video").getString("uri");assertFalse(source.exists());assertTrue(destination.startsWith("content://"));
        try(LibraryStore library=new LibraryStore(app)) {
            JSONObject video=library.video(id);assertNotNull(video);assertEquals(id,video.getLong("id"));assertEquals(destination,video.getString("uri"));assertEquals(43000,video.getLong("playbackMs"));
            assertEquals(marker,library.markers(id).get(0).getLong("id"));assertEquals(1,library.listVideos("","all",collection,20,0).size());
            JSONObject draft=library.clips(project).get(0);assertEquals(clip,draft.getLong("id"));assertEquals(destination,draft.getString("uri"));assertEquals(1000,draft.getLong("startMs"));
            assertFalse(library.referencesUri(Uri.fromFile(source).toString()));
        }
        assertEquals(1,provider.created);DownloadMover.Result again=DownloadMover.moveCompleted(app,()->false,message->{});assertEquals(0,again.moved);assertEquals(1,provider.created);
    }
    @Test public void repeatedUrisAcrossAudioAlbumAndAttachmentsAreCopiedOnce() throws Exception {
        File source=StorageLocationTest.source(app,"shared-audio.m4a");Uri uri=Uri.fromFile(source);
        JSONObject task=seed("audio",uri,"audio/mp4");task.put("attachments",new JSONArray().put(new JSONObject().put("name","cover.bin").put("mime","application/octet-stream").put("uri",uri.toString())));
        app.getSharedPreferences("tasks",0).edit().putString("items",new JSONArray().put(task).toString()).commit();
        DownloadMover.Result result=DownloadMover.moveCompleted(app,()->false,message->{});assertEquals(result.summary(),1,result.moved);assertEquals(1,provider.created);
        JSONObject updated=TaskStore.get(app,"audio");assertEquals(updated.getString("uri"),updated.getJSONArray("attachments").getJSONObject(0).getString("uri"));
        File zip=StorageLocationTest.source(app,"album.zip");seed("album",Uri.fromFile(zip),"application/zip");
        assertEquals(1,DownloadMover.moveCompleted(app,()->false,message->{}).moved);
    }
    @Test public void copyOrReadbackFailureNeverChangesReferencesOrDeletesOriginal() throws Exception {
        File source=StorageLocationTest.source(app,"copy-failure.mp4");Uri uri=Uri.fromFile(source);seed("video",uri,"video/mp4");
        provider.failWrite=true;DownloadMover.Result failed=DownloadMover.moveCompleted(app,()->false,message->{});assertEquals(1,failed.failed);assertTrue(source.exists());assertEquals(uri.toString(),TaskStore.get(app,"video").getString("uri"));assertTrue(provider.files.isEmpty());
        provider.failWrite=false;provider.corruptRead=true;failed=DownloadMover.moveCompleted(app,()->false,message->{});assertEquals(1,failed.failed);assertTrue(source.exists());assertEquals(uri.toString(),TaskStore.get(app,"video").getString("uri"));assertTrue(provider.files.isEmpty());
    }
    @Test public void libraryWriteFailureRetainsVerifiedCopyAndRetryDoesNotCopyAgain() throws Exception {
        File source=StorageLocationTest.source(app,"database-failure.mp4");Uri uri=Uri.fromFile(source);JSONObject task=seed("video",uri,"video/mp4");
        try(LibraryStore library=new LibraryStore(app)){library.upsertCompletedDownload(task);}
        try(SQLiteDatabase db=app.openOrCreateDatabase("media-library.db",0,null)){db.execSQL("CREATE TRIGGER deny_move BEFORE UPDATE OF uri ON video BEGIN SELECT RAISE(ABORT,'synthetic record failure'); END");}
        DownloadMover.Result failure=DownloadMover.moveCompleted(app,()->false,message->{});assertEquals(1,failure.failed);assertTrue(source.exists());assertEquals(uri.toString(),TaskStore.get(app,"video").getString("uri"));assertEquals(1,provider.created);
        try(SQLiteDatabase db=app.openOrCreateDatabase("media-library.db",0,null)){db.execSQL("DROP TRIGGER deny_move");}
        DownloadMover.Result retry=DownloadMover.moveCompleted(app,()->false,message->{});assertEquals(retry.summary(),1,retry.moved);assertEquals(1,provider.created);assertFalse(source.exists());
    }
    @Test public void taskCommitFailureKeepsOriginalAndJournalRecoversBothStores() throws Exception {
        File source=StorageLocationTest.source(app,"task-record-failure.mp4");Uri uri=Uri.fromFile(source);JSONObject task=seed("video",uri,"video/mp4");long id;
        try(LibraryStore library=new LibraryStore(app)){id=library.upsertCompletedDownload(task);}
        Context failTasks=new ContextWrapper(app) {
            @Override public SharedPreferences getSharedPreferences(String name,int mode) {
                SharedPreferences delegate=super.getSharedPreferences(name,mode);if(!name.equals("tasks"))return delegate;
                return (SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
                    Object result=method.invoke(delegate,args);if(!method.getName().equals("edit"))return result;
                    SharedPreferences.Editor editor=(SharedPreferences.Editor)result;
                    final Object[] holder={null};holder[0]=Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(ep,em,ea)->{
                        if(em.getName().equals("commit"))return false;Object answer=em.invoke(editor,ea);return answer instanceof SharedPreferences.Editor?holder[0]:answer;
                    });return holder[0];
                });
            }
        };
        DownloadMover.Result failure=DownloadMover.moveCompleted(failTasks,()->false,message->{});assertEquals(1,failure.failed);assertTrue(source.exists());assertEquals(1,provider.created);
        assertEquals(uri.toString(),TaskStore.get(app,"video").getString("uri"));
        DownloadMover.Result retry=DownloadMover.moveCompleted(app,()->false,message->{});assertEquals(retry.summary(),1,retry.moved);assertEquals(1,provider.created);assertFalse(source.exists());
        try(LibraryStore library=new LibraryStore(app)){assertEquals(TaskStore.get(app,"video").getString("uri"),library.video(id).getString("uri"));}
    }
    @Test public void deleteFailureKeepsTwoCopiesAndRetryOnlyDeletesOriginal() throws Exception {
        DownloadLocation.remember(app,StorageLocationTest.folder("a"),StorageLocationTest.GRANTS);
        MediaExporter.Result source=MediaExporter.save(app,StorageLocationTest.source(app,"delete-refused.mp4"),()->false);seed("video",source.uri,"video/mp4");
        DownloadLocation.remember(app,StorageLocationTest.folder("b"),StorageLocationTest.GRANTS);provider.denyDelete=true;
        DownloadMover.Result first=DownloadMover.moveCompleted(app,()->false,message->{});assertEquals(first.summary(),1,first.retained);assertEquals(2,provider.files.size());
        assertEquals("b",DocumentsContract.getTreeDocumentId(Uri.parse(TaskStore.get(app,"video").getString("uri"))));
        int created=provider.created;provider.denyDelete=false;DownloadMover.Result retry=DownloadMover.moveCompleted(app,()->false,message->{});
        assertEquals(retry.summary(),1,retry.moved);assertEquals(created,provider.created);assertEquals(1,provider.files.size());
    }
    @Test public void cancellationRollsBackUncommittedCopyButCommittedFileFinishes() throws Exception {
        File source=StorageLocationTest.source(app,"cancel-move.mp4");Uri uri=Uri.fromFile(source);seed("video",uri,"video/mp4");
        AtomicBoolean cancelled=new AtomicBoolean();provider.afterWrite=()->cancelled.set(true);
        DownloadMover.Result stopped=DownloadMover.moveCompleted(app,cancelled::get,message->{});assertTrue(stopped.cancelled);assertTrue(source.exists());assertEquals(uri.toString(),TaskStore.get(app,"video").getString("uri"));assertTrue(provider.files.isEmpty());
    }
    @Test public void safRenameWithANewDocumentIdKeepsTaskAndDraftReferences() throws Exception {
        MediaExporter.Result source=MediaExporter.save(app,StorageLocationTest.source(app,"before-rename.mp4"),()->false);
        JSONObject task=seed("video",source.uri,"video/mp4");long id,project,clip;
        try(LibraryStore library=new LibraryStore(app)) {
            id=library.upsertCompletedDownload(task);library.savePlayback(id,34000);project=library.createProject("rename draft");clip=library.addClip(project,id,1000,5000);
            library.renameVideo(id,"renamed");
            JSONObject video=library.video(id);assertNotEquals(source.uri.toString(),video.getString("uri"));assertEquals("renamed.mp4",video.getString("fileName"));
            assertEquals(video.getString("uri"),TaskStore.get(app,"video").getString("uri"));assertEquals(34000,video.getLong("playbackMs"));
            assertEquals(clip,library.clips(project).get(0).getLong("id"));assertEquals(video.getString("uri"),library.clips(project).get(0).getString("uri"));
            assertFalse(library.referencesUri(source.uri.toString()));assertEquals(1,provider.files.size());
        }
    }

    @Test public void delayedCatalogueImportUsesMovedTaskInsteadOfItsStaleSnapshot() throws Exception {
        File source=StorageLocationTest.source(app,"not-yet-indexed.mp4");JSONObject stale=seed("video",Uri.fromFile(source),"video/mp4");
        assertEquals(1,DownloadMover.moveCompleted(app,()->false,message->{}).moved);
        try(LibraryStore library=new LibraryStore(app)) {
            long id=library.upsertCompletedDownload(stale);
            assertEquals(TaskStore.get(app,"video").getString("uri"),library.video(id).getString("uri"));
            assertFalse(library.video(id).getBoolean("missing"));
        }
    }

    @Test @Config(sdk=28) public void oldAndroidOnlyAcceptsMatchingTreeIdentityAsAnExistingDestination() throws Exception {
        java.lang.reflect.Method method=DownloadMover.class.getDeclaredMethod("inTarget",Context.class,Uri.class,DownloadLocation.Target.class);method.setAccessible(true);
        DownloadLocation.Target target=new DownloadLocation.Target(StorageLocationTest.folder("b"),"Folder b");
        Uri sameTree=DocumentsContract.buildDocumentUriUsingTree(StorageLocationTest.folder("b"),"b-existing");
        Uri plainDocument=DocumentsContract.buildDocumentUri(StorageLocationTest.AUTHORITY,"b-existing");
        assertEquals(true,method.invoke(null,app,sameTree,target));assertEquals(false,method.invoke(null,app,plainDocument,target));
    }

    @Test public void recoveryMustPublishPendingMediaStoreCopyBeforeUpdatingRecordsOrDeletingSource() throws Exception {
        DownloadLocation.reset(app);File source=StorageLocationTest.source(app,"interrupted-publication.mp4");Uri original=Uri.fromFile(source);seed("video",original,"video/mp4");
        PendingProvider media=new PendingProvider();media.file=StorageLocationTest.source(app,"pending-destination.mp4");
        FilesCopy(source,media.file);android.content.pm.ProviderInfo info=new android.content.pm.ProviderInfo();info.authority="media";media.attachInfo(app,info);
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media",media);
        Uri destination=Uri.parse("content://media/external/downloads/55");
        JSONObject journal=new JSONObject().put("from",original.toString()).put("to",destination.toString()).put("target","default").put("name","interrupted-publication.mp4").put("mime","video/mp4").put("phase","copying");
        app.getSharedPreferences("download-moves",0).edit().putString("move-fixture",journal.toString()).commit();
        media.failPublish=true;
        DownloadMover.Result refused=DownloadMover.moveCompleted(app,()->false,message->{});assertEquals(refused.summary(),1,refused.failed);assertTrue(source.exists());assertEquals(1,media.pending);assertEquals(original.toString(),TaskStore.get(app,"video").getString("uri"));
        media.failPublish=false;
        DownloadMover.Result recovered=DownloadMover.moveCompleted(app,()->false,message->{});assertEquals(recovered.summary(),1,recovered.moved);assertEquals(0,media.pending);assertFalse(source.exists());assertEquals(destination.toString(),TaskStore.get(app,"video").getString("uri"));
    }

    private static void FilesCopy(File from,File to)throws Exception {java.nio.file.Files.write(to.toPath(),java.nio.file.Files.readAllBytes(from.toPath()));}

    public static class PendingProvider extends android.content.ContentProvider {
        File file;int pending=1;boolean failPublish;
        @Override public boolean onCreate(){return true;}
        @Override public android.database.Cursor query(Uri uri,String[] projection,String selection,String[] args,String order) {
            String[] columns=projection==null?new String[]{android.provider.OpenableColumns.DISPLAY_NAME}:projection;
            android.database.MatrixCursor c=new android.database.MatrixCursor(columns);Object[] row=new Object[columns.length];
            for(int i=0;i<columns.length;i++) {
                if(columns[i].equals(android.provider.MediaStore.MediaColumns.RELATIVE_PATH))row[i]="Download/StreamPocket/";
                else if(columns[i].equals(android.provider.MediaStore.MediaColumns.IS_PENDING))row[i]=pending;
                else if(columns[i].equals(android.provider.OpenableColumns.DISPLAY_NAME))row[i]="interrupted-publication.mp4";
            }
            c.addRow(row);return c;
        }
        @Override public int update(Uri uri,android.content.ContentValues values,String selection,String[] args) {
            if(failPublish)throw new SecurityException("Synthetic pending publish failure");pending=values.getAsInteger(android.provider.MediaStore.MediaColumns.IS_PENDING);return 1;
        }
        @Override public android.os.ParcelFileDescriptor openFile(Uri uri,String mode)throws java.io.FileNotFoundException {return android.os.ParcelFileDescriptor.open(file,android.os.ParcelFileDescriptor.parseMode(mode));}
        @Override public String getType(Uri uri){return "video/mp4";}
        @Override public Uri insert(Uri uri,android.content.ContentValues values){return uri;}
        @Override public int delete(Uri uri,String selection,String[] args){return file.delete()?1:0;}
    }
}
