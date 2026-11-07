package dev.streampocket;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import static org.junit.Assert.*;

/** Real ContentResolver copies and readback through the existing isolated SAF fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public final class MediaExporterFileTest {
    private Context app;
    private FileProvider provider;
    private DownloadLocation.Target target;

    @Before public void setup() throws Exception {
        app=RuntimeEnvironment.getApplication();
        app.getSharedPreferences("download-location",0).edit().clear().commit();
        provider=new FileProvider();
        ProviderInfo info=new ProviderInfo();info.authority=StorageLocationTest.AUTHORITY;
        info.name=FileProvider.class.getName();info.packageName=app.getPackageName();
        info.exported=true;info.grantUriPermissions=true;
        info.readPermission="android.permission.MANAGE_DOCUMENTS";info.writePermission="android.permission.MANAGE_DOCUMENTS";
        ResolveInfo resolve=new ResolveInfo();resolve.providerInfo=info;
        Shadows.shadowOf(app.getPackageManager()).addResolveInfoForIntent(new Intent(DocumentsContract.PROVIDER_INTERFACE),resolve);
        provider.attachInfo(app,info);ShadowContentResolver.registerProviderInternal(info.authority,provider);
        DownloadLocation.remember(app,StorageLocationTest.folder("a"),StorageLocationTest.GRANTS);
        target=DownloadLocation.snapshot(app);
    }

    @Test public void zeroByteFileIsPublishedWithResponseNameMimeAndEmptyDigest() throws Exception {
        File source=source(new byte[0]);
        MediaExporter.Result saved=MediaExporter.saveFile(app,source,"说明（最终）.txt","text/plain",()->false,target);
        String id=DocumentsContract.getDocumentId(saved.uri);
        assertEquals("说明（最终）.txt",provider.names.get(id));
        assertEquals("text/plain",provider.mimes.get(id));
        assertEquals("text/plain",saved.mime);assertEquals(0,saved.bytes);
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",saved.sha256);
        try(InputStream input=app.getContentResolver().openInputStream(saved.uri)) {
            assertNotNull(input);assertEquals(-1,input.read());
        }
        assertTrue(source.isFile());assertEquals(1,provider.files.size());
    }

    @Test public void temporaryExtensionDoesNotReplaceTheChosenFilenameOrMime() throws Exception {
        byte[] expected=new byte[]{0,1,2,3,10,13,127,(byte)255};
        File source=source(expected);
        MediaExporter.Result saved=MediaExporter.saveFile(app,source,"工具与文档.zip","application/zip",()->false,target);
        String id=DocumentsContract.getDocumentId(saved.uri);
        assertEquals("工具与文档.zip",provider.names.get(id));
        assertEquals("application/zip",provider.mimes.get(id));
        assertEquals("application/zip",saved.mime);assertEquals(expected.length,saved.bytes);
        try(InputStream input=app.getContentResolver().openInputStream(saved.uri)) {
            assertNotNull(input);assertArrayEquals(expected,input.readAllBytes());
        }
        assertArrayEquals(expected,Files.readAllBytes(source.toPath()));
    }

    @Test public void cancellingAfterDestinationAllocationRemovesTheCopyAndKeepsSource() throws Exception {
        byte[] expected=new byte[256*1024];java.util.Arrays.fill(expected,(byte)37);
        File source=source(expected);AtomicBoolean cancelled=new AtomicBoolean();
        provider.afterWrite=()->cancelled.set(true);
        assertThrows(InterruptedIOException.class,()->MediaExporter.saveFile(app,source,"large.bin","application/octet-stream",cancelled::get,target));
        assertEquals(1,provider.created);assertTrue(provider.files.isEmpty());
        assertArrayEquals(expected,Files.readAllBytes(source.toPath()));
    }

    @Test public void corruptReadbackRejectsTheNewFileWithoutDeletingTheSource() throws Exception {
        byte[] expected="complete ordinary file bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        File source=source(expected);provider.corruptRead=true;
        IOException failure=assertThrows(IOException.class,()->MediaExporter.saveFile(app,source,"result.bin","application/octet-stream",()->false,target));
        assertTrue(failure.getMessage().contains("回读校验失败"));
        assertTrue(provider.files.isEmpty());assertArrayEquals(expected,Files.readAllBytes(source.toPath()));
    }

    @Test public void allowingEmptyOrdinaryFilesDoesNotWeakenTheMediaSaveContract() throws Exception {
        File empty=source(new byte[0]);
        assertThrows(IOException.class,()->MediaExporter.save(app,empty,()->false,target));
        assertEquals(0,provider.created);assertTrue(provider.files.isEmpty());assertTrue(empty.isFile());
    }

    private File source(byte[] bytes) throws IOException {
        File source=File.createTempFile("ordinary-file-fixture-",".part",app.getCacheDir());
        Files.write(source.toPath(),bytes);return source;
    }

    public static final class FileProvider extends StorageLocationTest.TreeProvider {
        final Map<String,String> mimes=new HashMap<>();
        @Override public String createDocument(String parent,String mime,String name) throws FileNotFoundException {
            String id=super.createDocument(parent,mime,name);mimes.put(id,mime);return id;
        }
        @Override public void deleteDocument(String id) throws FileNotFoundException {
            super.deleteDocument(id);mimes.remove(id);
        }
    }
}
