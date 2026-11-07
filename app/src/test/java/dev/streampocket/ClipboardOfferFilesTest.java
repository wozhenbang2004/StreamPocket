package dev.streampocket;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

/** Repeat suppression follows complete file requests, including synthetic signed parameters. */
public final class ClipboardOfferFilesTest {
    @Test public void youtubeSignaturesRemainDifferentFileRequests() {
        String first="https://www.youtube.com/watch?v=BaW_jenozKc&signature=first%2Bvalue&download=1";
        String second="https://www.youtube.com/watch?v=BaW_jenozKc&signature=second%2Bvalue&download=1";
        assertNotEquals(ClipboardOffer.fingerprint(Collections.singletonList(first),true),ClipboardOffer.fingerprint(Collections.singletonList(second),true));
        assertFalse(ClipboardOffer.alreadyInDraft(Collections.singletonList(second),first,true));
        assertFalse(ClipboardOffer.alreadyInDraft(Arrays.asList(first,second),first,true));
        assertTrue(ClipboardOffer.alreadyInDraft(Arrays.asList(first,second),first+"\n"+second,true));
    }

    @Test public void bilibiliSignedTimestampIsNotTreatedAsShareTrackingForFiles() {
        String first="https://www.bilibili.com/video/BV1nx411u79K?timestamp=100&sign=fixture%2Bvalue";
        String second="https://www.bilibili.com/video/BV1nx411u79K?timestamp=101&sign=fixture%2Bvalue";
        assertNotEquals(ClipboardOffer.fingerprint(Collections.singletonList(first),true),ClipboardOffer.fingerprint(Collections.singletonList(second),true));
        assertFalse(ClipboardOffer.alreadyInDraft(Collections.singletonList(second),first,true));
        assertTrue(ClipboardOffer.alreadyInDraft(Arrays.asList(first,second),"文件：\n"+second+"\n"+first,true));
    }

    @Test public void reorderingClipboardItemsAndExactDuplicatesDoNotRepeatAnOffer() {
        String first="https://files.example.org/report.pdf?part=2&part=1&signature=A%2Bb%3D";
        String second="https://files.example.org/archive.zip?signature=fixture";
        assertEquals(ClipboardOffer.fingerprint(Arrays.asList(first,second),true),ClipboardOffer.fingerprint(Arrays.asList(second,first,first),true));
        assertTrue(ClipboardOffer.alreadyInDraft(Arrays.asList(first,second),"下载清单\n"+second+"\n"+first+"\n"+first,true));
        assertFalse(ClipboardOffer.alreadyInDraft(Collections.singletonList(first),first.replace("part=2&part=1","part=1&part=2"),true));
    }

    @Test public void authorityAndBrowserFragmentDoNotCreateAnExtraFileOffer() {
        String normalized="https://files.example.org/report.pdf?signature=A%2Bb%3D";
        String copied="HTTPS://FILES.EXAMPLE.ORG/report.pdf?signature=A%2Bb%3D#page=2";
        assertEquals(ClipboardOffer.fingerprint(Collections.singletonList(normalized),true),ClipboardOffer.fingerprint(Collections.singletonList(copied),true));
        assertTrue(ClipboardOffer.alreadyInDraft(Collections.singletonList(normalized),copied,true));
    }

    @Test public void fileAndVideoSeenDigestsAreIndependentEvenForAnOrdinaryUrl() {
        String url="https://files.example.org/report.pdf?signature=fixture";
        assertNotEquals(ClipboardOffer.fingerprint(Collections.singletonList(url),false),ClipboardOffer.fingerprint(Collections.singletonList(url),true));
        assertNotEquals(ClipboardOffer.fingerprint(Collections.emptyList(),false),ClipboardOffer.fingerprint(Collections.emptyList(),true));
        assertFalse(ClipboardOffer.alreadyInDraft(Collections.singletonList(url),"没有链接的备注",true));
        assertFalse(ClipboardOffer.alreadyInDraft(Collections.singletonList(url),"",true));
    }

    @Test public void existingMediaInterfacesStillRecognizeTheSameVideoAcrossAliases() {
        String watch="https://www.youtube.com/watch?v=BaW_jenozKc&signature=fixture";
        String shortUrl="https://youtu.be/BaW_jenozKc";
        assertEquals(ClipboardOffer.fingerprint(Collections.singletonList(watch)),ClipboardOffer.fingerprint(Collections.singletonList(shortUrl)));
        assertEquals(ClipboardOffer.fingerprint(Collections.singletonList(watch)),ClipboardOffer.fingerprint(Collections.singletonList(watch),false));
        assertTrue(ClipboardOffer.alreadyInDraft(Collections.singletonList(watch),shortUrl));
        assertTrue(ClipboardOffer.alreadyInDraft(Collections.singletonList(watch),shortUrl,false));
    }
}
