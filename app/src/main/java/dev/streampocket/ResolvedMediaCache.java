package dev.streampocket;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Process-local, bounded cache. Tasks persist only opaque handles, never CDN credentials. */
final class ResolvedMediaCache {
    private static final long TTL = 5 * 60 * 1000L;
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final LinkedHashMap<String, Entry> ITEMS = new LinkedHashMap<>();
    private static final byte[] KEY = new byte[32];
    static { new SecureRandom().nextBytes(KEY); }
    private static final class Entry {
        String source, scope; long created; byte[] iv, encrypted;
    }
    static synchronized String put(JSONObject info, String source, String scope, long now) throws Exception {
        prune(now);
        byte[] bytes = info.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 1024 * 1024) return "";
        Entry e = new Entry(); e.source = source; e.scope = scope; e.created = now;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"));
        cipher.updateAAD((source + "\n" + scope).getBytes(StandardCharsets.UTF_8));
        e.iv = cipher.getIV(); e.encrypted = cipher.doFinal(bytes);
        String id = UUID.randomUUID().toString(); ITEMS.put(id, e);
        while (ITEMS.size() > 16 || size() > MAX_BYTES) ITEMS.remove(ITEMS.keySet().iterator().next());
        return id;
    }
    static synchronized JSONObject get(String id, String source, String scope, long now) throws Exception {
        prune(now); Entry e = ITEMS.get(id);
        if (e == null || !e.source.equals(source) || !e.scope.equals(scope)) { ITEMS.remove(id); return null; }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"), new GCMParameterSpec(128, e.iv));
        cipher.updateAAD((source + "\n" + scope).getBytes(StandardCharsets.UTF_8));
        return new JSONObject(new String(cipher.doFinal(e.encrypted), StandardCharsets.UTF_8));
    }
    private static void prune(long now) { ITEMS.values().removeIf(e -> now < e.created || now - e.created >= TTL); }
    private static int size() { int n = 0; for (Entry e : ITEMS.values()) n += e.encrypted.length; return n; }
}
