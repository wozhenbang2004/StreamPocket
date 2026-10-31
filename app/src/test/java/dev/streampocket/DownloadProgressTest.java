package dev.streampocket;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class DownloadProgressTest {
    @Test public void videoThenAudioDoesNotRestartOverallProgress() throws Exception {
        DownloadProgress model = twoStreams(900, 100);
        progress(model, "v", "h264", "none", "downloading", 450, 900);
        assertEquals(45, model.snapshot().progress, 0.01);
        assertEquals("video", model.snapshot().phase);
        progress(model, "v", "h264", "none", "finished", 900, 900);
        assertEquals(90, model.snapshot().progress, 0.01);
        assertEquals(1, model.snapshot().completedStreams);
        progress(model, "a", "none", "aac", "downloading", 0, 100);
        DownloadProgress.Snapshot audio = model.snapshot();
        assertEquals("audio", audio.phase);
        assertEquals(0, audio.stageProgress, 0.01);
        assertEquals(90, audio.progress, 0.01);
        assertTrue(audio.detail.contains("下载音频"));
        assertTrue(audio.detail.contains("2/2"));
        progress(model, "a", "none", "aac", "downloading", 50, 100);
        assertEquals(95, model.snapshot().progress, 0.01);
    }

    @Test public void allMediaDownloadedStillNeedsProcessingAndPublicSave() throws Exception {
        DownloadProgress model = twoStreams(900, 100);
        progress(model, "v", "h264", "none", "finished", 900, 900);
        progress(model, "a", "none", "aac", "finished", 100, 100);
        DownloadProgress.Snapshot downloaded = model.snapshot();
        assertTrue(downloaded.transferComplete);
        assertEquals(2, downloaded.completedStreams);
        assertEquals(-1, downloaded.progress, 0);
        assertEquals("processing", downloaded.phase);
        assertFalse("Completed media is not a committed task", downloaded.status().equals("completed"));
        model.accept("SP_POST:{\"postprocessor\":\"Merger\",\"status\":\"started\"}");
        assertEquals("merging", model.snapshot().phase);
        assertEquals(-1, model.snapshot().stageProgress, 0);
        assertEquals("saving", model.saving().phase);
        assertEquals(-1, model.snapshot().progress, 0);
        assertFalse(model.snapshot().toJson().getString("status").equals("completed"));
    }

    @Test public void unknownAudioSizeDoesNotInventAnOverallPercentage() throws Exception {
        DownloadProgress model = twoStreams(900, -1);
        progress(model, "v", "h264", "none", "downloading", 450, 900);
        assertEquals(50, model.snapshot().stageProgress, 0.01);
        assertEquals(-1, model.snapshot().progress, 0);
        assertEquals(-1, model.snapshot().totalBytes);
        progress(model, "v", "h264", "none", "finished", 900, 900);
        progress(model, "a", "none", "aac", "downloading", 20, 100);
        assertEquals(92, model.snapshot().progress, 0.01);
    }

    @Test public void delayedConcurrentFragmentUpdatesCannotMoveProgressBackwards() throws Exception {
        DownloadProgress model = twoStreams(900, 100);
        progress(model, "v", "h264", "none", "downloading", 700, 900);
        double overall = model.snapshot().progress;
        progress(model, "v", "h264", "none", "downloading", 600, 900);
        assertEquals(overall, model.snapshot().progress, 0.01);
        progress(model, "v", "h264", "none", "finished", 900, 900);
        progress(model, "a", "none", "aac", "downloading", 30, 100);
        progress(model, "v", "h264", "none", "downloading", 850, 900);
        progress(model, "v", "h264", "none", "finished", 900, 900);
        assertEquals("audio", model.snapshot().phase);
        assertEquals(93, model.snapshot().progress, 0.01);
    }

    @Test public void retryHasItsOwnVisibleStageAndKeepsCompletedStreams() throws Exception {
        DownloadProgress model = twoStreams(900, 100);
        progress(model, "v", "h264", "none", "finished", 900, 900);
        progress(model, "a", "none", "aac", "downloading", 20, 100);
        model.accept("WARNING: Retrying fragment 3 (1/3)...");
        assertEquals("retrying", model.snapshot().phase);
        assertEquals(1, model.snapshot().completedStreams);
        assertTrue(model.snapshot().detail.contains("重试"));
        assertEquals(92, model.snapshot().progress, 0.01);
        progress(model, "a", "none", "aac", "downloading", 25, 100);
        assertEquals("audio", model.snapshot().phase);
    }

    @Test public void aria2PercentageIsAssignedToTheCurrentStream() throws Exception {
        DownloadProgress model = twoStreams(900 * 1024, 100 * 1024);
        assertTrue(model.accept("[#ab12cd 630KiB/900KiB(70%) CN:4 DL:3.1MiB ETA:1s]"));
        assertEquals("video", model.snapshot().phase);
        assertEquals(63, model.snapshot().progress, 0.01);
        progress(model, "v", "h264", "none", "finished", 900 * 1024, 900 * 1024);
        model.accept("[#ef34ab 20KiB/100KiB(20%) CN:4 DL:1MiB]");
        assertEquals("audio", model.snapshot().phase);
        assertEquals(92, model.snapshot().progress, 0.01);
        assertEquals(20, model.snapshot().stageProgress, 0.01);
    }

    @Test public void aria2SummaryReportsLiveRateEtaAndRoundedBytesWithoutChangingExactTotals() throws Exception {
        DownloadProgress model = twoStreams(20 * 1048576, 2 * 1048576);
        model.accept("[#ab12cd 4.1MiB/20MiB(20%) CN:4 DL:2.3MiB ETA:6s]");
        DownloadProgress.Snapshot state = model.snapshot();
        assertEquals(2.3 * 1048576, state.speedBytesPerSecond, 1);
        assertEquals(6, state.etaSeconds);
        assertEquals(22 * 1048576, state.totalBytes);
        assertEquals((long) (4.1 * 1048576), state.downloadedBytes);
        assertTrue(state.estimated);
        assertTrue(state.detail.contains("2.3 MB/s"));
        assertTrue(state.detail.contains("本阶段约 6 秒"));
        model.accept("[#ab12cd 4.2MiB/19.9MiB(21%) CN:4 DL:512KiB ETA:1m2s]");
        assertEquals("Rounded console totals must not replace exact metadata", 22 * 1048576, model.snapshot().totalBytes);
        assertEquals(512 * 1024, model.snapshot().speedBytesPerSecond, 0);
        assertEquals(62, model.snapshot().etaSeconds);
    }

    @Test public void aria2UnknownSizeStillReportsSpeedAndRejectsUnknownUnits() throws Exception {
        DownloadProgress model = twoStreams(-1, -1);
        assertTrue(model.accept("[#ab12cd 1.5MiB/0B CN:1 DL:128KiB ETA:2m5s]"));
        assertEquals(-1, model.snapshot().progress, 0);
        assertEquals(-1, model.snapshot().stageProgress, 0);
        assertEquals(128 * 1024, model.snapshot().speedBytesPerSecond, 0);
        assertEquals(125, model.snapshot().etaSeconds);
        assertTrue(model.accept("[#ab12cd 2furlongs/unknown(20%) CN:1 DL:4kb ETA:unknown]"));
        assertEquals("Unknown speed is not zero or a guessed conversion", -1, model.snapshot().speedBytesPerSecond, 0);
        assertEquals(-1, model.snapshot().etaSeconds);
        assertFalse(model.accept("下载进度 20%，凭据内容不属于进度事件"));
        assertFalse(model.snapshot().toJson().toString().contains("凭据"));
    }

    @Test public void aria2RateResetsForRetryAndNextAudioTrackAndIgnoresLateOldTrack() throws Exception {
        DownloadProgress model = twoStreams(900 * 1024, 100 * 1024);
        model.accept("[#ab12cd 630KiB/900KiB(70%) CN:4 DL:3.1MiB ETA:1s]");
        model.accept("WARNING: Retrying fragment 3 (1/3)...");
        assertEquals(-1, model.snapshot().speedBytesPerSecond, 0);
        assertEquals(-1, model.snapshot().etaSeconds);
        progress(model, "v", "h264", "none", "finished", 900 * 1024, 900 * 1024);
        model.accept("[#ef34ab 20KiB/100KiB(20%) CN:4]");
        assertEquals("audio", model.snapshot().phase);
        assertEquals(-1, model.snapshot().speedBytesPerSecond, 0);
        double overall = model.snapshot().progress;
        assertFalse("A late video summary cannot masquerade as the audio track", model.accept("[#ab12cd 899KiB/900KiB(99%) CN:1 DL:4MiB ETA:1s]"));
        assertEquals("audio", model.snapshot().phase);
        assertEquals(overall, model.snapshot().progress, 0);
    }

    @Test public void rawByteSummariesAnsiColorAndNativeCorrectionsRemainAccurate() throws Exception {
        DownloadProgress model = twoStreams(20 * 1048576, 2 * 1048576);
        assertTrue(model.accept("\u001b[32m[#ab12cd 4194304/20971520(20%) CN:4 DL:524288 ETA:1h2m3s]\u001b[0m"));
        assertEquals(524288, model.snapshot().speedBytesPerSecond, 0);
        assertEquals(3723, model.snapshot().etaSeconds);
        assertFalse(model.snapshot().estimated);
        model.accept("[#ab12cd 4.1MiB/20MiB(20%) CN:4 DL:0B]");
        assertEquals(-1, model.snapshot().speedBytesPerSecond, 0);
        progress(model, "v", "h264", "none", "finished", 4290000, 4290000);
        assertEquals("The native final counter corrects rounded aria2 bytes", 4290000, model.snapshot().downloadedBytes);
        assertEquals(4290000 + 2 * 1048576, model.snapshot().totalBytes);
        assertEquals(-1, model.snapshot().speedBytesPerSecond, 0);
    }

    @Test public void nativeJsonRateRemainsLiveAndDoesNotCarryIntoTheNextTrack() throws Exception {
        DownloadProgress model = twoStreams(900, 100);
        JSONObject first = event("v", "h264", "none", "downloading", 450, 900);
        first.getJSONObject("progress").put("speed", 131072).put("eta", 9);
        model.accept(DownloadProgress.DOWNLOAD_PREFIX + first);
        assertEquals(131072, model.snapshot().speedBytesPerSecond, 0);
        assertEquals(9, model.snapshot().etaSeconds);
        progress(model, "v", "h264", "none", "finished", 900, 900);
        progress(model, "a", "none", "aac", "downloading", 10, 100);
        assertEquals("audio", model.snapshot().phase);
        assertEquals(-1, model.snapshot().speedBytesPerSecond, 0);
        assertEquals(-1, model.snapshot().etaSeconds);
    }

    @Test public void fragmentOnlyAndEstimatedSizesAreReportedHonestly() throws Exception {
        DownloadProgress model = twoStreams(-1, -1);
        JSONObject event = event("v", "h264", "none", "downloading", 30, -1);
        event.getJSONObject("progress").put("fragment_index", 3).put("fragment_count", 10);
        model.accept(DownloadProgress.DOWNLOAD_PREFIX + event);
        assertEquals(30, model.snapshot().stageProgress, 0.01);
        assertEquals(-1, model.snapshot().progress, 0);
        event.getJSONObject("progress").put("total_bytes_estimate", 100);
        model.accept(DownloadProgress.DOWNLOAD_PREFIX + event);
        assertTrue(model.snapshot().estimated);
        assertTrue(model.snapshot().detail.contains("约"));
        assertTrue(model.snapshot().toJson().getBoolean("progressEstimated"));
    }

    @Test public void setupPostprocessorAndMalformedLinesDoNotPretendToDownload() throws Exception {
        DownloadProgress model = twoStreams(900, 100);
        model.accept("SP_POST:{\"postprocessor\":\"StreamPocketTransfer\",\"status\":\"started\"}");
        assertEquals("preparing", model.snapshot().phase);
        assertFalse(model.accept("SP_PROGRESS:not-json"));
        assertFalse(model.accept("unrelated 45% text"));
        assertEquals(-1, model.snapshot().stageProgress, 0);
        assertEquals(0, model.snapshot().completedStreams);
    }

    @Test public void heldEstimateStaysApproximateUntilTheExactCalculationCatchesUp() throws Exception {
        DownloadProgress model = new DownloadProgress();
        model.accept(DownloadProgress.PLAN_PREFIX + new JSONObject().put("formats", new JSONArray()
                .put(new JSONObject().put("format_id", "v").put("vcodec", "h264").put("acodec", "none").put("filesize_approx", 100))
                .put(new JSONObject().put("format_id", "a").put("vcodec", "none").put("acodec", "aac").put("filesize", 100))));
        JSONObject estimated = event("v", "h264", "none", "downloading", 90, -1);
        estimated.getJSONObject("progress").put("total_bytes_estimate", 100);
        model.accept(DownloadProgress.DOWNLOAD_PREFIX + estimated);
        assertEquals(45, model.snapshot().progress, 0.01);
        assertTrue(model.snapshot().estimated);
        progress(model, "v", "h264", "none", "downloading", 90, 900);
        assertEquals("The visible total must not jump backwards", 45, model.snapshot().progress, 0.01);
        assertTrue("The held high-water mark is still an estimate", model.snapshot().estimated);
        assertTrue(model.snapshot().detail.contains("约"));
        progress(model, "v", "h264", "none", "downloading", 810, 900);
        assertEquals(81, model.snapshot().progress, 0.01);
        assertFalse("Exact measurements caught up", model.snapshot().estimated);
        assertFalse(model.snapshot().detail.contains("约"));
    }

    @Test public void configuredConnectionsAreNotReportedAsObservedConnections() throws Exception {
        DownloadProgress model = new DownloadProgress();
        model.setTransferPolicy("parallel-http", 8);
        assertEquals("auto", model.snapshot().transferMode);
        assertEquals(8, model.snapshot().configuredConnections);
        assertEquals(-1, model.snapshot().activeConnections);
        JSONObject event = event("v", "h264", "none", "downloading", 100, 1000);
        model.accept(DownloadProgress.DOWNLOAD_PREFIX + event);
        assertEquals("Missing protocol must not promise HTTP acceleration", "auto", model.snapshot().transferMode);
        assertEquals(-1, model.snapshot().activeConnections);
        event.getJSONObject("info").put("protocol", "https");
        model.accept(DownloadProgress.DOWNLOAD_PREFIX + event);
        assertEquals("parallel-http", model.snapshot().transferMode);
        assertEquals(-1, model.snapshot().activeConnections);
        model.setTransferPolicy("native", 4);
        assertEquals("native-http", model.snapshot().transferMode);
        assertEquals(4, model.snapshot().configuredConnections);
        assertEquals(-1, model.snapshot().toJson().getInt("activeConnections"));
    }

    @Test public void hlsAndDashKeepTheirNativeFragmentRouteUnderAriaEligibility() throws Exception {
        for (String protocol : new String[]{"m3u8", "m3u8_native", "hls", "dash", "http_dash_segments", "http_dash_segments_generator", "native_dash_segments"}) {
            DownloadProgress model = streamWithProtocol(protocol, "parallel-http", 4);
            assertEquals(protocol, "fragments", model.snapshot().transferMode);
            assertEquals(-1, model.snapshot().activeConnections);
            assertFalse("An unrelated aria summary must not attach connections to native fragments",
                    model.accept("[#unrelated 200B/1000B(20%) CN:4 DL:100B]"));
            assertEquals(-1, model.snapshot().activeConnections);
            assertEquals(100, model.snapshot().downloadedBytes);
        }
        for (String protocol : new String[]{"ism", "f4m", "rtmp", "unknown"}) {
            DownloadProgress model = streamWithProtocol(protocol, "parallel-http", 8);
            assertEquals("Do not infer concurrent fragment support for " + protocol, "auto", model.snapshot().transferMode);
            assertEquals(-1, model.snapshot().activeConnections);
        }
    }

    @Test public void theBeforeDownloadPlanSuppliesProtocolBeforeAnyNativeProgressHook() throws Exception {
        DownloadProgress model = new DownloadProgress(); model.setTransferPolicy("parallel-http", 4);
        JSONObject plan = new JSONObject().put("formats", new JSONArray()
                .put(new JSONObject().put("format_id", "v").put("vcodec", "h264").put("acodec", "none").put("filesize", 900).put("protocol", "https"))
                .put(new JSONObject().put("format_id", "a").put("vcodec", "none").put("acodec", "aac").put("filesize", 100).put("protocol", "m3u8_native")));
        model.accept(DownloadProgress.PLAN_PREFIX + plan);
        assertEquals("No active media has been selected yet", "auto", model.snapshot().transferMode);
        model.accept("[#video 450B/900B(50%) CN:4 DL:100B]");
        assertEquals("parallel-http", model.snapshot().transferMode);
        assertEquals(4, model.snapshot().activeConnections);
        progress(model, "v", "h264", "none", "finished", 900, 900);
        model.accept("WARNING: Unsupported features have been detected; extraction will be delegated to ffmpeg");
        progress(model, "a", "none", "aac", "downloading", 10, 100);
        assertEquals("A fallback for the next item must not attach to the finished video", "auto", model.snapshot().transferMode);
        assertEquals(-1, model.snapshot().activeConnections);
    }

    @Test public void ariaConnectionCountsComeFromEachCurrentSummaryIncludingZeroAndMissing() throws Exception {
        DownloadProgress model = streamWithProtocol("https", "parallel-http", 8);
        model.accept("[#ab12cd 200B/1000B(20%) CN:4 DL:100B]");
        assertEquals(4, model.snapshot().activeConnections);
        assertEquals("parallel-http", model.snapshot().toJson().getString("transferMode"));
        assertEquals(8, model.snapshot().toJson().getInt("configuredConnections"));
        assertEquals(4, model.snapshot().toJson().getInt("activeConnections"));
        model.accept("[#ab12cd 200B/1000B(20%) CN:0 DL:0B]");
        assertEquals("An observed idle zero is different from unknown", 0, model.snapshot().activeConnections);
        model.accept("[#ab12cd 200B/1000B(20%) DL:100B]");
        assertEquals(-1, model.snapshot().activeConnections);
        for (String invalid : new String[]{"-1", "4.5", "2147483648", "unknown"}) {
            model.accept("[#ab12cd 200B/1000B(20%) CN:" + invalid + " DL:100B]");
            assertEquals("Invalid connection count cannot retain an old measurement", -1, model.snapshot().activeConnections);
        }
    }

    @Test public void actualConnectionCountClearsOnRetryTrackTransitionPostprocessAndSave() throws Exception {
        DownloadProgress model = twoStreams(900, 100);
        model.setTransferPolicy("parallel-http", 4);
        JSONObject video = event("v", "h264", "none", "downloading", 100, 900);
        video.getJSONObject("info").put("protocol", "https");
        model.accept(DownloadProgress.DOWNLOAD_PREFIX + video);
        model.accept("[#video 450B/900B(50%) CN:4 DL:100B]");
        assertEquals(4, model.snapshot().activeConnections);
        model.accept("WARNING: Retrying fragment 3 (1/3)...");
        assertEquals(-1, model.snapshot().activeConnections);
        model.accept("[#video 500B/900B(55%) CN:3 DL:100B]");
        assertEquals(3, model.snapshot().activeConnections);
        progress(model, "v", "h264", "none", "finished", 900, 900);
        assertEquals(-1, model.snapshot().activeConnections);
        JSONObject audio = event("a", "none", "aac", "downloading", 10, 100);
        audio.getJSONObject("info").put("protocol", "https");
        model.accept(DownloadProgress.DOWNLOAD_PREFIX + audio);
        assertEquals(-1, model.snapshot().activeConnections);
        assertFalse(model.accept("[#video 899B/900B(99%) CN:4 DL:100B]"));
        assertEquals(-1, model.snapshot().activeConnections);
        model.accept("[#audio 20B/100B(20%) CN:2 DL:100B]");
        assertEquals(2, model.snapshot().activeConnections);
        model.accept("SP_POST:{\"postprocessor\":\"Merger\",\"status\":\"started\"}");
        assertEquals(-1, model.snapshot().activeConnections);
        assertEquals("auto", model.snapshot().transferMode);
        assertEquals(-1, model.saving().activeConnections);
    }

    @Test public void explicitFfmpegDelegationRemovesTheConcurrentFragmentClaim() throws Exception {
        DownloadProgress model = streamWithProtocol("m3u8_native", "parallel-http", 4);
        assertEquals("fragments", model.snapshot().transferMode);
        assertTrue(model.accept("WARNING: Unsupported features have been detected; extraction will be delegated to ffmpeg"));
        assertEquals("auto", model.snapshot().transferMode);
        assertEquals(-1, model.snapshot().activeConnections);
        assertFalse(model.accept("[#unrelated 200B/1000B(20%) CN:4 DL:100B]"));
        assertEquals("auto", model.snapshot().transferMode);
    }

    @Test public void sizeEstimateFlagsDoNotConfuseExactTotalsWithRoundedDownloadedBytes() throws Exception {
        DownloadProgress model = twoStreams(20 * 1048576, 2 * 1048576);
        model.accept("[#ab12cd 4.1MiB/20MiB(20%) CN:4 DL:2MiB]");
        JSONObject state = model.snapshot().toJson();
        assertTrue(state.getBoolean("progressEstimated"));
        assertFalse("Exact metadata total is still exact", state.getBoolean("totalBytesEstimated"));
        assertTrue(state.getBoolean("downloadedBytesEstimated"));
        progress(model, "v", "h264", "none", "finished", 4290000, 4290000);
        assertFalse(model.snapshot().toJson().getBoolean("downloadedBytesEstimated"));
        DownloadProgress estimated = new DownloadProgress();
        JSONObject event = event("v", "h264", "none", "downloading", 10, -1);
        event.getJSONObject("progress").put("total_bytes_estimate", 100);
        estimated.accept(DownloadProgress.DOWNLOAD_PREFIX + event);
        assertTrue(estimated.snapshot().toJson().getBoolean("totalBytesEstimated"));
        assertFalse(estimated.snapshot().toJson().getBoolean("downloadedBytesEstimated"));
        progress(estimated, "v", "h264", "none", "downloading", 20, 110);
        assertFalse(estimated.snapshot().toJson().getBoolean("totalBytesEstimated"));
    }

    @Test public void protocolTelemetryDoesNotLeakUrlsAndLabelsDoNotSuggestThreadCounts() throws Exception {
        DownloadProgress model = twoStreams(900, 100);
        JSONObject event = event("v", "h264", "none", "downloading", 100, 900);
        event.getJSONObject("info").put("protocol", "https://cdn.example/video?token=private");
        model.accept(DownloadProgress.DOWNLOAD_PREFIX + event);
        assertEquals("auto", model.snapshot().transferMode);
        assertFalse(model.snapshot().toJson().toString().contains("private"));
        assertTrue(model.snapshot().detail.contains("媒体 1/2"));
        assertFalse(model.snapshot().detail.contains("轨道"));
        assertTrue(DownloadProgress.PLAN_TEMPLATE.contains("protocol"));
        assertTrue(DownloadProgress.DOWNLOAD_TEMPLATE.contains("protocol"));
    }

    @Test public void explicitCompatibilityFallbackBeforeThePlanStaysStickyAcrossHttpMedia() throws Exception {
        DownloadProgress model = new DownloadProgress(); model.setTransferPolicy("parallel-http", 8);
        assertTrue(model.accept("SP_TRANSFER_MODE:native-http"));
        assertEquals("preparing", model.snapshot().phase);
        assertEquals("native-http", model.snapshot().transferMode);
        assertEquals(-1, model.snapshot().activeConnections);
        JSONObject plan = new JSONObject().put("formats", new JSONArray()
                .put(new JSONObject().put("format_id", "v").put("vcodec", "h264").put("acodec", "none").put("filesize", 900).put("protocol", "https"))
                .put(new JSONObject().put("format_id", "a").put("vcodec", "none").put("acodec", "aac").put("filesize", 100).put("protocol", "https")));
        model.accept(DownloadProgress.PLAN_PREFIX + plan);
        model.accept("[#video 450B/900B(50%) CN:4 DL:100B]");
        assertEquals("native-http", model.snapshot().transferMode);
        assertEquals("Old or compatibility aria summaries cannot revive a parallel claim", -1, model.snapshot().activeConnections);
        progress(model, "v", "h264", "none", "finished", 900, 900);
        JSONObject audio = event("a", "none", "aac", "downloading", 10, 100);
        audio.getJSONObject("info").put("protocol", "https"); model.accept(DownloadProgress.DOWNLOAD_PREFIX + audio);
        assertEquals("native-http", model.snapshot().transferMode);
        assertFalse(model.accept("[#video 899B/900B(99%) CN:4 DL:100B]"));
        model.accept("[#audio 20B/100B(20%) CN:6 DL:100B]");
        assertEquals("native-http", model.snapshot().transferMode);
        assertEquals(-1, model.snapshot().activeConnections);
        model.setTransferPolicy("parallel-http", 8);
        assertEquals("An explicit new policy is the only reset", "parallel-http", model.snapshot().transferMode);
        model.accept("[#audio 30B/100B(30%) CN:8 DL:100B]");
        assertEquals(8, model.snapshot().activeConnections);
    }

    @Test public void transferModeEventsAreAllowlistedAndDoNotRestartMediaProgress() throws Exception {
        DownloadProgress model = streamWithProtocol("https", "parallel-http", 4);
        model.accept("[#video 200B/1000B(20%) CN:4 DL:100B]");
        DownloadProgress.Snapshot before = model.snapshot();
        assertTrue(DownloadProgress.isProtocolLine("SP_TRANSFER_MODE:native-http"));
        for (String invalid : new String[]{"parallel-http", "native-http extra", "{}", "", "https://cdn.example/?token=private"}) {
            String line = DownloadProgress.TRANSFER_MODE_PREFIX + invalid;
            assertTrue("Even malformed protocol payloads stay out of user logs", DownloadProgress.isProtocolLine(line));
            assertFalse(model.accept(line));
            assertEquals("parallel-http", model.snapshot().transferMode);
            assertEquals(before.progress, model.snapshot().progress, 0);
        }
        assertTrue(model.accept("SP_TRANSFER_MODE:native-http"));
        assertEquals(before.phase, model.snapshot().phase);
        assertEquals(before.progress, model.snapshot().progress, 0);
        assertEquals(before.stageProgress, model.snapshot().stageProgress, 0);
        assertEquals(before.downloadedBytes, model.snapshot().downloadedBytes);
        assertEquals("native-http", model.snapshot().toJson().getString("transferMode"));
        assertEquals(-1, model.snapshot().toJson().getInt("activeConnections"));
        assertFalse(model.snapshot().toJson().toString().contains("private"));
    }

    @Test public void compatibilityHttpOverrideDoesNotDisableNativeHlsFragmentConcurrency() throws Exception {
        DownloadProgress model = new DownloadProgress(); model.setTransferPolicy("parallel-http", 4);
        model.accept("SP_TRANSFER_MODE:native-http");
        JSONObject plan = new JSONObject().put("single", new JSONObject().put("format_id", "hls")
                .put("vcodec", "h264").put("acodec", "aac").put("filesize", 1000).put("protocol", "m3u8_native"));
        model.accept(DownloadProgress.PLAN_PREFIX + plan);
        assertEquals("fragments", model.snapshot().transferMode);
        JSONObject event = event("hls", "h264", "aac", "downloading", 100, 1000);
        event.getJSONObject("info").put("protocol", "m3u8_native"); model.accept(DownloadProgress.DOWNLOAD_PREFIX + event);
        assertEquals("fragments", model.snapshot().transferMode);
        assertEquals(4, model.snapshot().configuredConnections);
        assertEquals(-1, model.snapshot().activeConnections);
    }

    private static DownloadProgress streamWithProtocol(String protocol, String strategy, int connections) throws Exception {
        DownloadProgress model = new DownloadProgress(); model.setTransferPolicy(strategy, connections);
        JSONObject event = event("v", "h264", "none", "downloading", 100, 1000);
        event.getJSONObject("info").put("protocol", protocol);
        assertTrue(model.accept(DownloadProgress.DOWNLOAD_PREFIX + event)); return model;
    }

    private static DownloadProgress twoStreams(long video, long audio) throws Exception {
        DownloadProgress model = new DownloadProgress();
        JSONObject plan = new JSONObject().put("formats", new JSONArray()
                .put(new JSONObject().put("format_id", "v").put("vcodec", "h264").put("acodec", "none").put("filesize", video))
                .put(new JSONObject().put("format_id", "a").put("vcodec", "none").put("acodec", "aac").put("filesize", audio)));
        assertTrue(model.accept(DownloadProgress.PLAN_PREFIX + plan)); return model;
    }
    private static JSONObject event(String id, String video, String audio, String status, long bytes, long size) throws Exception {
        return new JSONObject().put("info", new JSONObject().put("format_id", id).put("vcodec", video).put("acodec", audio))
                .put("progress", new JSONObject().put("status", status).put("downloaded_bytes", bytes).put("total_bytes", size));
    }
    private static void progress(DownloadProgress model, String id, String video, String audio, String status, long bytes, long size) throws Exception {
        assertTrue(model.accept(DownloadProgress.DOWNLOAD_PREFIX + event(id, video, audio, status, bytes, size)));
    }
}
