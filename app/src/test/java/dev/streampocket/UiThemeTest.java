package dev.streampocket;

import android.app.Application;
import android.content.res.Configuration;
import android.view.View;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35,application=Application.class)
public class UiThemeTest {
    private Application app;
    @Before public void init(){app=RuntimeEnvironment.getApplication();app.getSharedPreferences("config",0).edit().clear().commit();}
    @Test public void selectedPaletteOverridesSystemAndSystemFollowsConfiguration(){
        Configuration c=new Configuration(app.getResources().getConfiguration());c.uiMode=(c.uiMode&~Configuration.UI_MODE_NIGHT_MASK)|Configuration.UI_MODE_NIGHT_YES;app.getResources().updateConfiguration(c,app.getResources().getDisplayMetrics());
        assertTrue(UiTheme.get(app).dark);
        app.getSharedPreferences("config",0).edit().putString("appearance_theme","light").commit();assertFalse(UiTheme.get(app).dark);assertEquals(0xFFF5F6F8,UiTheme.get(app).bg);
        app.getSharedPreferences("config",0).edit().putString("appearance_theme","dark").commit();assertEquals(0xFF1B2024,UiTheme.get(app).surface);assertEquals(0xFFEEF1F2,UiTheme.get(app).ink);
    }
    @Test public void reducedMotionKeepsTheFinalVisibleState(){
        app.getSharedPreferences("config",0).edit().putBoolean("motion_enabled",false).commit();assertFalse(UiTheme.motionEnabled(app));
        View v=new View(app);v.setAlpha(.2f);v.setTranslationY(50);UiTheme.enter(v);assertEquals(1f,v.getAlpha(),.001f);assertEquals(0f,v.getTranslationY(),.001f);
    }
    @Test public void artworkOnlyAcceptsUncredentialedPublicHttpsNames(){
        assertTrue(MediaArtwork.isPublicImageUrl("https://pbs.twimg.com/media/public.jpg"));
        assertEquals("https://i0.hdslb.com/bfs/fixture.jpg",MediaArtwork.secureSource("http://i0.hdslb.com/bfs/fixture.jpg"));
        for(String value:new String[]{"http://example.com/a.jpg","file:///etc/passwd","content://media/video/1","https://user:secret@example.com/a.jpg","https://127.0.0.1/a.jpg","https://localhost/a.jpg","https://a.local/a.jpg","https://example.com:8443/a.jpg","https://[::1]/a.jpg"})assertFalse(value,MediaArtwork.isPublicImageUrl(value));
    }
}
