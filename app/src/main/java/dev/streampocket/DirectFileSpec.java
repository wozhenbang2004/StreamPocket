package dev.streampocket;

import org.json.JSONObject;

/** A file response selected by the user, independent of video extraction and quality. */
public final class DirectFileSpec {
    public final String url, name, mime, etag, lastModified;
    public final long size;
    public final boolean ranges;

    public DirectFileSpec(String url, String name, String mime, long size) {
        this(url, name, mime, size, "", "", false);
    }

    public DirectFileSpec(String url, String name, String mime, long size,
                          String etag, String lastModified, boolean ranges) {
        this.url = DirectFileRules.normalizeUrl(url);
        this.name = DirectFileRules.safeName(name);
        this.mime = DirectFileRules.mime(mime);
        this.size = Math.max(-1, size);
        this.etag = DirectFileRules.header(etag);
        this.lastModified = DirectFileRules.modified(lastModified);
        this.ranges = ranges;
    }

    public DirectFileSpec withName(String value) {
        return new DirectFileSpec(url, value, mime, size, etag, lastModified, ranges);
    }

    public JSONObject toJson() throws Exception {
        return new JSONObject().put("url", url).put("name", name).put("mime", mime).put("size", size)
                .put("etag", etag).put("lastModified", lastModified).put("ranges", ranges);
    }

    public static DirectFileSpec fromJson(JSONObject value) {
        if (value == null) throw new IllegalArgumentException("文件信息已丢失，请重新检查链接");
        return new DirectFileSpec(value.optString("url"), value.optString("name", "download"),
                value.optString("mime"), value.optLong("size", -1), value.optString("etag"),
                value.optString("lastModified"), value.optBoolean("ranges"));
    }
}
