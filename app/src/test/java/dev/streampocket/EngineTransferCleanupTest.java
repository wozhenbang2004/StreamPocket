package dev.streampocket;

import android.content.Context;
import java.io.File;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public final class EngineTransferCleanupTest {
    @Test public void requestCleanupDeletesOnlyItsPrivateMetadataAndCookieSnapshot() throws Exception {
        Context app=RuntimeEnvironment.getApplication();
        File metadata=new File(app.getCacheDir(),"transfer-metadata/download-fixture/.streampocket-bili-meta-one/selected.meta4");
        File other=new File(app.getCacheDir(),"transfer-metadata/download-other/keep.meta4");
        File cookie=new File(app.getCacheDir(),"auth-snapshots/cookies-download-fixture.txt");
        File partial=new File(app.getExternalFilesDir("downloads"),"fixture/media.mp4.part");
        File accounts=new File(app.getFilesDir(),"accounts/fixture-master.enc");
        for(File file:new File[]{metadata,other,cookie,partial,accounts}) {
            assertTrue(file.getParentFile().isDirectory()||file.getParentFile().mkdirs());
            Files.write(file.toPath(),new byte[]{1,2,3});
        }
        new Engine(app).cleanupRequest("download-fixture");
        assertFalse(metadata.exists()); assertFalse(cookie.exists());
        assertTrue(other.exists()); assertTrue(partial.exists()); assertTrue(accounts.exists());
        assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(partial.toPath()));
        assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(accounts.toPath()));
    }
}
