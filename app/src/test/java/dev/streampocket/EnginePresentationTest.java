package dev.streampocket;

import android.app.Application;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35,application=Application.class)
public class EnginePresentationTest {
    @Test public void extractedArtworkAndSizeSurviveTaskRoundTripWithoutInventingUnknownBytes()throws Exception{
        Engine engine=new Engine(RuntimeEnvironment.getApplication());Method parse=Engine.class.getDeclaredMethod("parseMedia",JSONObject.class,String.class,int.class);parse.setAccessible(true);
        JSONObject raw=new JSONObject().put("id","fixture").put("title","真实元数据夹具").put("thumbnail","https://pbs.twimg.com/media/fixture.jpg").put("width",720).put("height",960).put("filesize_approx",123456L);
        Engine.Media m=(Engine.Media)parse.invoke(engine,raw,"https://x.com/example/status/123",0);
        Engine.Media restored=Engine.Media.fromJson(m.toJson());assertEquals(m.thumbnail,restored.thumbnail);assertEquals(720,restored.width);assertEquals(960,restored.height);assertEquals(123456L,restored.filesize);assertTrue(restored.filesizeEstimated);
        raw.remove("filesize_approx");m=(Engine.Media)parse.invoke(engine,raw,"https://x.com/example/status/123",0);assertEquals(-1,m.filesize);assertFalse(m.filesizeEstimated);
        Engine.Media old=Engine.Media.fromJson(new JSONObject().put("id","old"));assertEquals("",old.thumbnail);assertEquals(-1,old.filesize);assertFalse(old.qualities.isEmpty());
    }
}
