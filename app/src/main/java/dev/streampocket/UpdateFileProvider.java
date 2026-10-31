package dev.streampocket;

/** Dedicated provider subclass, with only the verified-update directory configured in XML. */
public final class UpdateFileProvider extends androidx.core.content.FileProvider {
    public UpdateFileProvider() { super(); }
}
