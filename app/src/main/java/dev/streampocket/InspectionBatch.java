package dev.streampocket;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One failed share must not discard the rest of a pasted batch. */
final class InspectionBatch {
    interface Inspector {
        List<Engine.Media> inspect(String url, Consumer<String> progress, BooleanSupplier cancelled) throws Exception;
    }
    static final class Result {
        final List<Engine.Media> media = new ArrayList<>();
        final List<String> failed = new ArrayList<>();
        final StringBuilder details = new StringBuilder();
        int succeeded;
        String message;
    }
    static Result run(List<String> urls, Inspector inspector, BooleanSupplier cancelled, Consumer<String> progress) throws InterruptedException {
        Result result = new Result();
        LinkedHashMap<String, Engine.Media> unique = new LinkedHashMap<>();
        for (int n = 0; n < urls.size(); n++) {
            if (cancelled.getAsBoolean()) throw new InterruptedException("解析已取消");
            String url = urls.get(n), prefix = "解析 " + (n + 1) + "/" + urls.size() + " · ";
            try {
                List<Engine.Media> found = inspector.inspect(url, stage -> progress.accept(prefix + stage), cancelled);
                if (found.isEmpty()) throw new java.io.IOException("源站没有返回可用视频。");
                for (Engine.Media media : found) unique.putIfAbsent(key(media), media);
                result.succeeded++;
            } catch (InterruptedException stopped) { throw stopped; }
            catch (Exception failure) {
                result.failed.add(url);
                result.message = CoreRules.inspectionMessage(failure);
                // Bound logs even when a large pasted batch fails repeatedly.
                if (result.details.length() < 24000) result.details.append(n + 1).append(". ").append(CoreRules.cleanLog(url))
                        .append("\n").append(result.message).append("\n").append(CoreRules.cleanLog(failure.toString())).append("\n\n");
            }
        }
        if (cancelled.getAsBoolean()) throw new InterruptedException("解析已取消");
        result.media.addAll(unique.values());
        return result;
    }
    static String key(Engine.Media media) { return LinkBatch.identity(media.url) + ":" + media.id + ":" + media.playlistIndex; }
}
