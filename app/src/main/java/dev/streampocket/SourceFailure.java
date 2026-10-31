package dev.streampocket;

import java.util.Locale;

/** Classifies source errors, never explanatory account notes added by the UI. */
public final class SourceFailure {
    private SourceFailure() {}
    public enum Kind { TIMEOUT, NETWORK, RATE_LIMIT, AUTH, CHALLENGE, OTHER }

    public static Kind kind(Throwable failure) { return kind(failure == null ? "" : failure.toString()); }
    public static Kind kind(String message) {
        String text = message == null ? "" : message;
        String lastError = "";
        for (String line : text.split("\\r?\\n")) if (line.trim().startsWith("ERROR:")) lastError = line;
        if (!lastError.isEmpty()) text = lastError;
        text = text.toLowerCase(Locale.ROOT);
        if (java.util.regex.Pattern.compile("(?:http(?: error| status)?|status(?: code)?|response(?: code)?)[ :\\t]*(?:=\\s*)?429\\b").matcher(text).find() || text.contains("too many requests") || text.contains("rate limit")) return Kind.RATE_LIMIT;
        if (text.contains("timed out") || text.contains("timeout")) return Kind.TIMEOUT;
        if (text.contains("connection reset") || text.contains("connection refused") || text.contains("network is unreachable")
                || text.contains("temporary failure in name resolution") || text.contains("unable to resolve host")
                || text.contains("remote end closed connection") || text.contains("http error 502")
                || text.contains("http error 503") || text.contains("http error 504")) return Kind.NETWORK;
        if (text.contains("captcha") || text.contains("verification required") || text.contains("verify you are")
                || text.contains("complete the verification") || text.contains("需要验证")) return Kind.CHALLENGE;
        if (text.contains("login required") || text.contains("log in to") || text.contains("sign in")
                || text.contains("authentication required") || text.contains("http error 401") || text.contains("401 unauthorized")
                || text.contains("cookies have expired") || text.contains("invalid cookies") || text.contains("登录后")
                || text.contains("会员专享")) return Kind.AUTH;
        return Kind.OTHER;
    }
    public static boolean retryable(Throwable failure) {
        Kind kind = kind(failure);
        return kind == Kind.TIMEOUT || kind == Kind.NETWORK;
    }
}
