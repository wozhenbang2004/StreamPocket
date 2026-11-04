package dev.streampocket;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
@LooperMode(LooperMode.Mode.PAUSED)
public final class DownloadPreferencesUiTest {
    @Test public void sourceAndLanguageSelectionRemainDraftUntilAttachmentApply() throws Exception {
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).create().start().resume()) {
            MainActivity activity=c.get();Engine.Media media=Engine.mediaFromMetadata(DownloadPreferencesTest.metadata(),"https://vimeo.com/76979871").get(0);
            media.thumbnail="https://example.invalid/cover.jpg";addMedia(activity,media);show(activity,media);
            PocketDialog parent=latest();parent.getListView().performItemClick(null,1,1);
            parent.getButton(PocketDialog.BUTTON_NEUTRAL).performClick();
            latest().getListView().performItemClick(null,2,2); // automatic only
            PocketDialog languages=latest();assertEquals(2,languages.getListView().getCount());
            languages.getListView().performItemClick(null,0,0); // English
            languages.getButton(PocketDialog.BUTTON_POSITIVE).performClick();
            assertFalse(media.saveSubtitles);assertFalse(media.saveCover);
            latest().getButton(PocketDialog.BUTTON_POSITIVE).performClick();
            assertTrue(media.saveSubtitles);assertTrue(media.saveCover);
            assertEquals(Collections.singletonList("en"),media.selectedSubtitles());assertEquals(SubtitleChoices.AUTOMATIC,media.subtitleMode);
            assertTrue(TaskStore.all(activity).isEmpty());
            InspectionInbox restored=new InspectionInbox();restored.restore(activity.getPreferences(0).getString("inspection_inbox",""));
            assertEquals(Collections.singletonList("en"),restored.media.get(0).selectedSubtitles());
        }
    }

    @Test public void leavingTheOuterPanelDiscardsAttachmentAndLanguageDrafts() throws Exception {
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).create().start().resume()) {
            MainActivity activity=c.get();Engine.Media media=Engine.mediaFromMetadata(DownloadPreferencesTest.metadata(),"https://vimeo.com/76979871").get(0);
            addMedia(activity,media);show(activity,media);latest().getListView().performItemClick(null,0,0);
            latest().getButton(PocketDialog.BUTTON_NEGATIVE).performClick();
            assertFalse(media.saveSubtitles);assertEquals(Collections.singletonList("zh-Hans"),media.selectedSubtitles());
            assertTrue(TaskStore.all(activity).isEmpty());
        }
    }

    private static PocketDialog latest(){return (PocketDialog)ShadowDialog.getLatestDialog();}
    @SuppressWarnings("unchecked") private static void addMedia(MainActivity activity,Engine.Media media)throws Exception{
        Field f=MainActivity.class.getDeclaredField("inspectedMedia");f.setAccessible(true);((List<Engine.Media>)f.get(activity)).add(media);
    }
    private static void show(MainActivity activity,Engine.Media media)throws Exception{
        Method m=MainActivity.class.getDeclaredMethod("showAttachments",Engine.Media.class);m.setAccessible(true);m.invoke(activity,media);
    }
}
