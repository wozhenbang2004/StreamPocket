package dev.streampocket;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Inspector boundary and stable media identity shared by the persistent inbox and UI. */
final class InspectionBatch {
    interface Inspector {
        List<Engine.Media> inspect(String url, Consumer<String> progress, BooleanSupplier cancelled) throws Exception;
    }
    static String key(Engine.Media media) { return (media.directFile() ? "direct-file:" + media.url : LinkBatch.identity(media.url)) + ":" + media.id + ":" + media.playlistIndex; }
}
