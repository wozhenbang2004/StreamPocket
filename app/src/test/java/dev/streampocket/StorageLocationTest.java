package dev.streampocket;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.os.Bundle;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class StorageLocationTest {
    static final String AUTHORITY="storage.fixture";
    static final int GRANTS=Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    Context app; TreeProvider provider;
    @Before public void setup() throws Exception {
        app=RuntimeEnvironment.getApplication();
        app.getSharedPreferences("download-location",0).edit().clear().commit();
        provider=install(app);
    }
    static TreeProvider install(Context app) throws Exception {
        TreeProvider provider=new TreeProvider();ProviderInfo info=new ProviderInfo();info.authority=AUTHORITY;info.name=TreeProvider.class.getName();
        info.packageName=app.getPackageName();info.exported=true;info.grantUriPermissions=true;info.readPermission="android.permission.MANAGE_DOCUMENTS";info.writePermission="android.permission.MANAGE_DOCUMENTS";
        ResolveInfo resolve=new ResolveInfo();resolve.providerInfo=info;
        Shadows.shadowOf(app.getPackageManager()).addResolveInfoForIntent(new Intent(DocumentsContract.PROVIDER_INTERFACE),resolve);
        provider.attachInfo(app,info);ShadowContentResolver.registerProviderInternal(AUTHORITY,provider);return provider;
    }
    static Uri folder(String id){return DocumentsContract.buildTreeDocumentUri(AUTHORITY,id);}
    static File source(Context context,String name) throws Exception {
        File file=new File(context.getCacheDir(),name);Files.write(file.toPath(),("synthetic recorded bytes for "+name).getBytes(java.nio.charset.StandardCharsets.UTF_8));return file;
    }
    @Test public void selectionRetainsPermissionAndResetKeepsExistingFileGrant() throws Exception {
        DownloadLocation.remember(app,folder("a"),GRANTS);
        assertEquals(folder("a"),DownloadLocation.tree(app));assertEquals("Folder a",DownloadLocation.label(app));
        assertEquals(folder("a"),DownloadLocation.snapshot(app).tree);
        DownloadLocation.reset(app);assertNull(DownloadLocation.tree(app));assertEquals(DownloadLocation.DEFAULT_LABEL,DownloadLocation.label(app));
        assertTrue(app.getContentResolver().getPersistedUriPermissions().stream().anyMatch(p->p.getUri().equals(folder("a"))&&p.isWritePermission()));
    }
    @Test public void revokedPermissionFailsWithoutWritingToDefaultDirectory() throws Exception {
        DownloadLocation.remember(app,folder("a"),GRANTS);
        app.getContentResolver().releasePersistableUriPermission(folder("a"),GRANTS);
        try{MediaExporter.save(app,source(app,"permission.mp4"),()->false);fail("Revoked selection must not fall back");}
        catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("授权"));}
        assertTrue(provider.files.isEmpty());assertEquals(folder("a"),DownloadLocation.tree(app));
    }
    @Test public void targetSnapshotSurvivesASettingChangeForMainAndAttachment() throws Exception {
        DownloadLocation.remember(app,folder("a"),GRANTS);DownloadLocation.Target target=DownloadLocation.snapshot(app);
        DownloadLocation.remember(app,folder("b"),GRANTS);
        MediaExporter.Result main=MediaExporter.save(app,source(app,"main.mp4"),()->false,target);
        MediaExporter.Result extra=MediaExporter.save(app,source(app,"subtitle.vtt"),()->false,target);
        assertEquals("a",DocumentsContract.getTreeDocumentId(main.uri));assertEquals("a",DocumentsContract.getTreeDocumentId(extra.uri));
        assertEquals(folder("b"),DownloadLocation.tree(app));assertEquals(2,provider.files.size());
    }
    @Test public void incompleteFlagsOrUnavailableFolderPreservePreviousSelection() throws Exception {
        DownloadLocation.remember(app,folder("a"),GRANTS);
        try{DownloadLocation.remember(app,folder("b"),Intent.FLAG_GRANT_READ_URI_PERMISSION);fail();}catch(java.io.IOException expected){}
        assertEquals(folder("a"),DownloadLocation.tree(app));
        provider.denyFolder=true;
        try{DownloadLocation.snapshot(app);fail();}catch(java.io.IOException expected){}
        assertEquals(folder("a"),DownloadLocation.tree(app));
    }

    @Test public void explicitRetryCanUseANewFolderWhilePausedResumeKeepsItsSnapshot() throws Exception {
        DownloadLocation.remember(app,folder("a"),GRANTS);DownloadLocation.Target original=DownloadLocation.snapshot(app);
        org.json.JSONObject failed=new org.json.JSONObject().put("id","retry-location").put("status","failed").put("uri","").put("destinationTree",folder("a").toString()).put("savedLocation",original.label);
        org.json.JSONObject paused=new org.json.JSONObject().put("id","paused-location").put("status","paused").put("uri","").put("destinationTree",folder("a").toString()).put("savedLocation",original.label);
        TaskStore.all(app);app.getSharedPreferences("tasks",0).edit().putString("items",new org.json.JSONArray().put(failed).put(paused).toString()).commit();
        DownloadLocation.remember(app,folder("b"),GRANTS);
        assertTrue(TaskStore.markQueued(app,"retry-location"));assertTrue(TaskStore.markQueued(app,"paused-location"));
        assertFalse(TaskStore.get(app,"retry-location").has("destinationTree"));
        assertEquals(folder("b"),DownloadLocation.forTask(app,TaskStore.get(app,"retry-location")).tree);
        assertEquals(folder("a"),DownloadLocation.forTask(app,TaskStore.get(app,"paused-location")).tree);
    }

    public static class TreeProvider extends ContentProvider {
        final Map<String,File> files=new HashMap<>();final Map<String,String> names=new HashMap<>();
        int created; boolean failWrite,corruptRead,denyDelete,denyFolder; Runnable afterWrite;
        @Override public boolean onCreate(){return true;}
        public Cursor queryDocument(String id,String[] projection)throws FileNotFoundException {
            if(denyFolder&&(id.equals("a")||id.equals("b")))throw new FileNotFoundException("Folder unavailable");
            String[] columns=projection==null?new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_FLAGS,DocumentsContract.Document.COLUMN_SIZE}:projection;
            MatrixCursor result=new MatrixCursor(columns);boolean directory=id.equals("a")||id.equals("b");File file=files.get(id);
            if(!directory&&(file==null||!file.exists()))throw new FileNotFoundException(id);
            Object[] row=new Object[columns.length];
            for(int i=0;i<columns.length;i++)switch(columns[i]) {
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID:row[i]=id;break;
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME:row[i]=directory?"Folder "+id:names.get(id);break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE:row[i]=directory?DocumentsContract.Document.MIME_TYPE_DIR:"application/octet-stream";break;
                case DocumentsContract.Document.COLUMN_FLAGS:row[i]=directory?DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE:DocumentsContract.Document.FLAG_SUPPORTS_DELETE|DocumentsContract.Document.FLAG_SUPPORTS_WRITE;break;
                case DocumentsContract.Document.COLUMN_SIZE:row[i]=directory?0L:file.length();break;
            }
            result.addRow(row);return result;
        }
        public String createDocument(String parent,String mime,String name)throws FileNotFoundException {
            try{String id=parent+"-"+(++created);File file=File.createTempFile("saf-fixture-",".bin",getContext().getCacheDir());files.put(id,file);names.put(id,name);return id;}
            catch(java.io.IOException error){throw new FileNotFoundException(error.toString());}
        }
        public boolean isChildDocument(String parent,String child){return child.startsWith(parent+"-");}
        public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal)throws FileNotFoundException {
            File file=files.get(id);if(file==null||!file.exists())throw new FileNotFoundException(id);
            if(mode.contains("w")&&failWrite)throw new FileNotFoundException("Synthetic target write failure");
            if(!mode.contains("w")&&corruptRead)try{File corrupt=File.createTempFile("corrupt-fixture-",".bin",getContext().getCacheDir());Files.write(corrupt.toPath(),new byte[]{7,8,9});return ParcelFileDescriptor.open(corrupt,ParcelFileDescriptor.MODE_READ_ONLY);}catch(java.io.IOException error){throw new FileNotFoundException(error.toString());}
            if(mode.contains("w")&&afterWrite!=null)afterWrite.run();
            return ParcelFileDescriptor.open(file,ParcelFileDescriptor.parseMode(mode));
        }
        public void deleteDocument(String id)throws FileNotFoundException {
            if(denyDelete)throw new SecurityException("Synthetic deletion refusal");File file=files.get(id);if(file!=null)file.delete();files.remove(id);names.remove(id);
        }
        public String renameDocument(String id,String name)throws FileNotFoundException {
            File file=files.remove(id);if(file==null)throw new FileNotFoundException(id);
            names.remove(id);String renamed=id+"-renamed";files.put(renamed,file);names.put(renamed,name);return renamed;
        }
        @Override public Cursor query(Uri uri,String[] projection,String selection,String[] arguments,String order) {
            try{return queryDocument(DocumentsContract.getDocumentId(uri),projection);}catch(FileNotFoundException missing){throw new IllegalArgumentException(missing);}
        }
        @Override public String getType(Uri uri){return "application/octet-stream";}
        @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
        @Override public int update(Uri uri,ContentValues values,String selection,String[] arguments){throw new UnsupportedOperationException("SAF requires renameDocument");}
        @Override public int delete(Uri uri,String selection,String[] arguments){try{deleteDocument(DocumentsContract.getDocumentId(uri));return 1;}catch(FileNotFoundException absent){return 0;}}
        @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{return openDocument(DocumentsContract.getDocumentId(uri),mode,null);}
        @Override public Bundle call(String method,String argument,Bundle extras) {
            Uri uri=extras.getParcelable("uri");Bundle result=new Bundle();
            try {
                String id=DocumentsContract.getDocumentId(uri);
                if("android:createDocument".equals(method))result.putParcelable("uri",DocumentsContract.buildDocumentUriUsingTree(uri,createDocument(id,extras.getString("mimeType"),extras.getString("_display_name"))));
                else if("android:deleteDocument".equals(method))deleteDocument(id);
                else if("android:renameDocument".equals(method))result.putParcelable("uri",DocumentsContract.buildDocumentUriUsingTree(uri,renameDocument(id,extras.getString("_display_name"))));
                else if("android:isChildDocument".equals(method)){Uri child=extras.getParcelable("targetUri");result.putBoolean("result",isChildDocument(id,DocumentsContract.getDocumentId(child)));}
                else throw new UnsupportedOperationException(method);
                return result;
            }catch(FileNotFoundException missing){throw new IllegalArgumentException(missing);}
        }
    }
}
