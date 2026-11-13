package com.mtgalegacy.deviceprobe;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Application-wide coordinator shared by the TV UI and the LAN HTTP server. */
public final class ProbeRuntime {
    private static final long MAX_SHELL_TIMEOUT_MS = 30_000L;
    private static final int MAX_SHELL_OUTPUT_BYTES = 1024 * 1024;

    private final Context context;
    private final RingLog log;
    private final DeviceInfoCollector deviceInfo;
    private final CodecEnumerator codecs;
    private final AppManager apps;
    private final CodecTestRunner tests;
    private final LanHttpServer server;
    private final ExecutorService testExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object runLock = new Object();
    private final Object bridgeLock = new Object();

    private volatile Surface testSurface;
    private volatile SurfaceSampler surfaceSampler;
    private volatile ActivityBridge activityBridge;
    private JSONObject lastRun;
    private int runCounter;

    public ProbeRuntime(Context context) {
        this.context = context.getApplicationContext();
        this.log = new RingLog();
        this.deviceInfo = new DeviceInfoCollector(this.context);
        this.codecs = new CodecEnumerator(log);
        this.apps = new AppManager(this.context, log);
        this.tests = new CodecTestRunner(this, codecs, log);
        this.server = new LanHttpServer(this, log);
    }

    public void start() {
        log.info("LegacyTV Device Probe 启动");
        server.start();
    }

    public void stop() {
        server.stop();
        testExecutor.shutdownNow();
    }

    public Context getContext() {
        return context;
    }

    public RingLog getLog() {
        return log;
    }

    public DeviceInfoCollector getDeviceInfo() {
        return deviceInfo;
    }

    public CodecEnumerator getCodecs() {
        return codecs;
    }

    public AppManager getApps() {
        return apps;
    }

    public JSONObject statusJson() throws JSONException {
        JSONObject result = new JSONObject();
        result.put("app", "LegacyTV Device Probe");
        result.put("packageName", context.getPackageName());
        result.put("versionName", "0.1.0");
        result.put("versionCode", 1);
        result.put("server", server.statusJson());
        result.put("logEntries", log.toJson(RingLog.MAX_ENTRIES).length());
        JSONObject testStatus = new JSONObject();
        synchronized (runLock) {
            if (lastRun == null) {
                testStatus.put("status", "idle");
            } else {
                testStatus = new JSONObject(lastRun.toString());
            }
        }
        result.put("tests", testStatus);
        result.put("shell", new JSONObject()
                .put("enabled", true)
                .put("executor", "/system/bin/sh")
                .put("uid", android.os.Process.myUid())
                .put("root", android.os.Process.myUid() == 0));
        return result;
    }

    public JSONObject deviceJson() throws JSONException {
        return deviceInfo.collect();
    }

    public JSONObject codecsJson() throws JSONException {
        return codecs.enumerate();
    }

    public JSONArray logsJson(int limit) throws JSONException {
        return log.toJson(limit);
    }

    public void clearLogs() {
        log.clear();
        log.info("日志已清空");
    }

    public JSONObject testsJson() throws JSONException {
        JSONObject result = new JSONObject();
        result.put("catalog", tests.catalogJson());
        synchronized (runLock) {
            result.put("lastRun", lastRun == null ? JSONObject.NULL : new JSONObject(lastRun.toString()));
        }
        return result;
    }

    public String startTests(List<String> requestedIds) throws JSONException {
        final List<String> ids = requestedIds == null
                ? tests.defaultTestIds() : new ArrayList<String>(requestedIds);
        final String runId;
        final JSONObject run = new JSONObject();
        synchronized (runLock) {
            runCounter++;
            runId = "run-" + runCounter + "-" + System.currentTimeMillis();
            run.put("runId", runId);
            run.put("status", "queued");
            run.put("requestedTests", new JSONArray(ids));
            run.put("results", JSONObject.NULL);
            run.put("startedAt", JSONObject.NULL);
            run.put("finishedAt", JSONObject.NULL);
            lastRun = run;
        }
        testExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (runLock) {
                        run.put("status", "running");
                        run.put("startedAt", System.currentTimeMillis());
                    }
                    JSONArray results = tests.run(ids);
                    synchronized (runLock) {
                        run.put("status", "completed");
                        run.put("results", results);
                        run.put("finishedAt", System.currentTimeMillis());
                    }
                    log.info("编解码测试完成: " + runId);
                } catch (Throwable error) {
                    try {
                        synchronized (runLock) {
                            run.put("status", "failed");
                            run.put("error", error.toString());
                            run.put("finishedAt", System.currentTimeMillis());
                        }
                    } catch (JSONException ignored) {
                    }
                    log.error("编解码测试任务失败", error);
                }
            }
        });
        return runId;
    }

    public void setTestSurface(Surface surface, SurfaceSampler sampler) {
        this.testSurface = surface;
        this.surfaceSampler = sampler;
    }

    public Surface getTestSurface() {
        return testSurface;
    }

    public VisualSample sampleSurface() {
        SurfaceSampler sampler = surfaceSampler;
        if (sampler == null) {
            return null;
        }
        try {
            return sampler.sample();
        } catch (Throwable error) {
            log.warn("读取视频 Surface 画面失败: " + error);
            return null;
        }
    }

    public void setActivityBridge(ActivityBridge bridge) {
        synchronized (bridgeLock) {
            activityBridge = bridge;
        }
    }

    public JSONObject appsJson() throws JSONException {
        JSONObject result = apps.listJson();
        result.put("selfPackageName", context.getPackageName());
        result.put("canUninstallSelf", false);
        return result;
    }

    public JSONObject installApp(String fileName) throws JSONException {
        File file = apps.findUploadedFile(fileName);
        if (file == null) {
            return errorJson("file_not_found", "uploaded APK file not found");
        }
        JSONObject metadata = apps.metadataFor(file);
        if (metadata == null) {
            return errorJson("invalid_apk", "uploaded file is not a readable APK");
        }
        ActivityBridge bridge = activityBridge;
        if (bridge == null) {
            return errorJson("activity_unavailable", "open the TV application before installing");
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    bridge.install(file);
                } catch (Throwable error) {
                    log.error("拉起系统安装器失败", error);
                }
            }
        });
        return new JSONObject().put("ok", true).put("started", true).put("apk", metadata);
    }

    public JSONObject uninstallApp(String packageName) throws JSONException {
        if (packageName == null || packageName.trim().length() == 0) {
            return errorJson("invalid_package", "packageName is required");
        }
        String normalized = packageName.trim();
        if (context.getPackageName().equals(normalized)) {
            return errorJson("self_uninstall_blocked", "the diagnostic tool cannot uninstall itself");
        }
        try {
            context.getPackageManager().getPackageInfo(normalized, 0);
        } catch (PackageManager.NameNotFoundException error) {
            return errorJson("package_not_found", normalized);
        }
        ActivityBridge bridge = activityBridge;
        if (bridge == null) {
            return errorJson("activity_unavailable", "open the TV application before uninstalling");
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    bridge.uninstall(normalized);
                } catch (Throwable error) {
                    log.error("拉起系统卸载器失败", error);
                }
            }
        });
        return new JSONObject().put("ok", true).put("started", true).put("packageName", normalized);
    }

    public JSONObject executeShell(String command, long requestedTimeoutMs) throws JSONException {
        if (command == null || command.trim().length() == 0) {
            return errorJson("invalid_command", "command is required");
        }
        if (command.length() > 8192) {
            return errorJson("command_too_long", "command length is limited to 8192 characters");
        }
        long timeoutMs = Math.max(100L, Math.min(MAX_SHELL_TIMEOUT_MS, requestedTimeoutMs));
        java.lang.Process process = null;
        StreamCollector stdout = null;
        StreamCollector stderr = null;
        boolean timedOut = false;
        int exitCode = -1;
        try {
            process = new ProcessBuilder("/system/bin/sh", "-c", command)
                    .directory(context.getFilesDir())
                    .start();
            stdout = new StreamCollector(process.getInputStream());
            stderr = new StreamCollector(process.getErrorStream());
            stdout.start();
            stderr.start();
            long deadline = SystemClockCompat.elapsedRealtime() + timeoutMs;
            while (processIsAlive(process) && SystemClockCompat.elapsedRealtime() < deadline) {
                try {
                    Thread.sleep(25L);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (processIsAlive(process)) {
                timedOut = true;
                process.destroy();
            } else {
                exitCode = process.exitValue();
            }
            stdout.join(500L);
            stderr.join(500L);
        } catch (Throwable error) {
            return new JSONObject()
                    .put("ok", false)
                    .put("command", command)
                    .put("error", error.toString())
                    .put("uid", android.os.Process.myUid())
                    .put("root", android.os.Process.myUid() == 0);
        } finally {
            if (process != null && processIsAlive(process)) {
                process.destroy();
            }
        }
        log.info("远程 shell 命令已执行（输出未写入日志）");
        return new JSONObject()
                .put("ok", !timedOut && exitCode == 0)
                .put("command", command)
                .put("stdout", stdout == null ? "" : stdout.text())
                .put("stderr", stderr == null ? "" : stderr.text())
                .put("exitCode", exitCode)
                .put("timedOut", timedOut)
                .put("timeoutMs", timeoutMs)
                .put("uid", android.os.Process.myUid())
                .put("root", android.os.Process.myUid() == 0)
                .put("workingDirectory", context.getFilesDir().getAbsolutePath());
    }

    private static JSONObject errorJson(String code, String message) throws JSONException {
        return new JSONObject().put("ok", false).put("error", code).put("message", message);
    }

    private static boolean processIsAlive(java.lang.Process process) {
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException running) {
            return true;
        }
    }

    public interface ActivityBridge {
        void install(File file);

        void uninstall(String packageName);
    }

    public interface SurfaceSampler {
        VisualSample sample();
    }

    public static final class VisualSample {
        public final boolean blackScreenSuspected;
        public final boolean garbledSuspected;
        public final double meanLuma;
        public final double variance;

        public VisualSample(boolean blackScreenSuspected, boolean garbledSuspected,
                            double meanLuma, double variance) {
            this.blackScreenSuspected = blackScreenSuspected;
            this.garbledSuspected = garbledSuspected;
            this.meanLuma = meanLuma;
            this.variance = variance;
        }
    }

    private static final class StreamCollector extends Thread {
        private final InputStream input;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        StreamCollector(InputStream input) {
            this.input = input;
            setDaemon(true);
        }

        @Override
        public void run() {
            byte[] buffer = new byte[4096];
            int remaining = MAX_SHELL_OUTPUT_BYTES;
            try {
                int read;
                while (remaining > 0 && (read = input.read(buffer, 0, Math.min(buffer.length, remaining))) >= 0) {
                    output.write(buffer, 0, read);
                    remaining -= read;
                    if (read == 0) {
                        break;
                    }
                }
            } catch (Exception ignored) {
            } finally {
                try {
                    input.close();
                } catch (Exception ignored) {
                }
            }
        }

        String text() {
            return new String(output.toByteArray(), Charset.forName("UTF-8"));
        }
    }

    /** Avoids newer Process.waitFor(timeout) APIs on API 19. */
    private static final class SystemClockCompat {
        static long elapsedRealtime() {
            return android.os.SystemClock.elapsedRealtime();
        }
    }
}
