package dev.streampocket;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Wrapper compatibility fixtures; these are not claims of live platform access. */
public final class EngineMetadataTest {
    @Test public void choosesPlaylistRootAfterIndividualDumpLines() throws Exception {
        String output = "{\"id\":\"clip1\",\"title\":\"First\"}\n"
                + "{\"entries\":[{\"id\":\"clip1\",\"title\":\"First\"},null],\"id\":\"post\"}\n";
        JSONObject root = Engine.lastJsonObject(output);
        assertEquals("post", root.getString("id"));
        assertEquals("clip1", root.getJSONArray("entries").getJSONObject(0).getString("id"));
        assertTrue(root.getJSONArray("entries").isNull(1));
    }
    @Test public void ignoresNonJsonStatusLinesWithoutLosingVideo() throws Exception {
        assertEquals("one", Engine.lastJsonObject("[download] preparing\n{\"id\":\"one\"}\n").getString("id"));
    }
    @Test(expected = Exception.class) public void refusesStatusOutputWithoutMediaMetadata() throws Exception {
        Engine.lastJsonObject("WARNING: unavailable\n");
    }
    @Test public void preservesRealErrorWhenWrapperReturnsLiteralNull() throws Exception {
        try {
            Engine.metadataFromResponse("null\n", "ERROR: HTTP Error 403: Forbidden", 1);
            fail("Literal null must not count as partial metadata");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("403"));
        }
    }
    @Test public void rejectsAllNullPlaylistAndKeepsSourceReason() throws Exception {
        try {
            Engine.metadataFromResponse("{\"entries\":[null]}\n", "ERROR: Tweet is unavailable", 1);
            fail("A playlist without a usable item must fail");
        } catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("unavailable")); }
    }
    @Test public void keepsUsablePartialPlaylistDespiteNonZeroExit() throws Exception {
        JSONObject root = Engine.metadataFromResponse("{\"id\":\"post\",\"entries\":[{\"id\":\"clip\",\"url\":\"https://example.invalid/clip.mp4\"},null]}\n", "ERROR: second item unavailable", 1);
        assertEquals("post",root.getString("id"));
    }
    @Test public void retainsEarlierPlayableDumpWhenLastCardHasNoFormats() throws Exception {
        JSONObject root=Engine.metadataFromResponse("{\"id\":\"clip\",\"playlist_index\":2,\"url\":\"https://example.org/video.mp4\"}\n{\"entries\":[null]}\n","ERROR: [twitter] card: No video formats found!",1);
        assertEquals("clip",root.getJSONArray("entries").getJSONObject(0).getString("id"));
        assertEquals(2,root.getJSONArray("entries").getJSONObject(0).getInt("playlist_index"));
        assertEquals(2,Engine.mediaFromMetadata(root,"https://x.com/i/status/123").get(0).playlistIndex);
    }
    @Test public void noFormatsExplainsUncertaintyWithoutCallingItANetworkFailure(){
        java.io.IOException error=new java.io.IOException("ERROR: [twitter] 2116424978870456755: No video formats found!");
        assertEquals(SourceFailure.Kind.NO_FORMATS,SourceFailure.kind(error));assertFalse(SourceFailure.retryable(error));assertTrue(CoreRules.inspectionMessage(error).contains("不能确定是代理故障"));
        assertEquals(SourceFailure.Kind.TIMEOUT,SourceFailure.kind("ERROR: connection timed out"));
    }
    @Test public void redactsCredentialsInPreservedParserError() throws Exception {
        try {
            Engine.metadataFromResponse("null", "Cookie: auth_token=private-value\nHTTP 403", 1);
            fail("Expected source failure");
        } catch (java.io.IOException expected) {
            assertFalse(expected.getMessage().contains("private-value"));
            assertTrue(expected.getMessage().contains("403"));
        }
    }
}
