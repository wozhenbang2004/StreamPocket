package dev.streampocket;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

/** Ordinary download URLs use synthetic signatures, never customer credentials. */
public final class LinkBatchFilesTest {
    @Test public void signedQueryKeepsEncodingOrderAndRepeatedParameters() {
        String url="https://files.example.org/releases/build.zip?credential=fixture%2F20261107%2Fregion&signature=A%2Bb%2Fc%3D&part=2&part=1";
        LinkBatch.Result result=LinkBatch.parseFiles("文件链接：\n"+url+"#browser-only");
        assertEquals(Collections.singletonList(url),result.urls);
        assertTrue(result.invalid.isEmpty());
    }

    @Test public void legalQueryPunctuationIsNotTreatedAsVideoShareText() {
        String url="https://files.example.org/archive.zip?token=a,b;c&signature=fixture!";
        assertEquals(Collections.singletonList(url),LinkBatch.parseFiles(url).urls);
    }

    @Test public void platformLookingUrlsDoNotLoseFileParametersOrCollapseByVideoId() {
        String first="https://www.youtube.com/watch?v=BaW_jenozKc&signature=first%2Bvalue&download=1";
        String second="https://www.youtube.com/watch?v=BaW_jenozKc&signature=second%2Bvalue&download=1";
        LinkBatch.Result result=LinkBatch.parseFiles(first+"\n"+second);
        assertEquals(Arrays.asList(first,second),result.urls);
        assertEquals(0,result.duplicates);
    }

    @Test public void exactDuplicatesAreMergedWithoutReorderingDistinctFileRequests() {
        String first="https://files.example.org/report.pdf?signature=one";
        String second="https://files.example.org/report.pdf?signature=two";
        LinkBatch.Result result=LinkBatch.parseFiles(first+"\n"+second+"\n"+first+"#viewer");
        assertEquals(Arrays.asList(first,second),result.urls);
        assertEquals(1,result.duplicates);
    }

    @Test public void unsafeLinksAreSkippedAndNeverBecomeAnAcceptedFileRequest() {
        String publicFile="https://files.example.org/report.pdf";
        LinkBatch.Result result=LinkBatch.parseFiles(publicFile+"\nhttps://127.0.0.1/private\nhttps://192.168.1.9/private\nhttps://user:fixture@example.org/private");
        assertEquals(Collections.singletonList(publicFile),result.urls);
        assertEquals(3,result.invalid.size());
        assertThrows(IllegalArgumentException.class,()->LinkBatch.parseFiles("file:///sdcard/private.txt"));
    }

    @Test public void fileBatchRetainsTheExistingBoundRatherThanAcceptingAnUnboundedList() {
        StringBuilder links=new StringBuilder();
        for(int i=0;i<100;i++)links.append("https://files.example.org/").append(i).append(".bin\n");
        assertEquals(100,LinkBatch.parseFiles(links.toString()).urls.size());
        links.append("https://files.example.org/overflow.bin");
        assertThrows(IllegalArgumentException.class,()->LinkBatch.parseFiles(links.toString()));
    }
}
