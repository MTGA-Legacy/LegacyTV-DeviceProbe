package com.mtgalegacy.deviceprobe;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** APK staging and metadata parsing. Installation itself is always delegated to Android. */
public final class AppManager {
    public static final long MAX_UPLOAD_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_STAGED_FILES = 10;

    private final Context context;
    private final RingLog log;
    private final File uploadDirectory;

    public AppManager(Context context, RingLog log) {
        this.context = context.getApplicationContext();
        this.log = log;
        this.uploadDirectory = new File(this.context.getFilesDir(), "uploads");
        if (!uploadDirectory.exists()) {
            //noinspection ResultOfMethodCallIgnored
            uploadDirectory.mkdirs();
        }
        cleanupPartFiles();
    }

    public File getUploadDirectory() {
        return uploadDirectory;
    }

    public synchronized JSONObject finalizeUpload(File temporaryFile, String requestedName)
            throws Exception {
        if (temporaryFile == null || !temporaryFile.isFile()) {
            throw new IllegalArgumentException("temporary upload missing");
        }
        if (temporaryFile.length() <= 0 || temporaryFile.length() > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("APK size must be between 1 byte and 64 MiB");
        }
        String fileName = safeFileName(requestedName);
        if (!fileName.toLowerCase(Locale.US).endsWith(".apk")) {
            throw new IllegalArgumentException("only .apk uploads are accepted");
        }
        File target = new File(uploadDirectory, fileName);
        if (!isInsideUploadDirectory(target)) {
            throw new IllegalArgumentException("invalid upload path");
        }
        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("cannot replace existing APK");
        }
        if (!temporaryFile.renameTo(target)) {
            throw new IllegalStateException("cannot finalize APK upload");
        }
        PackageInfo packageInfo = parsePackage(target);
        if (packageInfo == null || packageInfo.packageName == null) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw new IllegalArgumentException("file is not a readable APK");
        }
        trimOldFiles(target);
        JSONObject result = metadata(target, packageInfo);
        log.info("APK 已上传: " + fileName + " (" + packageInfo.packageName + ")");
        return result;
    }

    public synchronized File findUploadedFile(String fileName) {
        String safe = safeFileName(fileName);
        if (!safe.equals(fileName)) {
            return null;
        }
        File file = new File(uploadDirectory, safe);
        return isInsideUploadDirectory(file) && file.isFile() ? file : null;
    }

    public synchronized JSONObject listJson() throws JSONException {
        JSONArray files = new JSONArray();
        File[] all = uploadDirectory.listFiles();
        if (all != null) {
            List<File> sorted = new ArrayList<File>(Arrays.asList(all));
            Collections.sort(sorted, new Comparator<File>() {
                @Override
                public int compare(File left, File right) {
                    return left.getName().compareToIgnoreCase(right.getName());
                }
            });
            for (File file : sorted) {
                if (!file.isFile() || !file.getName().toLowerCase(Locale.US).endsWith(".apk")) {
                    continue;
                }
                PackageInfo info = parsePackage(file);
                if (info != null) {
                    files.put(metadata(file, info));
                }
            }
        }
        return new JSONObject()
                .put("uploadDirectory", uploadDirectory.getName())
                .put("maxUploadBytes", MAX_UPLOAD_BYTES)
                .put("files", files);
    }

    public JSONObject metadataFor(File file) throws JSONException {
        PackageInfo info = parsePackage(file);
        return info == null ? null : metadata(file, info);
    }

    private PackageInfo parsePackage(File file) {
        try {
            PackageManager packageManager = context.getPackageManager();
            PackageInfo info = packageManager.getPackageArchiveInfo(
                    file.getAbsolutePath(), PackageManager.GET_META_DATA);
            if (info != null && info.applicationInfo != null) {
                info.applicationInfo.sourceDir = file.getAbsolutePath();
                info.applicationInfo.publicSourceDir = file.getAbsolutePath();
            }
            return info;
        } catch (Throwable error) {
            log.warn("读取 APK 元数据失败 " + file.getName() + ": " + error);
            return null;
        }
    }

    private JSONObject metadata(File file, PackageInfo info) throws JSONException {
        ApplicationInfo applicationInfo = info.applicationInfo;
        String label = info.packageName;
        if (applicationInfo != null) {
            try {
                CharSequence loaded = applicationInfo.loadLabel(context.getPackageManager());
                if (loaded != null) {
                    label = loaded.toString();
                }
            } catch (Throwable ignored) {
            }
        }
        long versionCode = info.versionCode;
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            versionCode = info.getLongVersionCode();
        }
        return new JSONObject()
                .put("fileName", file.getName())
                .put("sizeBytes", file.length())
                .put("packageName", info.packageName)
                .put("label", label)
                .put("versionName", info.versionName == null ? "" : info.versionName)
                .put("versionCode", versionCode);
    }

    private void cleanupPartFiles() {
        File[] files = uploadDirectory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.getName().endsWith(".part")) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    private void trimOldFiles(File newest) {
        File[] files = uploadDirectory.listFiles();
        if (files == null || files.length <= MAX_STAGED_FILES) {
            return;
        }
        List<File> apkFiles = new ArrayList<File>();
        for (File file : files) {
            if (file.isFile() && file.getName().toLowerCase(Locale.US).endsWith(".apk")) {
                apkFiles.add(file);
            }
        }
        Collections.sort(apkFiles, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return Long.compare(left.lastModified(), right.lastModified());
            }
        });
        while (apkFiles.size() > MAX_STAGED_FILES) {
            File candidate = apkFiles.remove(0);
            if (!candidate.equals(newest)) {
                //noinspection ResultOfMethodCallIgnored
                candidate.delete();
            }
        }
    }

    private String safeFileName(String requestedName) {
        if (requestedName == null) {
            return "upload.apk";
        }
        String name = new File(requestedName).getName();
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.length() == 0) {
            name = "upload.apk";
        }
        return name.length() > 120 ? name.substring(name.length() - 120) : name;
    }

    private boolean isInsideUploadDirectory(File file) {
        try {
            String root = uploadDirectory.getCanonicalPath() + File.separator;
            return file.getCanonicalPath().startsWith(root);
        } catch (Exception error) {
            return false;
        }
    }
}
