package dev.streampocket;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.HttpCookie;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Private, encrypted platform sessions. Only disposable yt-dlp snapshots are plaintext. */
public final class AccountStore {
    private static final Object LOCK = new Object();
    private static final String HEADER = "# Netscape HTTP Cookie File\n\n";
    private static final byte[] MAGIC = new byte[]{'S', 'P', 'A', 'C', 1};
    private static final String KEY_ALIAS = "streampocket.local.accounts.v1";
    private static final int LIMIT = 2 * 1024 * 1024;
    private static final String YOUTUBE_MIGRATED = "youtube.otherMigrated";
    private static final String CHANNELS_MIGRATED = "channels038.migrated";
    private static final String COMMON_SOURCES_MIGRATED = "channels.commonVideoSources.v1";
    private final Context app;
    private final File directory;
    private final SharedPreferences metadata;
    private final SecretKey suppliedKey;

    public AccountStore(Context context) { this(context, null); }

    // Tests exercise real AES/GCM with an ephemeral key; production uses AndroidKeyStore only.
    AccountStore(Context context, SecretKey key) {
        app = context.getApplicationContext();
        directory = new File(app.getNoBackupFilesDir(), "account-sessions");
        metadata = app.getSharedPreferences("account-sessions-metadata", Context.MODE_PRIVATE);
        suppliedKey = key;
    }

    public void prepare() throws IOException { synchronized (LOCK) { migrateLegacy(); } }

    public boolean hasSaved(LoginPlatform platform) {
        synchronized (LOCK) { return exists(platform.id); }
    }

    public boolean hasAnySaved() {
        synchronized (LOCK) {
            for (LoginPlatform platform : LoginPlatform.values()) if (hasSaved(platform)) return true;
            return exists("other") || new File(app.getFilesDir(), "cookies.txt").isFile();
        }
    }

    public boolean isEnabled(LoginPlatform platform) {
        synchronized (LOCK) { return hasSaved(platform) && metadata.getBoolean(platform.id + ".enabled", true); }
    }

    public void setEnabled(LoginPlatform platform, boolean enabled) throws IOException {
        synchronized (LOCK) {
            migrateLegacy();
            if (!metadata.edit().putBoolean(platform.id + ".enabled", enabled)
                    .putString(platform.id + ".revision", java.util.UUID.randomUUID().toString()).remove(platform.id + ".requestState").commit())
                throw new IOException("未能保存账号启用设置，请重试。");
        }
    }

    public long savedAt(LoginPlatform platform) {
        synchronized (LOCK) { return metadata.getLong(platform.id + ".savedAt", 0); }
    }

    public String source(LoginPlatform platform) {
        synchronized (LOCK) { return metadata.getString(platform.id + ".source", "文件导入"); }
    }

    String cacheScope(String url) throws IOException {
        synchronized (LOCK) {
            migrateLegacy();
            VideoPlatform channel = VideoPlatform.detect(url);
            LoginPlatform platform = channel == null ? null : LoginPlatform.fromId(channel.id);
            String id = platform == null ? "other" : platform.id;
            return metadata.getString("cacheEpoch", "initial") + ":" + id + ":"
                    + metadata.getString("other.revision", "initial") + ":"
                    + metadata.getString(id + ".revision", "legacy-" + metadata.getLong(id + ".savedAt", 0))
                    + ":" + (platform == null || isEnabled(platform));
        }
    }

    public String requestStatus(LoginPlatform platform) {
        synchronized (LOCK) { return metadata.getString(platform.id + ".requestState", "未验证"); }
    }

    void noteRequest(String url, String scope, Throwable failure) throws IOException {
        synchronized (LOCK) {
            VideoPlatform channel = VideoPlatform.detect(url);
            LoginPlatform platform = channel == null ? null : LoginPlatform.fromId(channel.id);
            if (platform == null || !isEnabled(platform) || !scope.equals(cacheScope(url))) return;
            // A public request with saved cookies does not prove account permissions.
            if (failure == null) metadata.edit().putString(platform.id + ".requestState", "最近请求成功").apply();
            else if (SourceFailure.kind(failure) == SourceFailure.Kind.AUTH)
                metadata.edit().putString(platform.id + ".requestState", "需要重新登录").apply();
        }
    }

    public String load(LoginPlatform platform) throws IOException {
        synchronized (LOCK) {
            migrateLegacy();
            return readEncrypted(platform.id);
        }
    }

    public void save(LoginPlatform platform, String netscape, String source) throws IOException {
        synchronized (LOCK) {
            migrateLegacy();
            Map<String, StringBuilder> parts = split(netscape);
            StringBuilder selected = parts.get(platform.id);
            if (selected == null || selected.length() == 0) throw new IOException("没有取得此平台的有效 Cookie，原登录状态已保留。");
            // A platform save cannot replace or store another platform's credentials.
            if (parts.size() != 1) throw new IOException("登录数据包含其他网站，原登录状态已保留。");
            writeEncrypted(platform.id, HEADER + selected);
            recordSaved(platform, "网页登录".equals(source) ? "网页登录" : "文件导入");
        }
    }

    public void clear(LoginPlatform platform) throws IOException {
        synchronized (LOCK) {
            migrateLegacy();
            AtomicFile atomic = new AtomicFile(file(platform.id));
            atomic.delete();
            if (exists(platform.id)) throw new IOException("无法清除此平台的登录信息，请稍后重试。");
            if (!metadata.edit().remove(platform.id + ".enabled").remove(platform.id + ".savedAt").remove(platform.id + ".source")
                    .remove(platform.id + ".requestState").putString(platform.id + ".revision", java.util.UUID.randomUUID().toString()).commit())
                throw new IOException("登录信息已清除，但状态记录未完成，请重新打开账号管理。");
        }
    }

    /** Recovery must work even when the secure key is unavailable or a legacy file is damaged. */
    public void clearAll() throws IOException {
        synchronized (LOCK) {
            for (LoginPlatform platform : LoginPlatform.values()) new AtomicFile(file(platform.id)).delete();
            new AtomicFile(file("other")).delete();
            File legacy = new File(app.getFilesDir(), "cookies.txt");
            if (legacy.exists() && !legacy.delete()) throw new IOException("部分本机登录信息未能清除，请重试。");
            File legacyStaged = new File(app.getFilesDir(), "cookies.txt.new");
            if (legacyStaged.exists() && !legacyStaged.delete()) throw new IOException("部分旧登录临时文件未能清除，请重试。");
            if (hasAnySaved()) throw new IOException("部分本机登录信息未能清除，请重试。");
            if (!metadata.edit().clear().putString("cacheEpoch", java.util.UUID.randomUUID().toString())
                    .putBoolean("legacy.migrated", true).putBoolean(YOUTUBE_MIGRATED, true)
                    .putBoolean(CHANNELS_MIGRATED, true).putBoolean(COMMON_SOURCES_MIGRATED, true).commit())
                throw new IOException("登录信息已清除，但状态记录未完成，请重新打开账号管理。");
        }
    }

    /** Import replaces only platforms actually represented in the selected file. */
    public void importLegacy(String netscape) throws IOException {
        synchronized (LOCK) {
            Map<String, StringBuilder> parts = split(netscape); // Validate before changing any saved session.
            if (parts.isEmpty()) throw new IOException("文件里没有有效 Cookie。");
            migrateLegacy();
            for (Map.Entry<String, StringBuilder> part : parts.entrySet()) {
                writeEncrypted(part.getKey(), HEADER + part.getValue());
                if (part.getKey().equals("other")) metadata.edit().putString("other.revision", java.util.UUID.randomUUID().toString()).commit();
                LoginPlatform platform = LoginPlatform.fromId(part.getKey());
                if (platform != null) recordSaved(platform, "文件导入");
            }
        }
    }

    /** A platform import never saves cookies from the other websites in an export. */
    public void importForPlatform(LoginPlatform platform, String netscape) throws IOException {
        synchronized (LOCK) {
            if (platform == null) throw new IOException("请选择要导入的平台。");
            Map<String, StringBuilder> parts = split(netscape);
            StringBuilder selected = parts.get(platform.id);
            if (selected == null || selected.length() == 0)
                throw new IOException("文件里没有此平台的 Cookie，原登录信息已保留。");
            migrateLegacy();
            writeEncrypted(platform.id, HEADER + selected);
            recordSaved(platform, "文件导入");
        }
    }

    /** Never gives a request permission to modify the encrypted masters. */
    String writeScopedSnapshot(File destination, String url) throws IOException {
        synchronized (LOCK) { writeSnapshot(destination); return cacheScope(url); }
    }

    public void writeSnapshot(File destination) throws IOException {
        synchronized (LOCK) {
            migrateLegacy();
            StringBuilder combined = new StringBuilder(HEADER);
            for (LoginPlatform platform : LoginPlatform.values()) {
                if (isEnabled(platform)) appendRows(combined, readEncrypted(platform.id));
            }
            // Old backups must not bypass a platform's pause or clear action.
            StringBuilder other = split(readEncrypted("other", true)).get("other");
            if (other != null) appendRows(combined, HEADER + other);
            File parent = destination.getParentFile();
            if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) throw new IOException("无法创建登录凭据临时目录。");
            try (FileOutputStream output = new FileOutputStream(destination)) {
                destination.setReadable(false, false); destination.setWritable(false, false);
                destination.setReadable(true, true); destination.setWritable(true, true);
                output.write(combined.toString().getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            } catch (IOException error) { destination.delete(); throw new IOException("无法准备本机登录会话。", error); }
        }
    }

    public boolean hasTwitterLogin() throws IOException {
        synchronized (LOCK) {
            migrateLegacy();
            return isEnabled(LoginPlatform.X) && LoginPlatform.X.hasLogin(readEncrypted(LoginPlatform.X.id));
        }
    }

    /** Only the selected platform's own saved cookies may enter its temporary login browser. */
    static List<WebCookie> webRestoreCookies(LoginPlatform platform, String netscape) throws IOException {
        Map<String, StringBuilder> parts = split(netscape);
        if (parts.size() > 1 || (!parts.isEmpty() && !parts.containsKey(platform.id)))
            throw new IOException("登录数据不属于当前平台，原保存信息已保留。");
        long now = System.currentTimeMillis() / 1000;
        List<WebCookie> result = new ArrayList<>();
        SimpleDateFormat expiresFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        expiresFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
        for (String line : netscape.split("\\r?\\n")) {
            if (line.trim().isEmpty() || (line.startsWith("#") && !line.startsWith("#HttpOnly_"))) continue;
            String[] row = line.split("\\t", -1);
            boolean httpOnly = row[0].startsWith("#HttpOnly_");
            String domain = httpOnly ? row[0].substring(10) : row[0];
            String host = domain.startsWith(".") ? domain.substring(1) : domain;
            if (!platform.ownsDomain(host)) throw new IOException("登录数据不属于当前平台，原保存信息已保留。");
            // Native Set-Cookie accepts attributes, so never turn an imported
            // semicolon in a value/path into a second attribute while restoring.
            if (row[6].indexOf(';') >= 0 || row[2].indexOf(';') >= 0)
                throw new IOException("此登录信息无法带入网页登录，原保存信息已保留。");
            long expires = Long.parseLong(row[4]);
            if (expires != 0 && expires <= now) continue;
            StringBuilder value = new StringBuilder(row[5]).append('=').append(row[6]);
            if ("TRUE".equals(row[1])) value.append("; Domain=.").append(host);
            value.append("; Path=").append(row[2]);
            if ("TRUE".equals(row[3])) value.append("; Secure");
            if (httpOnly) value.append("; HttpOnly");
            if (expires > 0) value.append("; Expires=").append(expiresFormat.format(new Date(Math.min(expires, 253402300799L) * 1000)));
            result.add(new WebCookie("https://" + host + "/", value.toString()));
        }
        return result;
    }

    static final class WebCookie {
        final String url, value;
        WebCookie(String url, String value) { this.url = url; this.value = value; }
    }

    /** CookieManager returns effective Cookie headers, so keep each sampled host scoped exactly. */
    public static String fromWebCookies(LoginPlatform platform, Map<String, String> urlToHeaders) {
        LinkedHashMap<String, String> rows = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : urlToHeaders.entrySet()) {
            String url = entry.getKey(), header = entry.getValue();
            if (!Arrays.asList(platform.cookieUrls()).contains(url)) throw new IllegalArgumentException("Cookie 来源不属于当前平台。");
            if (header == null || header.trim().isEmpty()) continue;
            if (hasControl(header)) throw new IllegalArgumentException("网页登录数据格式无效，原登录状态已保留。");
            String host;
            try { host = new URI(url).getHost(); }
            catch (Exception invalid) { throw new IllegalArgumentException("网页登录来源无效。"); }
            if (!platform.ownsDomain(host)) throw new IllegalArgumentException("Cookie 来源不属于当前平台。");
            for (String segment : header.split(";")) {
                String pair = segment.trim();
                int equals = pair.indexOf('=');
                if (equals < 1) throw new IllegalArgumentException("网页登录数据格式无效，原登录状态已保留。");
                String name = pair.substring(0, equals), value = pair.substring(equals + 1);
                if (!validName(name)) throw new IllegalArgumentException("网页登录数据格式无效，原登录状态已保留。");
                String key = host + "\t" + name;
                // Duplicate-name request cookies can differ by path. Preserve the browser's first value.
                if (!rows.containsKey(key)) rows.put(key, host + "\tFALSE\t/\tTRUE\t0\t" + name + "\t" + value + "\n");
            }
        }
        StringBuilder out = new StringBuilder(HEADER);
        for (String row : rows.values()) out.append(row);
        return out.toString();
    }

    /** Newer WebViews expose full Set-Cookie metadata through CookieManagerCompat. */
    public static String fromWebCookieInfo(LoginPlatform platform, Map<String, List<String>> urlToSetCookies) {
        LinkedHashMap<String, String> rows = new LinkedHashMap<>();
        long now = System.currentTimeMillis() / 1000;
        for (Map.Entry<String, List<String>> entry : urlToSetCookies.entrySet()) {
            String url = entry.getKey();
            if (!Arrays.asList(platform.cookieUrls()).contains(url)) throw new IllegalArgumentException("Cookie 来源不属于当前平台。");
            String host;
            try { host = new URI(url).getHost(); }
            catch (Exception invalid) { throw new IllegalArgumentException("网页登录来源无效。"); }
            if (!platform.ownsDomain(host)) throw new IllegalArgumentException("Cookie 来源不属于当前平台。");
            if (entry.getValue() == null) continue;
            for (String info : entry.getValue()) {
                if (info == null || hasControl(info)) throw new IllegalArgumentException("网页登录数据格式无效，原登录状态已保留。");
                List<HttpCookie> parsed;
                try { parsed = HttpCookie.parse(info); }
                catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("网页登录数据格式无效，原登录状态已保留。"); }
                if (parsed.size() != 1) throw new IllegalArgumentException("网页登录数据格式无效，原登录状态已保留。");
                HttpCookie cookie = parsed.get(0);
                String domain = cookie.getDomain();
                // Chromium emits domain= for host-only cookies too. Its canonical
                // leading dot, not mere attribute presence, marks domain cookies.
                boolean includeSubdomains = domain != null && domain.startsWith(".");
                if (includeSubdomains) domain = domain.substring(1);
                if (domain == null || domain.isEmpty()) domain = host;
                boolean scopeMatches = host.equalsIgnoreCase(domain) || (includeSubdomains
                        && host.toLowerCase(java.util.Locale.ROOT).endsWith("." + domain.toLowerCase(java.util.Locale.ROOT)));
                if (!platform.ownsDomain(domain) || !scopeMatches)
                    throw new IllegalArgumentException("Cookie 域名与官方来源不匹配，原登录状态已保留。");
                String path = cookie.getPath() == null || cookie.getPath().isEmpty() ? "/" : cookie.getPath();
                if (!path.startsWith("/") || hasControl(path) || !validName(cookie.getName()) || hasControl(cookie.getValue()))
                    throw new IllegalArgumentException("网页登录数据格式无效，原登录状态已保留。");
                long maxAge = cookie.getMaxAge();
                if (maxAge == 0) continue; // Expired / deleted credentials are not a usable login.
                long expires = maxAge < 0 ? 0 : now + Math.min(maxAge, Long.MAX_VALUE - now);
                String outputDomain = (includeSubdomains ? "." : "") + domain.toLowerCase(java.util.Locale.ROOT);
                String key = outputDomain + "\t" + path + "\t" + cookie.getName();
                if (!rows.containsKey(key)) rows.put(key, (cookie.isHttpOnly() ? "#HttpOnly_" : "") + outputDomain
                        + "\t" + (includeSubdomains ? "TRUE" : "FALSE") + "\t" + path + "\t" + (cookie.getSecure() ? "TRUE" : "FALSE")
                        + "\t" + expires + "\t" + cookie.getName() + "\t" + cookie.getValue() + "\n");
            }
        }
        StringBuilder out = new StringBuilder(HEADER);
        for (String row : rows.values()) out.append(row);
        return out.toString();
    }

    private void migrateLegacy() throws IOException {
        if (metadata.getBoolean("legacy.migrated", false)) { migrateChannelOther(); migrateYouTubeOther(); return; }
        File legacy = new File(app.getFilesDir(), "cookies.txt");
        if (legacy.isFile()) {
            String original = new String(readLimited(legacy), StandardCharsets.UTF_8);
            Map<String, StringBuilder> parts = split(original);
            for (Map.Entry<String, StringBuilder> part : parts.entrySet()) {
                // An interrupted migration may be retried after a login was updated, paused or cleared.
                if (canMigrate(part.getKey())) {
                    writeEncrypted(part.getKey(), HEADER + part.getValue());
                    LoginPlatform platform = LoginPlatform.fromId(part.getKey());
                    if (platform != null) recordSaved(platform, "文件导入");
                }
            }
            if (!legacy.delete()) throw new IOException("旧登录信息迁移未完成，请稍后重试。");
        }
        File legacyStaged = new File(app.getFilesDir(), "cookies.txt.new");
        if (legacyStaged.exists() && !legacyStaged.delete()) throw new IOException("旧登录临时文件迁移未完成，请稍后重试。");
        if (!metadata.edit().putBoolean("legacy.migrated", true).commit()) throw new IOException("无法记录本机登录信息迁移状态。");
        migrateChannelOther();
        migrateYouTubeOther();
    }

    /** Reclassify older generic imports before a platform's pause/clear can take effect. */
    private void migrateChannelOther() throws IOException {
        if (metadata.getBoolean(CHANNELS_MIGRATED, false) && metadata.getBoolean(COMMON_SOURCES_MIGRATED, false)) return;
        Map<String, StringBuilder> parts = split(readEncrypted("other", true));
        for (LoginPlatform platform : LoginPlatform.values()) {
            StringBuilder rows = parts.get(platform.id);
            if (rows != null && canMigrate(platform.id)) {
                writeEncrypted(platform.id, HEADER + rows);
                recordSaved(platform, "文件导入");
            }
        }
        if (exists("other")) {
            StringBuilder remaining = parts.get("other");
            if (remaining != null) writeEncrypted("other", HEADER + remaining);
            else {
                new AtomicFile(file("other")).delete();
                if (exists("other")) throw new IOException("旧登录信息迁移未完成，请稍后重试。");
            }
        }
        if (!metadata.edit().putBoolean(CHANNELS_MIGRATED, true).putBoolean(COMMON_SOURCES_MIGRATED, true).commit())
            throw new IOException("无法记录本机登录信息迁移状态。");
    }

    /** Older releases kept YouTube in the encrypted generic jar, without a platform switch. */
    private void migrateYouTubeOther() throws IOException {
        if (metadata.getBoolean(YOUTUBE_MIGRATED, false)) return;
        Map<String, StringBuilder> parts = split(readEncrypted("other", true));
        StringBuilder youtube = parts.get(LoginPlatform.YOUTUBE.id);
        if (youtube != null) {
            // Write the new owner first. An interrupted retry must keep an explicit newer login,
            // including its existing disabled state and save metadata.
            if (canMigrate(LoginPlatform.YOUTUBE.id)) {
                writeEncrypted(LoginPlatform.YOUTUBE.id, HEADER + youtube);
                recordSaved(LoginPlatform.YOUTUBE, "文件导入");
            }
            StringBuilder other = parts.get("other");
            if (other == null) {
                new AtomicFile(file("other")).delete();
                if (exists("other")) throw new IOException("旧登录信息迁移未完成，请稍后重试。");
            } else writeEncrypted("other", HEADER + other);
        }
        if (!metadata.edit().putBoolean(YOUTUBE_MIGRATED, true).commit())
            throw new IOException("无法记录本机登录信息迁移状态。");
    }

    private void recordSaved(LoginPlatform platform, String source) throws IOException {
        if (!metadata.edit().putBoolean(platform.id + ".enabled", true)
                .putString(platform.id + ".revision", java.util.UUID.randomUUID().toString()).remove(platform.id + ".requestState")
                .putLong(platform.id + ".savedAt", System.currentTimeMillis()).putString(platform.id + ".source", source).commit())
            throw new IOException("登录信息已保存在本机，但状态记录未完成，请重新打开账号管理。");
    }

    /** A revision survives clear; an absent file must never revive an explicitly changed session. */
    private boolean canMigrate(String id) {
        return !exists(id) && !metadata.contains(id + ".revision")
                && !metadata.contains(id + ".enabled") && !metadata.contains(id + ".savedAt");
    }

    private File file(String id) { return new File(directory, id + ".session"); }
    private boolean exists(String id) { return file(id).isFile() || new File(file(id).getPath() + ".bak").isFile(); }

    private SecretKey key() throws Exception {
        if (suppliedKey != null) return suppliedKey;
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return (SecretKey) store.getKey(KEY_ALIAS, null);
        // Never generate over existing encrypted sessions if AndroidKeyStore lost the key.
        File[] saved = directory.listFiles((parent, name) -> name.endsWith(".session") || name.endsWith(".session.bak"));
        if (saved != null && saved.length > 0) throw new IOException("本机安全密钥不可用，请清除对应登录状态后重新登录。");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    private void writeEncrypted(String id, String value) throws IOException {
        FileOutputStream output = null;
        AtomicFile atomic = new AtomicFile(file(id));
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建本机账号目录。");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), iv = cipher.getIV();
            output = atomic.startWrite();
            file(id).setReadable(false, false); file(id).setWritable(false, false);
            file(id).setReadable(true, true); file(id).setWritable(true, true);
            output.write(MAGIC); output.write(iv.length); output.write(iv); output.write(encrypted);
            atomic.finishWrite(output);
        } catch (Exception error) {
            if (output != null) atomic.failWrite(output);
            throw new IOException("无法安全保存本机登录信息，原会话已保留。", error);
        }
    }

    private String readEncrypted(String id) throws IOException {
        return readEncrypted(id, false);
    }

    private String readEncrypted(String id, boolean allowLegacyRepartition) throws IOException {
        File saved = file(id);
        if (!exists(id)) return HEADER;
        try {
            byte[] bytes;
            try (FileInputStream input = new AtomicFile(saved).openRead()) { bytes = readLimited(input); }
            if (bytes.length < MAGIC.length + 1 + 12 + 16 || !Arrays.equals(MAGIC, Arrays.copyOf(bytes, MAGIC.length))) throw new IOException();
            int ivLength = bytes[MAGIC.length] & 255;
            if (ivLength != 12) throw new IOException();
            int start = MAGIC.length + 1;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, bytes, start, ivLength));
            cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8));
            String value = new String(cipher.doFinal(bytes, start + ivLength, bytes.length - start - ivLength), StandardCharsets.UTF_8);
            Map<String, StringBuilder> parts = split(value);
            if (allowLegacyRepartition && id.equals("other")) {
                // Authenticate with the original `other` AAD before reclassifying sites
                // that old releases stored there. Already-owned platform jars stay strict.
                for (String owner : parts.keySet()) if (!owner.equals("other")
                        && !owner.equals(LoginPlatform.YOUTUBE.id) && !owner.equals(LoginPlatform.XIAOHONGSHU.id)
                        && !owner.equals(LoginPlatform.KUAISHOU.id) && !owner.equals(LoginPlatform.TIKTOK.id)
                        && !owner.equals(LoginPlatform.INSTAGRAM.id) && !owner.equals(LoginPlatform.FACEBOOK.id)
                        && !owner.equals(LoginPlatform.PINTEREST.id) && !owner.equals(LoginPlatform.PORNHUB.id)
                        && !owner.equals(LoginPlatform.HEYBOX.id) && !owner.equals(LoginPlatform.WEIBO.id)
                        && !owner.equals(LoginPlatform.XIGUA.id) && !owner.equals(LoginPlatform.ACFUN.id)
                        && !owner.equals(LoginPlatform.REDDIT.id) && !owner.equals(LoginPlatform.VIMEO.id)
                        && !owner.equals(LoginPlatform.TWITCH.id)) throw new IOException();
            } else if (parts.size() > 1 || (!parts.isEmpty() && !parts.containsKey(id))) throw new IOException();
            return value;
        } catch (Exception error) { throw new IOException("本机保存的登录信息暂时无法读取，原文件已保留；请在账号管理中检查或重新登录此平台。", error); }
    }

    static Map<String, StringBuilder> split(String netscape) throws IOException {
        if (netscape == null || netscape.getBytes(StandardCharsets.UTF_8).length > LIMIT
                || (!netscape.contains("Netscape HTTP Cookie File") && !netscape.contains("HTTP Cookie File")))
            throw new IOException("请选择 Netscape 格式 Cookie 文件，最多 2 MB。");
        LinkedHashMap<String, StringBuilder> parts = new LinkedHashMap<>();
        for (String line : netscape.split("\\r?\\n")) {
            if (line.trim().isEmpty() || (line.startsWith("#") && !line.startsWith("#HttpOnly_"))) continue;
            String[] row = line.split("\t", -1);
            if (row.length != 7 || !validDomain(row[0]) || (!row[1].equals("TRUE") && !row[1].equals("FALSE"))
                    || !row[2].startsWith("/") || hasControl(row[2]) || (!row[3].equals("TRUE") && !row[3].equals("FALSE"))
                    || !row[4].matches("[0-9]{1,19}") || !validName(row[5]) || hasControl(row[6]))
                throw new IOException("Cookie 文件格式无效，原登录状态已保留。");
            try { Long.parseLong(row[4]); } catch (NumberFormatException invalid) { throw new IOException("Cookie 文件格式无效，原登录状态已保留。"); }
            String id = "other";
            for (LoginPlatform platform : LoginPlatform.values()) if (platform.ownsDomain(row[0])) { id = platform.id; break; }
            parts.computeIfAbsent(id, ignored -> new StringBuilder()).append(line).append('\n');
        }
        return parts;
    }

    private static boolean validDomain(String domain) {
        if (domain.startsWith("#HttpOnly_")) domain = domain.substring(10);
        if (domain.startsWith(".")) domain = domain.substring(1);
        return domain.length() <= 253 && domain.contains(".")
                && domain.matches("(?i)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*");
    }

    private static boolean validName(String name) { return name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+"); }
    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 32 || value.charAt(i) == 127) return true;
        return false;
    }
    private static void appendRows(StringBuilder into, String netscape) {
        for (String line : netscape.split("\\r?\\n")) if (!line.trim().isEmpty() && (!line.startsWith("#") || line.startsWith("#HttpOnly_"))) into.append(line).append('\n');
    }
    private static byte[] readLimited(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) { return readLimited(input); }
    }
    private static byte[] readLimited(FileInputStream input) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (result.size() + count > LIMIT + 128) throw new IOException("登录信息过大，请重新导入。");
            result.write(buffer, 0, count);
        }
        return result.toByteArray();
    }
}
