package com.mtgalegacy.deviceprobe;

import android.content.res.AssetFileDescriptor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.view.Surface;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Runs short local MediaCodec probes. The assets never use the network. */
public final class CodecTestRunner {
    private static final long TEST_LIMIT_MS = 2_500L;
    private static final long DEQUEUE_TIMEOUT_US = 10_000L;

    private final ProbeRuntime runtime;
    private final CodecEnumerator enumerator;
    private final RingLog log;

    public CodecTestRunner(ProbeRuntime runtime, CodecEnumerator enumerator, RingLog log) {
        this.runtime = runtime;
        this.enumerator = enumerator;
        this.log = log;
    }

    public JSONArray catalogJson() throws JSONException {
        JSONArray result = new JSONArray();
        for (TestSpec spec : specs()) {
            result.put(spec.toJson());
        }
        return result;
    }

    public List<String> defaultTestIds() {
        List<String> ids = new ArrayList<String>();
        for (TestSpec spec : specs()) {
            ids.add(spec.id);
        }
        return ids;
    }

    public JSONArray run(List<String> requestedIds) throws JSONException {
        JSONArray result = new JSONArray();
        for (TestSpec spec : specs()) {
            if (requestedIds != null && !requestedIds.contains(spec.id)) {
                continue;
            }
            result.put(runOne(spec));
        }
        return result;
    }

    private JSONObject runOne(TestSpec spec) throws JSONException {
        JSONObject result = new JSONObject();
        result.put("id", spec.id);
        result.put("label", spec.label);
        result.put("mime", spec.mime);
        result.put("asset", spec.asset);
        result.put("video", spec.video);

        if (spec.requiresHevc && !enumerator.hasDecoder(spec.mime)) {
            result.put("status", "skipped");
            result.put("skipReason", "device did not declare an HEVC decoder");
            return result;
        }

        List<CodecEnumerator.CodecRecord> candidates = enumerator.findDecoders(spec.mime);
        if (candidates.isEmpty()) {
            result.put("status", "unsupported_declared");
            result.put("skipReason", "no decoder was declared for this MIME type");
            return result;
        }

        JSONArray attempts = new JSONArray();
        JSONObject successfulAttempt = null;
        for (CodecEnumerator.CodecRecord candidate : candidates) {
            JSONObject attempt = runWithCodec(spec, candidate);
            attempts.put(attempt);
            if ("passed".equals(attempt.optString("status"))) {
                successfulAttempt = attempt;
                break;
            }
        }
        result.put("attempts", attempts);
        if (successfulAttempt == null) {
            result.put("status", "failed");
            result.put("error", "all declared decoder attempts failed");
        } else {
            result.put("status", "passed");
            result.put("selectedCodec", successfulAttempt.optString("codecName"));
            result.put("hardwareInference", successfulAttempt.optString("hardwareInference"));
            result.put("firstFrameMs", successfulAttempt.opt("firstFrameMs"));
            result.put("durationMs", successfulAttempt.optLong("durationMs", 0));
            result.put("actualResolution", successfulAttempt.opt("actualResolution"));
            result.put("decodeErrors", successfulAttempt.optJSONArray("decodeErrors"));
            result.put("droppedFrames", successfulAttempt.optInt("droppedFrames", 0));
            result.put("blackScreenSuspected", successfulAttempt.opt("blackScreenSuspected"));
            result.put("garbledSuspected", successfulAttempt.opt("garbledSuspected"));
            result.put("visualCheck", successfulAttempt.optString("visualCheck"));
        }
        return result;
    }

    private JSONObject runWithCodec(TestSpec spec, CodecEnumerator.CodecRecord candidate)
            throws JSONException {
        long startedAt = SystemClock.elapsedRealtime();
        JSONObject result = new JSONObject();
        JSONArray errors = new JSONArray();
        MediaExtractor extractor = null;
        MediaCodec codec = null;
        AssetFileDescriptor asset = null;
        boolean configureOk = false;
        boolean startOk = false;
        boolean sawEos = false;
        int inputSamples = 0;
        int outputFrames = 0;
        long firstFrameMs = -1L;
        long outputBytes = 0L;
        MediaFormat inputFormat = null;
        MediaFormat actualFormat = null;
        ProbeRuntime.VisualSample visualSample = null;
        Surface surface = spec.video ? runtime.getTestSurface() : null;
        try {
            result.put("codecName", candidate.name);
            result.put("hardwareInference", candidate.hardwareInference);
            result.put("surfaceAvailable", surface != null);

            asset = runtime.getContext().getAssets().openFd(spec.asset);
            extractor = new MediaExtractor();
            extractor.setDataSource(asset.getFileDescriptor(), asset.getStartOffset(), asset.getLength());
            int trackIndex = findTrack(extractor, spec.mime);
            if (trackIndex < 0) {
                throw new IllegalStateException("asset has no track for " + spec.mime);
            }
            extractor.selectTrack(trackIndex);
            inputFormat = extractor.getTrackFormat(trackIndex);

            codec = MediaCodec.createByCodecName(candidate.name);
            result.put("created", true);
            codec.configure(inputFormat, surface, null, 0);
            configureOk = true;
            result.put("configured", true);
            codec.start();
            startOk = true;
            result.put("started", true);

            ByteBuffer[] inputBuffers = codec.getInputBuffers();
            ByteBuffer[] outputBuffers = codec.getOutputBuffers();
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            long decodeStarted = SystemClock.elapsedRealtime();
            while (!sawEos && SystemClock.elapsedRealtime() - decodeStarted < TEST_LIMIT_MS) {
                int inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                if (inputIndex >= 0 && inputBuffers != null && inputIndex < inputBuffers.length) {
                    ByteBuffer input = inputBuffers[inputIndex];
                    input.clear();
                    int sampleSize = extractor.readSampleData(input, 0);
                    long sampleTime = extractor.getSampleTime();
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        sawEos = true;
                    } else {
                        if (sampleSize > input.capacity()) {
                            throw new IllegalStateException("sample exceeds codec input buffer");
                        }
                        codec.queueInputBuffer(inputIndex, 0, sampleSize,
                                Math.max(0L, sampleTime), extractor.getSampleFlags());
                        inputSamples++;
                        extractor.advance();
                    }
                }

                int outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US);
                if (outputIndex >= 0) {
                    boolean sampleAfterRelease = false;
                    if (bufferInfo.size > 0) {
                        outputFrames++;
                        outputBytes += bufferInfo.size;
                        if (firstFrameMs < 0) {
                            firstFrameMs = SystemClock.elapsedRealtime() - decodeStarted;
                            sampleAfterRelease = spec.video && surface != null;
                        }
                    }
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawEos = true;
                    }
                    boolean render = spec.video && surface != null;
                    codec.releaseOutputBuffer(outputIndex, render);
                    if (sampleAfterRelease) {
                        try {
                            Thread.sleep(30L);
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                        }
                        visualSample = runtime.sampleSurface();
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputBuffers = codec.getOutputBuffers();
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    actualFormat = codec.getOutputFormat();
                }
            }

            long durationMs = SystemClock.elapsedRealtime() - startedAt;
            result.put("status", outputFrames > 0 ? "passed" : "failed");
            result.put("firstFrameMs", firstFrameMs < 0 ? JSONObject.NULL : firstFrameMs);
            result.put("durationMs", durationMs);
            result.put("outputFrames", outputFrames);
            result.put("outputBytes", outputBytes);
            result.put("droppedFrames", spec.video ? Math.max(0, inputSamples - outputFrames) : 0);
            result.put("actualResolution", resolutionJson(actualFormat == null ? inputFormat : actualFormat));
            result.put("decodeErrors", errors);
            if (spec.video) {
                if (visualSample == null) {
                    result.put("visualCheck", "not_run");
                    result.put("blackScreenSuspected", JSONObject.NULL);
                    result.put("garbledSuspected", JSONObject.NULL);
                } else {
                    result.put("visualCheck", "heuristic");
                    result.put("blackScreenSuspected", visualSample.blackScreenSuspected);
                    result.put("garbledSuspected", visualSample.garbledSuspected);
                    result.put("visualMeanLuma", visualSample.meanLuma);
                    result.put("visualVariance", visualSample.variance);
                }
            } else {
                result.put("visualCheck", "not_applicable");
                result.put("blackScreenSuspected", JSONObject.NULL);
                result.put("garbledSuspected", JSONObject.NULL);
            }
        } catch (Throwable error) {
            errors.put(error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
            result.put("status", "failed");
            result.put("error", error.toString());
            result.put("decodeErrors", errors);
            result.put("durationMs", SystemClock.elapsedRealtime() - startedAt);
            result.put("actualResolution", resolutionJson(actualFormat == null ? inputFormat : actualFormat));
            result.put("firstFrameMs", firstFrameMs < 0 ? JSONObject.NULL : firstFrameMs);
            result.put("outputFrames", outputFrames);
            result.put("outputBytes", outputBytes);
            result.put("droppedFrames", spec.video ? Math.max(0, inputSamples - outputFrames) : 0);
            result.put("visualCheck", spec.video ? "not_run" : "not_applicable");
            result.put("blackScreenSuspected", JSONObject.NULL);
            result.put("garbledSuspected", JSONObject.NULL);
            log.warn("编解码测试失败 " + spec.id + " / " + candidate.name + ": " + error);
        } finally {
            if (codec != null) {
                if (startOk) {
                    try {
                        codec.stop();
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    codec.release();
                } catch (Throwable ignored) {
                }
            }
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Throwable ignored) {
                }
            }
            if (asset != null) {
                try {
                    asset.close();
                } catch (Throwable ignored) {
                }
            }
            try {
                result.put("configureOk", configureOk);
                result.put("startOk", startOk);
            } catch (JSONException ignored) {
            }
        }
        return result;
    }

    private static int findTrack(MediaExtractor extractor, String mime) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String trackMime = format.getString(MediaFormat.KEY_MIME);
            if (mime.equalsIgnoreCase(trackMime)) {
                return i;
            }
        }
        return -1;
    }

    private static JSONObject resolutionJson(MediaFormat format) throws JSONException {
        if (format == null) {
            return new JSONObject().put("available", false);
        }
        JSONObject result = new JSONObject().put("available", true);
        if (format.containsKey(MediaFormat.KEY_WIDTH)) {
            result.put("width", format.getInteger(MediaFormat.KEY_WIDTH));
        }
        if (format.containsKey(MediaFormat.KEY_HEIGHT)) {
            result.put("height", format.getInteger(MediaFormat.KEY_HEIGHT));
        }
        return result;
    }

    private static List<TestSpec> specs() {
        List<TestSpec> result = new ArrayList<TestSpec>();
        result.add(new TestSpec("h264_baseline", "H.264 Baseline", "video/avc",
                "probes/h264_baseline.mp4", true, false));
        result.add(new TestSpec("h264_main", "H.264 Main", "video/avc",
                "probes/h264_main.mp4", true, false));
        result.add(new TestSpec("h264_high", "H.264 High", "video/avc",
                "probes/h264_high.mp4", true, false));
        result.add(new TestSpec("hevc", "HEVC", "video/hevc",
                "probes/hevc.mp4", true, true));
        result.add(new TestSpec("aac", "AAC", "audio/mp4a-latm",
                "probes/aac.m4a", false, false));
        result.add(new TestSpec("mp3", "MP3", "audio/mpeg",
                "probes/mp3.mp3", false, false));
        return result;
    }

    private static final class TestSpec {
        final String id;
        final String label;
        final String mime;
        final String asset;
        final boolean video;
        final boolean requiresHevc;

        TestSpec(String id, String label, String mime, String asset,
                 boolean video, boolean requiresHevc) {
            this.id = id;
            this.label = label;
            this.mime = mime;
            this.asset = asset;
            this.video = video;
            this.requiresHevc = requiresHevc;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("id", id)
                    .put("label", label)
                    .put("mime", mime)
                    .put("asset", asset)
                    .put("video", video)
                    .put("requiresDeclaredHevc", requiresHevc)
                    .put("durationLimitMs", TEST_LIMIT_MS);
        }
    }
}
