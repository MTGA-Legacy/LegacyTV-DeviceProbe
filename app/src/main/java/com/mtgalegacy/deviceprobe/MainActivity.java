package com.mtgalegacy.deviceprobe;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

/** Remote-control friendly TV screen. Every long-running operation is delegated to ProbeRuntime. */
public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ProbeRuntime runtime;
    private TextView serverText;
    private TextView outputText;
    private TextureView preview;
    private Surface previewSurface;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshUi();
            handler.postDelayed(this, 1_000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        runtime = ((DeviceProbeApplication) getApplication()).getRuntime();
        setContentView(createContent());
        runtime.setActivityBridge(new ProbeRuntime.ActivityBridge() {
            @Override
            public void install(File file) {
                launchInstaller(file);
            }

            @Override
            public void uninstall(String packageName) {
                launchUninstaller(packageName);
            }
        });
        handler.post(refreshRunnable);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
        runtime.setTestSurface(null, null);
        runtime.setActivityBridge(null);
        super.onDestroy();
    }

    private View createContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.TOP);
        root.setPadding(dp(28), dp(22), dp(28), dp(22));
        root.setBackgroundColor(Color.rgb(16, 19, 24));

        TextView title = text("LegacyTV Device Probe", 26, Color.WHITE);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        serverText = text("正在启动 HTTP 服务…", 16, Color.rgb(184, 193, 209));
        root.addView(serverText, marginParams(0, 8, 0, 12));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        body.addView(controls, new LinearLayout.LayoutParams(dp(280),
                ViewGroup.LayoutParams.MATCH_PARENT));
        addButton(controls, "刷新设备信息", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                refreshUi();
            }
        });
        addButton(controls, "枚举编解码器", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    showJson(runtime.codecsJson());
                } catch (JSONException error) {
                    outputText.setText(error.toString());
                }
            }
        });
        addButton(controls, "运行全部测试", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    String runId = runtime.startTests(null);
                    outputText.setText("测试已排队: " + runId);
                } catch (JSONException error) {
                    outputText.setText(error.toString());
                }
            }
        });
        addButton(controls, "清空日志", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                runtime.clearLogs();
                refreshUi();
            }
        });

        preview = new TextureView(this);
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surfaceTexture,
                                                   int width, int height) {
                previewSurface = new Surface(surfaceTexture);
                runtime.setTestSurface(previewSurface, new ProbeRuntime.SurfaceSampler() {
                    @Override
                    public ProbeRuntime.VisualSample sample() {
                        return samplePreview();
                    }
                });
            }

            @Override
            public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture surfaceTexture,
                                                    int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture surfaceTexture) {
                runtime.setTestSurface(null, null);
                if (previewSurface != null) {
                    previewSurface.release();
                    previewSurface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surfaceTexture) {
            }
        });
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(dp(360), dp(202));
        previewParams.gravity = Gravity.TOP;
        previewParams.leftMargin = dp(18);
        body.addView(preview, previewParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        outputText = text("等待数据…", 14, Color.rgb(245, 247, 250));
        outputText.setTypeface(android.graphics.Typeface.MONOSPACE);
        outputText.setPadding(dp(18), dp(6), dp(6), dp(6));
        scroll.addView(outputText, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(250)));
        return root;
    }

    private void addButton(LinearLayout parent, String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setFocusable(true);
        button.setMinHeight(dp(54));
        button.setBackgroundResource(com.mtgalegacy.deviceprobe.R.drawable.button_focus);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60));
        params.bottomMargin = dp(8);
        parent.addView(button, params);
    }

    private void refreshUi() {
        try {
            JSONObject status = runtime.statusJson();
            JSONObject server = status.optJSONObject("server");
            if (server != null) {
                serverText.setText(String.format(Locale.US, "HTTP: %s  |  shell: /api/shell/exec",
                        server.optString("url", "http://0.0.0.0:10382")));
            }
            JSONObject tests = runtime.testsJson();
            JSONObject output = new JSONObject()
                    .put("device", runtime.deviceJson())
                    .put("status", status)
                    .put("tests", tests);
            outputText.setText(output.toString());
        } catch (Throwable error) {
            outputText.setText("读取诊断信息失败: " + error);
        }
    }

    private void showJson(JSONObject value) {
        outputText.setText(value == null ? "null" : value.toString());
    }

    private void launchInstaller(File file) {
        try {
            Uri uri;
            if (Build.VERSION.SDK_INT >= 24) {
                uri = ApkContentProvider.uriForFile(this, file);
            } else {
                //noinspection ResultOfMethodCallIgnored
                file.setReadable(true, false);
                uri = Uri.fromFile(file);
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addCategory(Intent.CATEGORY_DEFAULT);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (Build.VERSION.SDK_INT >= 16 && "content".equals(uri.getScheme())) {
                intent.setClipData(ClipData.newRawUri("APK", uri));
            }
            grantInstallerPermissions(uri, intent);
            startActivity(intent);
        } catch (Throwable error) {
            runtime.getLog().error("无法拉起系统安装器", error);
        }
    }

    private void launchUninstaller(String packageName) {
        try {
            Intent intent = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + packageName));
            intent.addCategory(Intent.CATEGORY_DEFAULT);
            startActivity(intent);
        } catch (Throwable error) {
            runtime.getLog().error("无法拉起系统卸载器", error);
        }
    }

    private void grantInstallerPermissions(Uri uri, Intent intent) {
        if (!"content".equals(uri.getScheme())) {
            return;
        }
        java.util.List<android.content.pm.ResolveInfo> installers = getPackageManager()
                .queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        for (android.content.pm.ResolveInfo installer : installers) {
            if (installer.activityInfo != null && installer.activityInfo.packageName != null) {
                grantUriPermission(installer.activityInfo.packageName, uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        }
    }

    private ProbeRuntime.VisualSample samplePreview() {
        if (preview == null) {
            return null;
        }
        Bitmap bitmap = preview.getBitmap(64, 36);
        if (bitmap == null) {
            return null;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        bitmap.recycle();
        double sum = 0;
        double sumSquares = 0;
        int extremeTransitions = 0;
        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            double luma = 0.2126 * Color.red(color) + 0.7152 * Color.green(color)
                    + 0.0722 * Color.blue(color);
            sum += luma;
            sumSquares += luma * luma;
            if (i > 0) {
                int previous = pixels[i - 1];
                int previousLuma = (int) (0.2126 * Color.red(previous)
                        + 0.7152 * Color.green(previous) + 0.0722 * Color.blue(previous));
                if (Math.abs(luma - previousLuma) > 220) {
                    extremeTransitions++;
                }
            }
        }
        double mean = pixels.length == 0 ? 0 : sum / pixels.length;
        double variance = pixels.length == 0 ? 0 : (sumSquares / pixels.length) - (mean * mean);
        double transitionRatio = pixels.length <= 1 ? 0
                : (double) extremeTransitions / (pixels.length - 1);
        return new ProbeRuntime.VisualSample(mean < 3.0 && variance < 12.0,
                transitionRatio > 0.35, mean, variance);
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout.LayoutParams marginParams(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
