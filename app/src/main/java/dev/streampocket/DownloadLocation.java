package dev.streampocket;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.IOException;

/** A persisted SAF destination. Existing grants remain available to already saved files. */
public final class DownloadLocation {
    private static final String PREFS = "download-location";
    public static final String DEFAULT_LABEL = "Download / StreamPocket";
    private DownloadLocation() {}

    public static final class Target {
        public final Uri tree;
        public final String label;
        Target(Uri tree, String label) { this.tree = tree; this.label = label; }
        String key() { return tree == null ? "default" : tree.toString(); }
    }

    public static Uri tree(Context context) {
        String saved = preferences(context).getString("tree", "");
        return saved.isEmpty() ? null : Uri.parse(saved);
    }

    public static String label(Context context) {
        if (tree(context) == null) return DEFAULT_LABEL;
        return preferences(context).getString("label", "自选文件夹");
    }

    public static void remember(Context context, Uri tree, int resultFlags) throws IOException {
        if (tree == null || !"content".equals(tree.getScheme()) || !DocumentsContract.isTreeUri(tree))
            throw new IOException("请选择可写入的文件夹");
        int required = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        if ((resultFlags & required) != required) throw new IOException("文件夹需要读取和写入授权，请重新选择");
        try {
            context.getContentResolver().takePersistableUriPermission(tree, required);
            ensureGrant(context, tree);
            String name = directoryName(context, tree);
            if (!preferences(context).edit().putString("tree", tree.toString()).putString("label", name).commit())
                throw new IOException("未能保存文件夹设置，请重试");
        } catch (SecurityException error) { throw new IOException("文件夹授权未保留，请重新选择", error); }
    }

    public static void reset(Context context) throws IOException {
        // Old document URIs can still be used by video records and editing drafts.
        if (!preferences(context).edit().remove("tree").remove("label").commit())
            throw new IOException("未能恢复默认保存位置，请重试");
    }

    public static Target snapshot(Context context) throws IOException {
        Uri selected = tree(context);
        if (selected == null) return new Target(null, DEFAULT_LABEL);
        validate(context, selected);
        return new Target(selected, label(context));
    }

    static Target forTask(Context context, org.json.JSONObject task) throws Exception {
        if (task.has("destinationTree")) {
            String value=task.optString("destinationTree");
            Target pinned=new Target(value.isEmpty()?null:Uri.parse(value),task.optString("savedLocation",DEFAULT_LABEL));
            validate(context,pinned.tree); return pinned;
        }
        Target target=snapshot(context); TaskStore.pinDestination(context,task.getString("id"),target); return target;
    }

    static void validate(Context context, Uri tree) throws IOException {
        if (tree == null) return;
        try { ensureGrant(context, tree); directoryName(context, tree); }
        catch (SecurityException error) { throw new IOException("保存文件夹授权已失效，请重新选择；文件没有改存到其他位置", error); }
    }

    static Uri document(Target target) { return DocumentsContract.buildDocumentUriUsingTree(target.tree, DocumentsContract.getTreeDocumentId(target.tree)); }

    private static void ensureGrant(Context context, Uri tree) throws IOException {
        for (UriPermission grant : context.getContentResolver().getPersistedUriPermissions())
            if (tree.equals(grant.getUri()) && grant.isReadPermission() && grant.isWritePermission()) return;
        throw new IOException("保存文件夹授权已失效，请重新选择；文件没有改存到其他位置");
    }

    private static String directoryName(Context context, Uri tree) throws IOException {
        Target target = new Target(tree, "");
        try (Cursor cursor = context.getContentResolver().query(document(target),
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst() || !DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(1)))
                throw new IOException("保存文件夹无法访问，请重新选择");
            String name = cursor.getString(0);
            return name == null || name.trim().isEmpty() ? "自选文件夹" : name;
        } catch (RuntimeException error) { throw new IOException("保存文件夹无法访问，请重新选择", error); }
    }

    private static SharedPreferences preferences(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
}
