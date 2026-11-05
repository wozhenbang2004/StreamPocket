package dev.streampocket;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;

/**
 * Actual Android View rendering in Robolectric's native graphics runtime.
 * These fixtures do not run extraction, downloads, services or MediaStore.
 * Output is a renderer preview, not an installed-phone screenshot.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class WhiteUiRenderTest {
    private static final File OUTPUT = new File(System.getProperty("streampocket.ui.output", "build/ui-render"));

    @Test public void rendersWhiteCoreJourneyAndPreservesDraft() throws Exception {
        RuntimeEnvironment.getApplication().getSharedPreferences("tasks", 0).edit().clear().commit();
        Bundle emptyState = new Bundle();
        emptyState.putString("draft", "");
        emptyState.putString("tab", "new");
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class);
        try {
            MainActivity activity = controller.create(emptyState).start().resume().visible().get();
            idle();
            View root = field(activity, "root", View.class);
            assertTrue("Main surface must be a solid color", root.getBackground() instanceof ColorDrawable);
            assertEquals("Current authorized light/dark palette", UiTheme.get(activity).bg, ((ColorDrawable) root.getBackground()).getColor());
            capture(activity.getWindow().getDecorView(), "01-home-empty");

            EditText input = field(activity, "input", EditText.class);
            input.setText("这不是链接");
            field(activity, "parse", Button.class).performClick();
            idle();
            assertEquals("Rejected input must remain editable", "这不是链接", input.getText().toString());
            String error = field(activity, "feedback", TextView.class).getText().toString();
            assertTrue("Input error should explain the missing link", error.contains("链接"));
            assertFalse("Analysis errors must not claim downloaded data", error.contains("临时数据"));
            capture(activity.getWindow().getDecorView(), "02-input-error");

            String draft = "https://www.bilibili.com/video/BV1nx411u79K";
            input.setText(draft);
            tab(activity, "settings");
            capture(activity.getWindow().getDecorView(), "03-settings");
            String[] panels = {"showLoginSettings", "showNetworkSettings", "showParserSettings", "showAboutSettings"};
            String[] panelNames = {"03a-login-settings", "03b-network-settings", "03c-parser-settings", "03d-about-settings"};
            for (int i = 0; i < panels.length; i++) {
                invoke(activity, panels[i], new Class<?>[0]);
                idle();
                Dialog panel = ShadowDialog.getLatestDialog();
                assertNotNull("Settings should disclose the selected section", panel);
                assertTrue(panel.isShowing());
                capture(panel.getWindow().getDecorView(), panelNames[i]);
                panel.dismiss();
                idle();
            }
            tab(activity, "queue");
            capture(activity.getWindow().getDecorView(), "04-download-empty");
            assertTrue("Fixture must not enqueue any real task", TaskStore.all(activity).isEmpty());
            tab(activity, "new");
            assertEquals("Navigation must preserve the original link", draft,
                    field(activity, "input", EditText.class).getText().toString());
            capture(activity.getWindow().getDecorView(), "05-home-with-link");

            setField(activity, "inspector", (InspectionBatch.Inspector)(url, progress, cancelled) -> {
                while (!cancelled.getAsBoolean()) Thread.sleep(10);
                throw new InterruptedException();
            });
            invoke(activity, "analyze", new Class<?>[0]);
            field(activity, "input", EditText.class).setText(draft);
            tab(activity, "new");
            invoke(activity, "cancelAnalysis", new Class<?>[0]);
            field(activity, "io", java.util.concurrent.ExecutorService.class).submit(() -> {}).get(5, java.util.concurrent.TimeUnit.SECONDS);
            idle();
            assertEquals("Cancellation must preserve the original link", draft,
                    field(activity, "input", EditText.class).getText().toString());
            assertTrue("Cancellation must enable retry", field(activity, "parse", Button.class).isEnabled());

            Engine.Media fixture = new Engine.Media();
            fixture.url = draft;
            fixture.id = "ui-fixture-only";
            fixture.title = "界面布局样例：一段较长的中文视频标题，用于检查换行与画质选择";
            fixture.uploader = "界面测试样例";
            fixture.platform = "哔哩哔哩";
            fixture.duration = 90;
            fixture.qualities.add(new Engine.Quality("best", "最高可用画质", "bv*+ba/b"));
            fixture.qualities.add(new Engine.Quality("1080", "1080p", "b[height=1080]"));
            fixture.qualities.add(new Engine.Quality("720", "720p", "b[height=720]"));
            invoke(activity, "showMedia", new Class<?>[]{java.util.List.class}, Collections.singletonList(fixture));
            idle();
            View resultSurface=activity.getWindow().getDecorView();
            capture(resultSurface, "06-quality-inline-fixture");
            TextView resultTitle = findText(resultSurface, fixture.title);
            assertNotNull(resultTitle);
            assertEquals("The fixture must exercise a two-line title", 2, resultTitle.getLineCount());
            Rect visibleTitle = new Rect();
            assertTrue(resultTitle.getLocalVisibleRect(visibleTitle));
            assertEquals("The action row must not clip the title through baseline alignment",
                    resultTitle.getHeight(), visibleTitle.height());
            Button quality = findButton(resultSurface, "最高可用画质");
            assertNotNull("Current quality must remain discoverable", quality);
            quality.performClick();
            idle();
            Dialog choice = ShadowDialog.getLatestDialog();
            assertTrue("Quality control should reveal its choices", choice instanceof PocketDialog);
            capture(choice.getWindow().getDecorView(), "07-quality-options-fixture");
            PocketDialog choices = (PocketDialog) choice;
            choices.getListView().performItemClick(null, 1, 1);
            idle();
            assertNotNull("Selected quality must be shown on the current result without reopening choices",
                    findButton(activity.getWindow().getDecorView(), "1080"));
            tab(activity,"settings");tab(activity,"new");
            assertNotNull("Inline quality survives navigation",findButton(activity.getWindow().getDecorView(),"1080"));
            assertEquals("Returning to the inline result must preserve the link", draft,
                    field(activity, "input", EditText.class).getText().toString());
            assertTrue("UI fixtures must not start a download", TaskStore.all(activity).isEmpty());

            setField(activity, "lastInspectionMessage", "解析失败，链接已保留。可查看具体原因后重试。");
            setField(activity, "lastInspectionDetails", "UI fixture only: HTTP 567. No network request was made by this test.");
            tab(activity, "new");
            capture(activity.getWindow().getDecorView(), "08-source-error-fixture");
            assertEquals(View.VISIBLE, field(activity, "failureDetails", Button.class).getVisibility());
        } finally { controller.pause().stop().destroy(); }
    }

    private static void tab(MainActivity activity, String destination) throws Exception {
        invoke(activity, "showTab", new Class<?>[]{String.class}, destination);
        idle();
    }
    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private static Object invoke(Object object, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = object.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(object, arguments);
    }
    private static <T> T field(Object object, String name, Class<T> type) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true);
        return type.cast(field.get(object));
    }
    private static void setField(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private static TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), text); if (found != null) return found;
            }
        }
        return null;
    }
    private static Button findButton(View view, String part) {
        if (view instanceof Button && ((Button) view).getText().toString().contains(part)) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = findButton(group.getChildAt(i), part); if (found != null) return found;
            }
        }
        return null;
    }
    private static void capture(View decor, String name) throws Exception {
        idle();
        float density = decor.getResources().getDisplayMetrics().density;
        int width = Math.round(412 * density);
        int height = Math.round(915 * density);
        ViewGroup.LayoutParams layout = decor.getLayoutParams();
        boolean wrapWidth = layout != null && layout.width == ViewGroup.LayoutParams.WRAP_CONTENT;
        boolean wrapHeight = layout != null && layout.height == ViewGroup.LayoutParams.WRAP_CONTENT;
        // Respect actual dialog wrap-content constraints. Forcing every floating
        // window to a full-screen height would manufacture empty white panels.
        if (layout != null && layout.width > 0) width = Math.min(width, layout.width);
        if (layout != null && layout.height > 0) height = Math.min(height, layout.height);
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, wrapWidth ? View.MeasureSpec.AT_MOST : View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, wrapHeight ? View.MeasureSpec.AT_MOST : View.MeasureSpec.EXACTLY));
        width = decor.getMeasuredWidth(); height = decor.getMeasuredHeight();
        decor.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.TRANSPARENT);
        Canvas canvas = new Canvas(bitmap); decor.draw(canvas);
        if (!OUTPUT.isDirectory() && !OUTPUT.mkdirs()) throw new java.io.IOException("Cannot create UI output directory");
        try (FileOutputStream stream = new FileOutputStream(new File(OUTPUT, name + ".png"))) {
            assertTrue("PNG encoder failed", bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
        StringBuilder tree = new StringBuilder("Robolectric native Android Views; not an installed-phone screenshot.\n");
        tree.append("Window layout constraints: ").append(layout == null ? "none" : layout.width + "x" + layout.height).append('\n');
        if (decor.getBackground() != null) {
            Outline outline = new Outline(); decor.getBackground().getOutline(outline);
            tree.append("Window background: ").append(decor.getBackground().getClass().getSimpleName())
                    .append("; outline radius=").append(outline.getRadius()).append('\n');
        }
        dump(decor, tree, 0);
        try (FileOutputStream stream = new FileOutputStream(new File(OUTPUT, name + ".txt"))) {
            stream.write(tree.toString().getBytes(StandardCharsets.UTF_8));
        }
        bitmap.recycle();
    }
    private static void dump(View view, StringBuilder out, int depth) {
        if (view.getVisibility() != View.VISIBLE) return;
        Rect bounds = new Rect(); view.getGlobalVisibleRect(bounds);
        for (int i = 0; i < depth; i++) out.append("  ");
        out.append(view.getClass().getSimpleName()).append(' ').append(bounds.toShortString());
        if (view instanceof TextView) out.append(" text=").append(((TextView) view).getText().toString().replace('\n', ' '));
        out.append('\n');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) dump(group.getChildAt(i), out, depth + 1);
        }
    }
}
