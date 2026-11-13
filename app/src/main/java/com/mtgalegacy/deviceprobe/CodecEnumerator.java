package com.mtgalegacy.deviceprobe;

import android.annotation.TargetApi;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.util.Range;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Enumerates codec declarations without treating them as proof of runtime support. */
public final class CodecEnumerator {
    private final RingLog log;

    public CodecEnumerator(RingLog log) {
        this.log = log;
    }

    public JSONObject enumerate() throws JSONException {
        List<CodecRecord> records = collectRecords();
        JSONArray codecs = new JSONArray();
        for (CodecRecord record : records) {
            codecs.put(record.toJson());
        }
        JSONObject result = new JSONObject();
        result.put("apiLevel", Build.VERSION.SDK_INT);
        result.put("source", Build.VERSION.SDK_INT >= 21
                ? "MediaCodecList.ALL_CODECS"
                : "MediaCodecList.getCodecCount/getCodecInfoAt");
        result.put("declarationOnly", true);
        result.put("rangeNote", Build.VERSION.SDK_INT >= 21
                ? "VideoCapabilities ranges are declaration data, not runtime proof"
                : "API 19 has no VideoCapabilities; use /api/tests runtime matrix");
        result.put("codecs", codecs);
        return result;
    }

    public List<CodecRecord> findDecoders(String mime) {
        List<CodecRecord> result = new ArrayList<CodecRecord>();
        for (CodecRecord record : collectRecords()) {
            if (!record.encoder && record.supportsMime(mime)) {
                result.add(record);
            }
        }
        Collections.sort(result, new Comparator<CodecRecord>() {
            @Override
            public int compare(CodecRecord left, CodecRecord right) {
                return Integer.compare(hardwareRank(right), hardwareRank(left));
            }
        });
        return result;
    }

    public boolean hasDecoder(String mime) {
        return !findDecoders(mime).isEmpty();
    }

    private int hardwareRank(CodecRecord record) {
        if ("hardware".equals(record.hardwareInference)) {
            return 3;
        }
        if ("likely_hardware".equals(record.hardwareInference)) {
            return 2;
        }
        if ("software".equals(record.hardwareInference)) {
            return 1;
        }
        return 0;
    }

    private List<CodecRecord> collectRecords() {
        List<CodecRecord> records = new ArrayList<CodecRecord>();
        try {
            MediaCodecInfo[] infos = getCodecInfos();
            for (MediaCodecInfo info : infos) {
                CodecRecord record = new CodecRecord(info.getName(), info.isEncoder(),
                        inferHardware(info));
                String[] types = info.getSupportedTypes();
                for (String type : types) {
                    record.types.add(type);
                    try {
                        record.capabilities.add(CapabilityRecord.from(type, info.getCapabilitiesForType(type)));
                    } catch (Throwable error) {
                        log.warn("读取 codec 能力失败 " + info.getName() + "/" + type + ": " + error);
                    }
                }
                records.add(record);
            }
        } catch (Throwable error) {
            log.error("枚举 MediaCodec 失败", error);
        }
        return records;
    }

    private static MediaCodecInfo[] getCodecInfos() {
        if (Build.VERSION.SDK_INT >= 21) {
            return new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
        }
        int count = MediaCodecList.getCodecCount();
        MediaCodecInfo[] infos = new MediaCodecInfo[count];
        for (int i = 0; i < count; i++) {
            infos[i] = MediaCodecList.getCodecInfoAt(i);
        }
        return infos;
    }

    private static String inferHardware(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                if (info.isSoftwareOnly()) {
                    return "software";
                }
                if (info.isHardwareAccelerated()) {
                    return "hardware";
                }
            } catch (Throwable ignored) {
            }
        }
        String name = info.getName().toLowerCase(Locale.US);
        if (name.startsWith("omx.google.")
                || name.startsWith("c2.android.")
                || name.startsWith("c2.google.")
                || name.contains("software")
                || name.contains(".sw.")
                || name.contains("ffmpeg")) {
            return "software";
        }
        if (name.startsWith("omx.") || name.startsWith("c2.")) {
            return "likely_hardware";
        }
        return "unknown";
    }

    public static final class CodecRecord {
        public final String name;
        public final boolean encoder;
        public final String hardwareInference;
        public final List<String> types = new ArrayList<String>();
        public final List<CapabilityRecord> capabilities = new ArrayList<CapabilityRecord>();

        CodecRecord(String name, boolean encoder, String hardwareInference) {
            this.name = name;
            this.encoder = encoder;
            this.hardwareInference = hardwareInference;
        }

        boolean supportsMime(String mime) {
            for (String type : types) {
                if (mime.equalsIgnoreCase(type)) {
                    return true;
                }
            }
            return false;
        }

        JSONObject toJson() throws JSONException {
            JSONObject result = new JSONObject();
            result.put("name", name);
            result.put("encoder", encoder);
            result.put("hardwareInference", hardwareInference);
            JSONArray typeArray = new JSONArray();
            for (String type : types) {
                typeArray.put(type);
            }
            result.put("supportedTypes", typeArray);
            JSONArray capabilityArray = new JSONArray();
            for (CapabilityRecord capability : capabilities) {
                capabilityArray.put(capability.toJson());
            }
            result.put("capabilities", capabilityArray);
            return result;
        }
    }

    public static final class CapabilityRecord {
        final String mime;
        final int[] colorFormats;
        final MediaCodecInfo.CodecProfileLevel[] profileLevels;
        final JSONObject videoRange;
        final JSONObject audioRange;

        private CapabilityRecord(String mime, int[] colorFormats,
                                 MediaCodecInfo.CodecProfileLevel[] profileLevels,
                                 JSONObject videoRange, JSONObject audioRange) {
            this.mime = mime;
            this.colorFormats = colorFormats == null ? new int[0] : colorFormats;
            this.profileLevels = profileLevels == null
                    ? new MediaCodecInfo.CodecProfileLevel[0] : profileLevels;
            this.videoRange = videoRange;
            this.audioRange = audioRange;
        }

        static CapabilityRecord from(String mime, MediaCodecInfo.CodecCapabilities capabilities)
                throws JSONException {
            JSONObject videoRange = new JSONObject();
            JSONObject audioRange = new JSONObject();
            videoRange.put("available", false);
            audioRange.put("available", false);
            if (Build.VERSION.SDK_INT >= 21) {
                fillApi21Ranges(capabilities, videoRange, audioRange);
            } else {
                videoRange.put("reason", "API19 does not expose VideoCapabilities");
                audioRange.put("reason", "API19 does not expose AudioCapabilities");
            }
            return new CapabilityRecord(mime, capabilities.colorFormats,
                    capabilities.profileLevels, videoRange, audioRange);
        }

        @TargetApi(21)
        private static void fillApi21Ranges(MediaCodecInfo.CodecCapabilities capabilities,
                                             JSONObject videoRange, JSONObject audioRange)
                throws JSONException {
            try {
                MediaCodecInfo.VideoCapabilities video = capabilities.getVideoCapabilities();
                if (video != null) {
                    videoRange.put("available", true);
                    videoRange.put("width", rangeToJson(video.getSupportedWidths()));
                    videoRange.put("height", rangeToJson(video.getSupportedHeights()));
                    videoRange.put("frameRate", rangeToJson(video.getSupportedFrameRates()));
                }
            } catch (Throwable error) {
                videoRange.put("error", error.toString());
            }
            try {
                MediaCodecInfo.AudioCapabilities audio = capabilities.getAudioCapabilities();
                if (audio != null) {
                    audioRange.put("available", true);
                    audioRange.put("sampleRates", intArrayToJson(audio.getSupportedSampleRates()));
                    audioRange.put("maxInputChannelCount", audio.getMaxInputChannelCount());
                }
            } catch (Throwable error) {
                audioRange.put("error", error.toString());
            }
        }

        @TargetApi(21)
        private static JSONObject rangeToJson(Range<?> range) throws JSONException {
            JSONObject object = new JSONObject();
            if (range == null) {
                return object;
            }
            object.put("lower", range.getLower());
            object.put("upper", range.getUpper());
            return object;
        }

        private static JSONArray intArrayToJson(int[] values) {
            JSONArray array = new JSONArray();
            if (values != null) {
                for (int value : values) {
                    array.put(value);
                }
            }
            return array;
        }

        JSONObject toJson() throws JSONException {
            JSONObject result = new JSONObject();
            result.put("mime", mime);
            result.put("colorFormats", intArrayToJson(colorFormats));
            JSONArray profiles = new JSONArray();
            for (MediaCodecInfo.CodecProfileLevel profile : profileLevels) {
                profiles.put(new JSONObject()
                        .put("profile", profile.profile)
                        .put("level", profile.level));
            }
            result.put("profileLevels", profiles);
            result.put("videoRange", videoRange);
            result.put("audioRange", audioRange);
            return result;
        }
    }
}
