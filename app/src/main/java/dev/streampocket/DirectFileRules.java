package dev.streampocket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SDK-free rules shared by metadata inspection and the native file transfer adapter. */
public final class DirectFileRules {
    private static final Pattern PARAMETER = Pattern.compile("(?:^|;)\\s*([!#$%&'*+.^_`|~0-9A-Za-z-]+)\\s*=\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|([^;]*))");
    private static final Pattern RANGE = Pattern.compile("(?i)^bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)$");
    private DirectFileRules() {}

    /** Do not decode, reorder, or drop signed query parameters. */
    public static String normalizeUrl(String input) {
        try {
            if (input == null || input.length() > 16384 || input.matches("(?s).*[\\p{Cntrl}].*")) throw new Exception();
            URI uri = new URI(input.trim());
            String scheme = uri.getScheme(), host = uri.getHost();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || host == null
                    || uri.getRawUserInfo() != null || uri.getPort() == 0 || uri.getPort() > 65535) throw new Exception();
            host = host.toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            if (!host.contains(".") || host.endsWith(".local") || host.endsWith(".localhost")
                    || host.equals("localhost") || host.contains(":") || host.startsWith("[")) throw new Exception();
            if (host.matches("[0-9.]+")) {
                String[] pieces = host.split("\\.", -1);
                if (pieces.length != 4) throw new Exception();
                byte[] address = new byte[4];
                for (int i = 0; i < 4; i++) {
                    if (pieces[i].isEmpty() || pieces[i].length() > 3 || (pieces[i].length() > 1 && pieces[i].startsWith("0"))) throw new Exception();
                    int part = Integer.parseInt(pieces[i]);
                    if (part < 0 || part > 255) throw new Exception();
                    address[i] = (byte) part;
                }
                if (!publicAddress(InetAddress.getByAddress(address))) throw new Exception();
            }
            String authority = host + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
            return new URI(scheme.toLowerCase(Locale.ROOT) + "://" + authority
                    + (uri.getRawPath() == null ? "" : uri.getRawPath())
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())).toASCIIString();
        } catch (Exception invalid) {
            throw new IllegalArgumentException("请使用公开网站的 HTTP 或 HTTPS 文件链接，不支持本地地址或包含账号密码的链接");
        }
    }

    static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = bytes[0] & 255, b = bytes[1] & 255;
            return a != 0 && a < 224 && !(a == 100 && b >= 64 && b <= 127)
                    && !(a == 198 && (b == 18 || b == 19));
        }
        return bytes.length == 16 && (bytes[0] & 0xfe) != 0xfc;
    }

    public static String header(String value) {
        if (value == null || value.length() > 8192 || value.matches("(?s).*[\\r\\n\\x00].*")) return "";
        return value.trim();
    }

    public static String mime(String contentType) {
        String value = header(contentType).split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return value.length() <= 128 && value.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")
                ? value : "application/octet-stream";
    }

    public static boolean htmlMime(String contentType) {
        String type = mime(contentType);
        return type.equals("text/html") || type.equals("application/xhtml+xml");
    }

    public static long length(String value) {
        try { String clean = header(value); return clean.matches("[0-9]+") ? Long.parseLong(clean) : -1; }
        catch (NumberFormatException invalid) { return -1; }
    }

    public static String filename(String url, String contentDisposition) {
        String extended = "", plain = "";
        Matcher parameters = PARAMETER.matcher(header(contentDisposition));
        while (parameters.find()) {
            String key = parameters.group(1).toLowerCase(Locale.ROOT);
            String value = parameters.group(2) != null ? parameters.group(2).replaceAll("\\\\(.)", "$1") : parameters.group(3).trim();
            if (key.equals("filename*") && extended.isEmpty()) {
                int first = value.indexOf('\''), second = first < 0 ? -1 : value.indexOf('\'', first + 1);
                if (second >= 0) try {
                    String encoding = value.substring(0, first);
                    if (encoding.equalsIgnoreCase("UTF-8") || encoding.equalsIgnoreCase("ISO-8859-1"))
                        extended = percentDecode(value.substring(second + 1), Charset.forName(encoding));
                } catch (IllegalArgumentException ignored) { /* Fall back to filename, then URL. */ }
            } else if (key.equals("filename") && plain.isEmpty()) plain = value;
        }
        if (!extended.isEmpty()) return safeName(extended);
        if (!plain.isEmpty()) return safeName(plain);
        try {
            String path = new URI(url).getRawPath();
            if (path != null && !path.endsWith("/")) return safeName(percentDecode(path.substring(path.lastIndexOf('/') + 1), StandardCharsets.UTF_8));
        } catch (Exception invalid) { /* Use a safe neutral filename. */ }
        return "download";
    }

    private static String percentDecode(String input, Charset encoding) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < input.length();) {
            if (input.charAt(i) == '%') {
                if (i + 2 >= input.length()) throw new IllegalArgumentException("Invalid percent escape");
                int a = Character.digit(input.charAt(i + 1), 16), b = Character.digit(input.charAt(i + 2), 16);
                if (a < 0 || b < 0) throw new IllegalArgumentException("Invalid percent escape");
                bytes.write((a << 4) + b); i += 3;
            } else {
                int point = input.codePointAt(i);
                byte[] encoded = new String(Character.toChars(point)).getBytes(encoding);
                bytes.write(encoded, 0, encoded.length); i += Character.charCount(point);
            }
        }
        return new String(bytes.toByteArray(), encoding);
    }

    public static String safeName(String input) {
        String value = Normalizer.normalize(input == null ? "" : input, Normalizer.Form.NFC)
                .replaceAll("[\\p{Cntrl}\\p{Cf}/\\\\<>:\"|?*]", "_").trim();
        while (value.startsWith(".")) value = value.substring(1);
        while (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        if (value.trim().isEmpty()) return "download";
        int dot = value.lastIndexOf('.');
        String suffix = dot > 0 && value.length() - dot <= 20 ? value.substring(dot) : "";
        String stem = suffix.isEmpty() ? value : value.substring(0, dot);
        while ((stem + suffix).getBytes(StandardCharsets.UTF_8).length > 200 && !stem.isEmpty())
            stem = stem.substring(0, stem.offsetByCodePoints(stem.length(), -1));
        return stem + suffix;
    }

    /** Return a total size only after checking that the partial response covers the requested offset. */
    public static long rangeTotal(int status, String contentRange, long offset, long responseLength) throws IOException {
        if (status != 206) throw new IOException("网站未按请求返回断点范围，已有数据已保留");
        Matcher match = RANGE.matcher(header(contentRange));
        if (!match.matches()) throw new IOException("网站返回了无效的文件范围");
        try {
            long begin = Long.parseLong(match.group(1)), end = Long.parseLong(match.group(2));
            long total = match.group(3).equals("*") ? -1 : Long.parseLong(match.group(3));
            if (begin != offset || end < begin || (total >= 0 && end >= total)
                    || (responseLength >= 0 && responseLength != Math.addExact(Math.subtractExact(end, begin), 1)))
                throw new IOException("网站返回的文件范围与请求不一致");
            return total;
        } catch (IllegalArgumentException | ArithmeticException invalid) { throw new IOException("网站返回的文件范围无效", invalid); }
    }

    public static String strongEtag(String value) {
        String clean = header(value);
        return clean.matches("\"[\\x21\\x23-\\x7e\\x80-\\xff]*\"") ? clean : "";
    }

    public static String modified(String value) {
        String clean = header(value);
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        format.setLenient(false); format.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
        java.text.ParsePosition position = new java.text.ParsePosition(0);
        return format.parse(clean, position) != null && position.getIndex() == clean.length() ? clean : "";
    }

    public static boolean sameIdentity(String oldEtag, String oldModified, long oldSize,
                                       String newEtag, String newModified, long newSize) {
        String oldStrong = strongEtag(oldEtag), newStrong = strongEtag(newEtag);
        if (!oldStrong.isEmpty()) return oldStrong.equals(newStrong)
                && (oldSize < 0 || newSize < 0 || oldSize == newSize);
        String modified = modified(oldModified);
        return !modified.isEmpty() && modified.equals(modified(newModified))
                && oldSize >= 0 && oldSize == newSize;
    }

    public static boolean looksHtml(byte[] prefix, int length) {
        String value = new String(prefix, 0, Math.max(0, Math.min(length, prefix.length)), StandardCharsets.UTF_8)
                .replace("\ufeff", "").trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < 8 && value.startsWith("<!--"); i++) {
            int end = value.indexOf("-->");
            if (end < 0) return false;
            value = value.substring(end + 3).trim();
        }
        if (value.startsWith("<?xml")) {
            int end = value.indexOf("?>");
            if (end >= 0) value = value.substring(end + 2).trim();
        }
        return value.startsWith("<!doctype html") || value.startsWith("<html") || value.startsWith("<head")
                || value.startsWith("<body") || value.startsWith("<script");
    }
}
