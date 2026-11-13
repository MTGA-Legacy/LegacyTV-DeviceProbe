package com.mtgalegacy.deviceprobe;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ConfigurationInfo;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Collects only read-only values exposed by Android and the local process. */
public final class DeviceInfoCollector {
    private static final int MAX_CPUINFO_BYTES = 16 * 1024;
    private static final String[] SYSTEM_PROPERTY_KEYS = new String[]{
            "ro.board.platform",
            "ro.hardware",
            "ro.product.board",
            "ro.product.manufacturer",
            "ro.soc.manufacturer",
            "ro.soc.model",
            "ro.boot.hardware",
            "ro.boot.board",
            "ro.chipname",
            "ro.mediatek.platform",
            "ro.product.cpu.abi"
    };

    private final Context context;

    public DeviceInfoCollector(Context context) {
        this.context = context.getApplicationContext();
    }

    public JSONObject collect() throws JSONException {
        JSONObject result = new JSONObject();
        JSONObject android = new JSONObject();
        android.put("release", safe(Build.VERSION.RELEASE));
        android.put("apiLevel", Build.VERSION.SDK_INT);
        android.put("codename", safe(Build.VERSION.CODENAME));
        result.put("android", android);

        JSONObject build = new JSONObject();
        build.put("brand", safe(Build.BRAND));
        build.put("manufacturer", safe(Build.MANUFACTURER));
        build.put("model", safe(Build.MODEL));
        build.put("device", safe(Build.DEVICE));
        build.put("product", safe(Build.PRODUCT));
        build.put("board", safe(Build.BOARD));
        build.put("hardware", safe(Build.HARDWARE));
        result.put("build", build);

        JSONObject cpu = new JSONObject();
        cpu.put("abi", safe(Build.CPU_ABI));
        cpu.put("abi2", safe(Build.CPU_ABI2));
        if (Build.VERSION.SDK_INT >= 21) {
            JSONArray supportedAbis = new JSONArray();
            for (String abi : Build.SUPPORTED_ABIS) {
                supportedAbis.put(safe(abi));
            }
            cpu.put("supportedAbis", supportedAbis);
        } else {
            cpu.put("supportedAbis", new JSONArray().put(safe(Build.CPU_ABI)).put(safe(Build.CPU_ABI2)));
        }
        cpu.put("procCpuInfo", readCpuInfo());
        result.put("cpu", cpu);

        ActivityManager activityManager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        if (activityManager != null) {
            activityManager.getMemoryInfo(memoryInfo);
        }
        JSONObject memory = new JSONObject();
        memory.put("totalBytes", Math.max(0L, memoryInfo.totalMem));
        memory.put("availableBytes", Math.max(0L, memoryInfo.availMem));
        memory.put("lowMemory", memoryInfo.lowMemory);
        result.put("memory", memory);

        result.put("display", collectDisplay());
        result.put("opengl", collectOpenGl());
        result.put("systemProperties", collectSystemProperties());
        result.put("network", collectNetwork());
        return result;
    }

    private JSONObject collectDisplay() throws JSONException {
        JSONObject displayJson = new JSONObject();
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            return displayJson.put("available", false);
        }
        Display display = windowManager.getDefaultDisplay();
        DisplayMetrics metrics = new DisplayMetrics();
        display.getMetrics(metrics);
        displayJson.put("available", true);
        displayJson.put("width", display.getWidth());
        displayJson.put("height", display.getHeight());
        displayJson.put("refreshRateHz", round(display.getRefreshRate(), 3));
        displayJson.put("density", round(metrics.density, 3));
        displayJson.put("densityDpi", metrics.densityDpi);
        return displayJson;
    }

    private JSONObject collectOpenGl() throws JSONException {
        JSONObject openGl = new JSONObject();
        ActivityManager activityManager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ConfigurationInfo configurationInfo = activityManager == null
                ? null : activityManager.getDeviceConfigurationInfo();
        int version = configurationInfo == null ? 0 : configurationInfo.reqGlEsVersion;
        openGl.put("requiredVersionHex", String.format(Locale.US, "0x%08x", version));
        if (version != 0) {
            int major = (version >> 16) & 0xffff;
            int minor = version & 0xffff;
            openGl.put("major", major);
            openGl.put("minor", minor);
            openGl.put("version", major + "." + minor);
        } else {
            openGl.put("version", JSONObject.NULL);
        }
        openGl.put("renderer", JSONObject.NULL);
        openGl.put("vendor", JSONObject.NULL);
        openGl.put("source", "ActivityManager.ConfigurationInfo; renderer requires a current GL context");
        return openGl;
    }

    private JSONObject collectSystemProperties() throws JSONException {
        JSONObject properties = new JSONObject();
        Map<String, String> fromBuildProp = readBuildPropFile();
        for (String key : SYSTEM_PROPERTY_KEYS) {
            String value = readSystemProperty(key);
            if (value.length() == 0) {
                value = fromBuildProp.containsKey(key) ? fromBuildProp.get(key) : "";
            }
            if (value.length() > 0) {
                properties.put(key, value);
            }
        }
        return properties;
    }

    private JSONObject collectNetwork() throws JSONException {
        JSONArray ipv4 = new JSONArray();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface networkInterface = interfaces.nextElement();
                    Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        java.net.InetAddress address = addresses.nextElement();
                        if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                            ipv4.put(address.getHostAddress());
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Network information is optional and must never block diagnostics.
        }
        return new JSONObject().put("ipv4", ipv4);
    }

    private static String readSystemProperty(String key) {
        try {
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            Method method = clazz.getMethod("get", String.class, String.class);
            Object value = method.invoke(null, key, "");
            return value == null ? "" : String.valueOf(value).trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static Map<String, String> readBuildPropFile() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        File file = new File("/system/build.prop");
        if (!file.isFile()) {
            return values;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            int bytes = 0;
            while ((line = reader.readLine()) != null && bytes < MAX_CPUINFO_BYTES) {
                bytes += line.length();
                int split = line.indexOf('=');
                if (split <= 0) {
                    continue;
                }
                String key = line.substring(0, split).trim();
                for (String allowed : SYSTEM_PROPERTY_KEYS) {
                    if (allowed.equals(key)) {
                        values.put(key, line.substring(split + 1).trim());
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
            values.clear();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
        return values;
    }

    private static String readCpuInfo() {
        File file = new File("/proc/cpuinfo");
        StringBuilder result = new StringBuilder();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null && result.length() < MAX_CPUINFO_BYTES) {
                if (result.length() > 0) {
                    result.append('\n');
                }
                result.append(line);
            }
        } catch (Exception error) {
            return "unavailable: " + error.getClass().getSimpleName();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
        return result.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static double round(float value, int digits) {
        double scale = Math.pow(10, digits);
        return Math.round(value * scale) / scale;
    }
}
