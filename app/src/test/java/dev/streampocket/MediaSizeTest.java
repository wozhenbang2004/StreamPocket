package dev.streampocket;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class MediaSizeTest {
    @Test public void zeroUnknownAndLargeFilesStayReadable() {
        assertEquals("未知大小", MediaSize.format(-1));
        assertEquals("0 B", MediaSize.format(0));
        assertEquals("1.0 KB", MediaSize.format(1024));
        assertEquals("2.5 GB", MediaSize.format(2684354560L));
        assertEquals("8.0 EB", MediaSize.format(Long.MAX_VALUE));
    }

    @Test public void estimatedTotalAndPartialBytesAreNotPresentedAsSavedFileSize() throws Exception {
        JSONObject task=new JSONObject().put("status","downloading").put("totalBytes",10485760).put("downloadedBytes",2097152).put("progressEstimated",true);
        assertEquals("已下载 2.0 MB / 约 10.0 MB",MediaSize.taskSummary(task));
        task.put("totalBytes",-1);
        assertEquals("已下载 2.0 MB · 总大小待获取",MediaSize.taskSummary(task));
        task.put("status","queued"); assertEquals("",MediaSize.taskSummary(task));
        task.put("status","completed").put("size",3145728);
        assertEquals("文件大小 3.0 MB",MediaSize.taskSummary(task));
    }
}
