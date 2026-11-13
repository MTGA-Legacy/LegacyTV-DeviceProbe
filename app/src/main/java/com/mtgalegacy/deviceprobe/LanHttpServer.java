package com.mtgalegacy.deviceprobe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small dependency-free HTTP/1.1 server for the local network diagnostics API. */
public final class LanHttpServer {
    public static final int PORT = 10382;
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final long MAX_JSON_BYTES = 512 * 1024L;
    private static final int CLIENT_THREADS = 4;

    private final ProbeRuntime runtime;
    private final RingLog log;
    private final ExecutorService clients = Executors.newFixedThreadPool(CLIENT_THREADS);
    private volatile boolean running;
    private volatile String startError;
    private volatile ServerSocket serverSocket;
    private Thread acceptThread;

    public LanHttpServer(ProbeRuntime runtime, RingLog log) {
        this.runtime = runtime;
        this.log = log;
    }

    public synchronized void start() {
        if (running || (acceptThread != null && acceptThread.isAlive())) {
            return;
        }
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "device-probe-http");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        ServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        clients.shutdownNow();
    }

    public JSONObject statusJson() throws JSONException {
        JSONObject result = new JSONObject();
        result.put("running", running && serverSocket != null);
        result.put("listenAddress", "0.0.0.0");
        result.put("port", PORT);
        result.put("localIpv4", localIpv4());
        result.put("url", "http://" + localIpv4() + ":" + PORT);
        if (startError != null) {
            result.put("error", startError);
        }
        return result;
    }

    private void acceptLoop() {
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), PORT), 16);
            serverSocket = socket;
            startError = null;
            log.info("HTTP 服务已启动: 0.0.0.0:" + PORT);
            while (running) {
                try {
                    final Socket client = socket.accept();
                    clients.execute(new Runnable() {
                        @Override
                        public void run() {
                            handle(client);
                        }
                    });
                } catch (IOException error) {
                    if (running) {
                        log.error("HTTP 接收连接失败", error);
                    }
                }
            }
        } catch (Throwable error) {
            startError = error.toString();
            log.error("HTTP 服务启动失败，应用继续运行", error);
        } finally {
            running = false;
            ServerSocket socket = serverSocket;
            serverSocket = null;
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(30_000);
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream(), 8192);
            BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream(), 8192);
            String requestLine = readLine(input, MAX_HEADER_BYTES);
            if (requestLine == null || requestLine.length() == 0) {
                return;
            }
            String[] requestParts = requestLine.split(" ", 3);
            if (requestParts.length != 3) {
                writeJson(output, 400, error("bad_request", "invalid HTTP request line"));
                return;
            }
            String method = requestParts[0].toUpperCase(Locale.US);
            URI target = new URI(requestParts[1]);
            String path = target.getPath();
            Map<String, String> headers = readHeaders(input);
            long contentLength = parseContentLength(headers.get("content-length"));
            String contentType = headers.get("content-type");
            JSONObject bodyJson = null;

            if ("POST".equals(method) && "/api/apps/upload".equals(path)) {
                MultipartUpload upload = receiveMultipart(input, contentLength, contentType);
                try {
                    JSONObject metadata = runtime.getApps().finalizeUpload(upload.file, upload.fileName);
                    writeJson(output, 200, new JSONObject().put("ok", true).put("apk", metadata));
                } finally {
                    deleteQuietly(upload.file);
                }
                return;
            }

            if ("POST".equals(method) && contentLength > 0) {
                if (contentLength > MAX_JSON_BYTES) {
                    writeJson(output, 413, error("body_too_large", "JSON body is limited to 512 KiB"));
                    return;
                }
                bodyJson = new JSONObject(new String(readExact(input, contentLength), Charset.forName("UTF-8")));
            }

            JSONObject response;
            int statusCode = 200;
            if ("GET".equals(method) && "/api/status".equals(path)) {
                response = runtime.statusJson();
            } else if ("GET".equals(method) && "/api/device".equals(path)) {
                response = runtime.deviceJson();
            } else if ("GET".equals(method) && "/api/codecs".equals(path)) {
                response = runtime.codecsJson();
            } else if ("GET".equals(method) && "/api/logs".equals(path)) {
                int limit = queryInt(target.getRawQuery(), "limit", 200);
                response = new JSONObject().put("count", runtime.logsJson(limit).length())
                        .put("logs", runtime.logsJson(limit));
            } else if ("POST".equals(method) && "/api/logs/clear".equals(path)) {
                runtime.clearLogs();
                response = new JSONObject().put("ok", true);
            } else if ("GET".equals(method) && "/api/tests".equals(path)) {
                response = runtime.testsJson();
            } else if ("POST".equals(method) && "/api/tests/run".equals(path)) {
                List<String> ids = bodyJson == null
                        ? null : readStringArray(bodyJson.optJSONArray("tests"));
                String runId = runtime.startTests(ids);
                response = new JSONObject().put("ok", true).put("runId", runId)
                        .put("status", "queued");
                statusCode = 202;
            } else if ("GET".equals(method) && "/api/apps".equals(path)) {
                response = runtime.appsJson();
            } else if ("POST".equals(method) && "/api/apps/install".equals(path)) {
                response = runtime.installApp(bodyJson == null ? null : bodyJson.optString("fileName", null));
                if (!response.optBoolean("ok", false)) {
                    statusCode = 409;
                }
            } else if ("POST".equals(method) && "/api/apps/uninstall".equals(path)) {
                response = runtime.uninstallApp(bodyJson == null ? null : bodyJson.optString("packageName", null));
                if (!response.optBoolean("ok", false)) {
                    statusCode = 409;
                }
            } else if ("POST".equals(method) && "/api/shell/exec".equals(path)) {
                String command = bodyJson == null ? null : bodyJson.optString("command", null);
                long timeout = bodyJson == null ? 5_000L : bodyJson.optLong("timeoutMs", 5_000L);
                response = runtime.executeShell(command, timeout);
                if (!response.optBoolean("ok", false)) {
                    statusCode = 400;
                }
            } else if ("GET".equals(method) && "/api/shell".equals(path)) {
                response = new JSONObject()
                        .put("enabled", true)
                        .put("endpoint", "POST /api/shell/exec")
                        .put("commandField", "command")
                        .put("timeoutField", "timeoutMs")
                        .put("executor", "/system/bin/sh")
                        .put("uid", android.os.Process.myUid())
                        .put("root", android.os.Process.myUid() == 0);
            } else {
                response = error("not_found", "unknown endpoint");
                statusCode = 404;
            }
            writeJson(output, statusCode, response);
        } catch (Throwable error) {
            log.error("HTTP 请求处理失败", error);
            try {
                OutputStream output = socket.getOutputStream();
                writeJson(output, 500, error("internal_error", error.toString()));
            } catch (Throwable ignored) {
            }
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private MultipartUpload receiveMultipart(InputStream input, long contentLength, String contentType)
            throws Exception {
        if (contentLength <= 0) {
            throw new IllegalArgumentException("multipart body is empty");
        }
        String boundary = extractBoundary(contentType);
        if (boundary == null || boundary.length() == 0 || boundary.length() > 200) {
            throw new IllegalArgumentException("multipart boundary is missing");
        }
        String firstLine = readLine(input, MAX_HEADER_BYTES);
        if (!("--" + boundary).equals(firstLine)) {
            throw new IllegalArgumentException("unsupported multipart preamble");
        }
        Map<String, String> partHeaders = readHeaders(input);
        String disposition = partHeaders.get("content-disposition");
        String fileName = extractFileName(disposition);
        if (fileName == null || fileName.length() == 0) {
            throw new IllegalArgumentException("multipart file name is missing");
        }
        File temp = new File(runtime.getApps().getUploadDirectory(),
                ".upload-" + System.currentTimeMillis() + "-" + Thread.currentThread().getId() + ".part");
        LimitedOutputStream output = null;
        try {
            output = new LimitedOutputStream(new FileOutputStream(temp), AppManager.MAX_UPLOAD_BYTES);
            streamUntilBoundary(input, boundary, output);
            output.close();
            if (output.count <= 0) {
                throw new IllegalArgumentException("uploaded APK is empty");
            }
            return new MultipartUpload(temp, fileName);
        } catch (Throwable error) {
            if (output != null) {
                try {
                    output.close();
                } catch (Exception ignored) {
                }
            }
            deleteQuietly(temp);
            throw error;
        }
    }

    private static void streamUntilBoundary(InputStream input, String boundary,
                                            LimitedOutputStream output) throws Exception {
        byte[] marker = ("\r\n--" + boundary).getBytes("ISO-8859-1");
        int matched = 0;
        int value;
        boolean found = false;
        while ((value = input.read()) >= 0) {
            if (value == (marker[matched] & 0xff)) {
                matched++;
                if (matched == marker.length) {
                    found = true;
                    break;
                }
            } else {
                if (matched > 0) {
                    output.write(marker, 0, matched);
                    matched = 0;
                }
                if (value == (marker[0] & 0xff)) {
                    matched = 1;
                } else {
                    output.write(value);
                }
            }
        }
        if (!found) {
            if (matched > 0) {
                output.write(marker, 0, matched);
            }
            throw new IllegalArgumentException("multipart boundary was not found");
        }
        int suffix1 = input.read();
        int suffix2 = input.read();
        if (!((suffix1 == '-' && suffix2 == '-') || (suffix1 == '\r' && suffix2 == '\n'))) {
            throw new IllegalArgumentException("invalid multipart boundary suffix");
        }
    }

    private static Map<String, String> readHeaders(InputStream input) throws Exception {
        Map<String, String> headers = new HashMap<String, String>();
        while (true) {
            String line = readLine(input, MAX_HEADER_BYTES);
            if (line == null) {
                throw new IOException("unexpected end of headers");
            }
            if (line.length() == 0) {
                return headers;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                        line.substring(colon + 1).trim());
            }
        }
    }

    private static String readLine(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) >= 0) {
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                line.write(value);
            }
            if (line.size() > maxBytes) {
                throw new IOException("HTTP header line too long");
            }
        }
        if (value < 0 && line.size() == 0) {
            return null;
        }
        return new String(line.toByteArray(), Charset.forName("ISO-8859-1"));
    }

    private static byte[] readExact(InputStream input, long length) throws IOException {
        if (length > Integer.MAX_VALUE) {
            throw new IOException("body too large");
        }
        byte[] result = new byte[(int) length];
        int offset = 0;
        while (offset < result.length) {
            int read = input.read(result, offset, result.length - offset);
            if (read < 0) {
                throw new IOException("unexpected end of body");
            }
            offset += read;
        }
        return result;
    }

    private static long parseContentLength(String value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(value));
        } catch (NumberFormatException error) {
            return 0L;
        }
    }

    private static String extractBoundary(String contentType) {
        if (contentType == null) {
            return null;
        }
        String lower = contentType.toLowerCase(Locale.US);
        int index = lower.indexOf("boundary=");
        if (index < 0) {
            return null;
        }
        String value = contentType.substring(index + "boundary=".length()).trim();
        if (value.startsWith("\"")) {
            int end = value.indexOf('"', 1);
            return end > 0 ? value.substring(1, end) : null;
        }
        int semicolon = value.indexOf(';');
        return semicolon >= 0 ? value.substring(0, semicolon).trim() : value;
    }

    private static String extractFileName(String disposition) {
        if (disposition == null) {
            return null;
        }
        String lower = disposition.toLowerCase(Locale.US);
        int index = lower.indexOf("filename=");
        if (index < 0) {
            return null;
        }
        String value = disposition.substring(index + "filename=".length()).trim();
        if (value.startsWith("\"")) {
            int end = value.indexOf('"', 1);
            return end > 0 ? new File(value.substring(1, end)).getName() : null;
        }
        int semicolon = value.indexOf(';');
        return new File((semicolon >= 0 ? value.substring(0, semicolon) : value).trim()).getName();
    }

    private static List<String> readStringArray(JSONArray array) {
        if (array == null) {
            return null;
        }
        List<String> result = new java.util.ArrayList<String>();
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "").trim();
            if (value.length() > 0) {
                result.add(value);
            }
        }
        return result;
    }

    private static int queryInt(String query, String name, int fallback) {
        if (query == null) {
            return fallback;
        }
        for (String part : query.split("&")) {
            int equals = part.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            try {
                String key = URLDecoder.decode(part.substring(0, equals), "UTF-8");
                if (name.equals(key)) {
                    return Integer.parseInt(URLDecoder.decode(part.substring(equals + 1), "UTF-8"));
                }
            } catch (Exception ignored) {
            }
        }
        return fallback;
    }

    private static String localIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface networkInterface = interfaces.nextElement();
                    Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress address = addresses.nextElement();
                        if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                            return address.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "0.0.0.0";
    }

    private static JSONObject error(String code, String message) throws JSONException {
        return new JSONObject().put("ok", false).put("error", code).put("message", message);
    }

    private static void writeJson(OutputStream output, int statusCode, JSONObject body)
            throws IOException {
        byte[] bytes = body.toString().getBytes(Charset.forName("UTF-8"));
        String reason = reason(statusCode);
        String header = "HTTP/1.1 " + statusCode + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n"
                + "Content-Length: " + bytes.length + "\r\n\r\n";
        output.write(header.getBytes(Charset.forName("ISO-8859-1")));
        output.write(bytes);
        output.flush();
    }

    private static String reason(int statusCode) {
        switch (statusCode) {
            case 200:
                return "OK";
            case 202:
                return "Accepted";
            case 400:
                return "Bad Request";
            case 404:
                return "Not Found";
            case 409:
                return "Conflict";
            case 413:
                return "Payload Too Large";
            default:
                return "Internal Server Error";
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static final class MultipartUpload {
        final File file;
        final String fileName;

        MultipartUpload(File file, String fileName) {
            this.file = file;
            this.fileName = fileName;
        }
    }

    private static final class LimitedOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final long limit;
        long count;

        LimitedOutputStream(OutputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public void write(int value) throws IOException {
            ensure(1);
            delegate.write(value);
            count++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            ensure(length);
            delegate.write(bytes, offset, length);
            count += length;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        private void ensure(int length) throws IOException {
            if (count + length > limit) {
                throw new IOException("APK upload exceeds 64 MiB");
            }
        }
    }
}
