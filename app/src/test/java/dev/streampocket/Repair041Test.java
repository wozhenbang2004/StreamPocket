package dev.streampocket;

import java.lang.reflect.Method;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public final class Repair041Test {
    @Test public void missingFormatsAndUnknownErrorsDoNotAskForLogin() throws Exception {
        for(String error:new String[]{"ERROR: No video formats found", "ERROR: unexpected source response", "ERROR: HTTP Error 403: Forbidden"})
            assertFalse(error,offersLogin(error));
        assertTrue(offersLogin("ERROR: Login required"));
    }

    @Test public void noFormatsCopyDoesNotAssumeCredentialsAreMissing() {
        assertFalse(CoreRules.inspectionMessage(new Exception("ERROR: No video formats found")).contains("登录"));
        assertFalse(CoreRules.userMessage(new Exception("ERROR: No video formats found")).contains("登录"));
    }

    @Test public void oldPersistedNoFormatsCopyIsRefreshedWithoutOverwritingOtherErrors() throws Exception {
        Method m=MainActivity.class.getDeclaredMethod("taskDisplayError",org.json.JSONObject.class);m.setAccessible(true);
        org.json.JSONObject task=new org.json.JSONObject().put("status","failed").put("userError","暂时没有画质，请重新登录").put("log","ERROR: No video formats found");
        assertFalse(((String)m.invoke(null,task)).contains("登录"));
        task.put("log","ERROR: custom destination failed").put("userError","无法写入所选文件夹");assertEquals("无法写入所选文件夹",m.invoke(null,task));
    }

    @Test public void pausedTaskPermissionFollowsItsOwnFolderAndFreshFailedRetriesUseTheNewFolder() throws Exception {
        org.json.JSONObject task=new org.json.JSONObject().put("status","paused").put("destinationTree","");
        assertTrue(MainActivity.taskUsesDefaultLocation(task,false));
        task.put("destinationTree","content://fixture/tree/media");assertFalse(MainActivity.taskUsesDefaultLocation(task,true));
        task.put("status","failed");assertTrue(MainActivity.taskUsesDefaultLocation(task,true));assertFalse(MainActivity.taskUsesDefaultLocation(task,false));
        task.put("attachments",new org.json.JSONArray().put(new org.json.JSONObject().put("uri","content://fixture/file")));
        assertFalse(MainActivity.taskUsesDefaultLocation(task,true));
    }

    @Test public void deletingAnActiveInspectionDiscardsLateSuccessAndPersistsRemoval() throws Exception {
        InspectionInbox inbox=new InspectionInbox();inbox.add(Collections.singletonList("https://x.com/u/status/1"));
        InspectionInbox.Job job=inbox.next();job.state="parsing";inbox.remove(job);
        assertTrue(job.cancelled);assertTrue(inbox.jobs.isEmpty());
        Engine.Media media=new Engine.Media();media.url=job.url;media.id="1";media.title="被删除的解析";
        inbox.complete(job,Collections.singletonList(media));assertTrue(inbox.media.isEmpty());
        InspectionInbox restored=new InspectionInbox();restored.restore(inbox.json());assertTrue(restored.jobs.isEmpty());assertTrue(restored.media.isEmpty());
    }

    @Test public void deletedInspectionsCannotReturnAsFailures() {
        InspectionInbox inbox=new InspectionInbox();inbox.add(Collections.singletonList("https://x.com/u/status/2"));
        InspectionInbox.Job job=inbox.next();job.state="parsing";inbox.remove(job);inbox.fail(job,new Exception("late failure"));
        assertTrue(inbox.jobs.isEmpty());assertNull(inbox.next());
    }

    private static boolean offersLogin(String error) throws Exception {
        Method m=MainActivity.class.getDeclaredMethod("canOfferLogin",String.class,String.class);m.setAccessible(true);
        return (Boolean)m.invoke(null,"https://x.com/u/status/1",error);
    }
}
